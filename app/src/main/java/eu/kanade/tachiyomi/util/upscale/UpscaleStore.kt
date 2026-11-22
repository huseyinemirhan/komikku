package eu.kanade.tachiyomi.util.upscale

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File

// KMK -->
/**
 * On-disk storage for upscaled pages.
 *
 * Two areas:
 *  - **cache** (`cacheDir/upscaled`): pages upscaled while reading. Trimmed oldest-first to a size
 *    limit chosen in settings; Android may also clear it.
 *  - **pinned** (`filesDir/upscaled`): pages upscaled for downloaded chapters. Never trimmed, deleted
 *    together with the chapter's download.
 *
 * Pages are stored as lossy WebP (quality 90) to keep them far smaller than a 2x PNG.
 */
object UpscaleStore {

    private const val DIR = "upscaled"
    private const val QUALITY = 90

    private fun root(context: Context, pinned: Boolean): File =
        File(if (pinned) context.filesDir else context.cacheDir, DIR)

    private fun chapterDir(context: Context, pinned: Boolean, mangaId: Long, chapterId: Long): File =
        File(root(context, pinned), "$mangaId/$chapterId")

    private fun pageName(index: Int, model: String?): String {
        val number = index.toString().padStart(4, '0')
        return if (model == null) "$number.webp" else "${number}_$model.webp"
    }

    fun file(context: Context, pinned: Boolean, mangaId: Long, chapterId: Long, index: Int, model: String): File {
        val dir = chapterDir(context, pinned, mangaId, chapterId)
        return File(dir, pageName(index, if (pinned) null else model))
    }

    /** Pinned (downloaded) pages win over the cache; any model counts for pinned pages. */
    fun find(context: Context, mangaId: Long, chapterId: Long, index: Int, model: String): File? {
        val pinned = file(context, true, mangaId, chapterId, index, model)
        if (pinned.length() > 0L) return pinned
        // A downloaded chapter can be split differently from its online page list, so its pages
        // must never be matched against the reader cache by index.
        if (hasPinnedChapter(context, mangaId, chapterId)) return null
        val cached = file(context, false, mangaId, chapterId, index, model)
        if (cached.length() > 0L) {
            cached.setLastModified(System.currentTimeMillis()) // recently used, trimmed last
            return cached
        }
        return null
    }

    fun hasPinnedChapter(context: Context, mangaId: Long, chapterId: Long): Boolean =
        chapterDir(context, true, mangaId, chapterId).exists()

    /** Writes [bitmap] atomically. Returns false if compression failed. */
    fun save(bitmap: Bitmap, target: File): Boolean {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val ok = try {
            tmp.outputStream().use { bitmap.compress(format, QUALITY, it) }
        } catch (_: Exception) {
            false
        }
        if (!ok || tmp.length() == 0L || !tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        return true
    }

    /** Deletes the oldest cached pages until the cache is under [maxBytes]. */
    fun trimCache(context: Context, maxBytes: Long) {
        val files = root(context, false).walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val target = (maxBytes * 0.9).toLong()
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= target) break
            total -= file.length()
            file.delete()
        }
    }

    fun deleteChapter(context: Context, mangaId: Long, chapterId: Long) {
        chapterDir(context, true, mangaId, chapterId).deleteRecursively()
        chapterDir(context, false, mangaId, chapterId).deleteRecursively()
    }

    fun deleteManga(context: Context, mangaId: Long) {
        File(root(context, true), mangaId.toString()).deleteRecursively()
        File(root(context, false), mangaId.toString()).deleteRecursively()
    }
}
// KMK <--
