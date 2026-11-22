package eu.kanade.tachiyomi.util.upscale

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

// KMK -->
/**
 * Coordinates AI upscaling for the reader and for downloaded chapters.
 *
 * Reader flow: when a page becomes ready, [onPageReady] queues it and the next few pages. A single
 * background worker upscales them nearest-to-the-current-page first and stores the results in
 * [UpscaleStore]. The page holders ask [cachedStream] for an upscaled version first and re-render
 * when [completed] reports a page they show.
 *
 * Download flow: [enqueueDownloadedChapter] upscales every page of a finished download into the
 * pinned (never trimmed) store, so it can be read offline later.
 */
object UpscaleManager {

    data class PageKey(val mangaId: Long, val chapterId: Long, val index: Int)

    /** Pages ahead of the current one that are queued as well. */
    private const val PAGES_AHEAD = 3

    /** Larger inputs are skipped: the 2x result would not fit comfortably in memory. */
    private const val MAX_INPUT_PIXELS = 4_000_000

    /** Already high resolution pages gain little. */
    private const val MAX_INPUT_WIDTH = 2000

    /** WebP cannot store more than 16383 px per side, and the output is 2x the input. */
    private const val MAX_INPUT_HEIGHT = 8000

    private const val TRIM_EVERY_N_SAVES = 20

    private val context: Application by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The GPU handles one page at a time, whether it comes from the reader or a download. */
    private val engineMutex = Mutex()

    private val lock = Any()
    private val pending = LinkedHashMap<PageKey, () -> InputStream>()
    private var worker: Job? = null

    @Volatile
    private var focus: PageKey? = null
    private var savesSinceTrim = 0

    /** Pages that cannot be upscaled (unsupported format, too large, failed): do not retry this session. */
    private val skipped = ConcurrentHashMap.newKeySet<PageKey>()

    /** Chapters whose download was deleted: a running download-upscale job must stop writing for them. */
    private val deletedChapterIds = ConcurrentHashMap.newKeySet<Long>()

    private val _completed = MutableSharedFlow<PageKey>(extraBufferCapacity = 64)

    /** Emits the key of every page that has just been upscaled while reading. */
    val completed: SharedFlow<PageKey> = _completed.asSharedFlow()

    val isEnabled: Boolean
        get() = readerPreferences.upscaleEnabled().get() && NativeUpscaler.isSupported

    private val model: String
        get() = readerPreferences.upscaleModel().get()

    fun keyOf(page: ReaderPage): PageKey? {
        val chapter = try {
            page.chapter.chapter
        } catch (_: UninitializedPropertyAccessException) {
            return null
        }
        return PageKey(chapter.manga_id ?: return null, chapter.id ?: return null, page.index)
    }

    /** An upscaled version of [page] if one is stored, to be used instead of `page.stream`. */
    fun cachedStream(page: ReaderPage): (() -> InputStream)? {
        if (!isEnabled) return null
        val key = keyOf(page) ?: return null
        val file = UpscaleStore.find(context, key.mangaId, key.chapterId, key.index, model) ?: return null
        return { file.inputStream() }
    }

    /** Called by the viewers when a page is ready to show: queue it and the pages after it. */
    fun onPageReady(page: ReaderPage) {
        if (!isEnabled) return
        val pages = try {
            page.chapter.pages
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
        for (offset in 0..PAGES_AHEAD) {
            val target = if (offset == 0) page else pages?.getOrNull(page.index + offset) ?: continue
            val key = keyOf(target) ?: continue
            val stream = target.stream ?: continue
            enqueue(key, stream)
        }
    }

    /** Called when the reader's current page changes; decides what the worker does next. */
    fun setFocus(page: ReaderPage) {
        focus = keyOf(page) ?: return
    }

    /** Called when the reader closes: drops queued reader pages (downloads keep running). */
    fun onReaderClosed() {
        synchronized(lock) { pending.clear() }
        focus = null
    }

    private fun enqueue(key: PageKey, stream: () -> InputStream) {
        if (key in skipped) return
        if (UpscaleStore.hasPinnedChapter(context, key.mangaId, key.chapterId)) return
        if (UpscaleStore.find(context, key.mangaId, key.chapterId, key.index, model) != null) return
        synchronized(lock) {
            if (pending.containsKey(key)) return
            pending[key] = stream
            if (worker?.isActive != true) {
                worker = scope.launch { runWorker() }
            }
        }
    }

    private suspend fun runWorker() {
        while (true) {
            val next = synchronized(lock) {
                val key = pickNext()
                if (key == null) {
                    worker = null
                    return
                }
                key to pending.remove(key)!!
            }
            val (key, stream) = next
            val done = try {
                engineMutex.withLock { processOne(key, stream, pinned = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Upscaling page ${key.index} failed" }
                skipped += key
                false
            }
            if (done) {
                _completed.tryEmit(key)
                trimIfNeeded()
            }
        }
    }

    /** Nearest page at or after the focus first; pages behind it are less urgent. */
    private fun pickNext(): PageKey? {
        if (pending.isEmpty()) return null
        val current = focus ?: return pending.keys.first()
        return pending.keys.minBy { key ->
            when {
                key.chapterId != current.chapterId -> 10_000 + key.index
                key.index >= current.index -> key.index - current.index
                else -> (current.index - key.index) * 3
            }
        }
    }

    private fun trimIfNeeded() {
        if (++savesSinceTrim < TRIM_EVERY_N_SAVES) return
        savesSinceTrim = 0
        val maxBytes = readerPreferences.upscaleCacheSizeMb().get() * 1024L * 1024L
        UpscaleStore.trimCache(context, maxBytes)
    }

    /**
     * Decodes, upscales and stores one page. Must be called with [engineMutex] held.
     * Returns true when a new upscaled file was written.
     */
    private fun processOne(key: PageKey, openStream: () -> InputStream, pinned: Boolean): Boolean {
        val modelName = model
        val target = UpscaleStore.file(context, pinned, key.mangaId, key.chapterId, key.index, modelName)
        if (target.length() > 0L) return false

        if (!NativeUpscaler.ensureModel(context, modelName)) {
            skipped += key
            return false
        }
        val input = decode(openStream)
        if (input == null) {
            skipped += key
            return false
        }
        try {
            val output = NativeUpscaler.upscale(input)
            if (output == null) {
                skipped += key
                return false
            }
            try {
                val saved = UpscaleStore.save(output, target)
                if (!saved) skipped += key
                return saved
            } finally {
                output.recycle()
            }
        } finally {
            input.recycle()
        }
    }

    private fun decode(openStream: () -> InputStream): Bitmap? {
        return try {
            if (ImageUtil.findImageType(openStream) == ImageUtil.ImageType.GIF) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            if (bounds.outWidth > MAX_INPUT_WIDTH || bounds.outHeight > MAX_INPUT_HEIGHT) return null
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_INPUT_PIXELS) return null

            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            openStream().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Could not decode page for upscaling" }
            null
        }
    }

    // region downloads

    /** Upscales all pages of a freshly downloaded chapter into the pinned store (if enabled). */
    fun enqueueDownloadedChapter(manga: Manga, chapter: Chapter, source: Source) {
        if (!isEnabled || !readerPreferences.upscaleOnDownload().get()) return
        deletedChapterIds -= chapter.id
        scope.launch {
            try {
                upscaleDownloadedChapter(manga, chapter, source)
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Upscaling downloaded chapter ${chapter.name} failed" }
            }
        }
    }

    private suspend fun upscaleDownloadedChapter(manga: Manga, chapter: Chapter, source: Source) {
        val downloadProvider: DownloadProvider = Injekt.get()
        val dir = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            // SY -->
            manga.ogTitle,
            // SY <--
            source,
        ) ?: return

        if (dir.isFile) {
            dir.archiveReader(context).use { reader ->
                if (reader.encrypted) return // cannot read password protected archives here
                val names = reader.useEntries { entries ->
                    entries
                        .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .map { it.name }
                        .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                        .toList()
                }
                names.forEachIndexed { index, name ->
                    upscaleDownloadedPage(manga, chapter, index) { reader.getInputStream(name)!! }
                }
            }
        } else {
            // Same filter and order as DownloadManager.buildPageList, so page indices line up.
            val files = dir.listFiles().orEmpty()
                .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedBy { it.name }
            files.forEachIndexed { index, file ->
                upscaleDownloadedPage(manga, chapter, index) { file.openInputStream() }
            }
        }
    }

    private suspend fun upscaleDownloadedPage(
        manga: Manga,
        chapter: Chapter,
        index: Int,
        openStream: () -> InputStream,
    ) {
        if (chapter.id in deletedChapterIds || !isEnabled) return
        val key = PageKey(manga.id, chapter.id, index)
        engineMutex.withLock { processOne(key, openStream, pinned = true) }
    }

    /** Removes upscaled pages (cache and downloaded) of deleted chapters. */
    fun deleteChapters(chapters: List<Chapter>) {
        chapters.forEach {
            deletedChapterIds += it.id
            UpscaleStore.deleteChapter(context, it.mangaId, it.id)
        }
    }

    fun deleteManga(manga: Manga) {
        UpscaleStore.deleteManga(context, manga.id)
    }

    // endregion
}
// KMK <--
