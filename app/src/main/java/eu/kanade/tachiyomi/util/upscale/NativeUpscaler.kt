package eu.kanade.tachiyomi.util.upscale

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

// KMK -->
/**
 * Thin wrapper over the native Real-CUGAN (ncnn + Vulkan) 2x engine in `src/main/cpp`.
 *
 * Only arm64-v8a builds ship the native library; everywhere else [isSupported] is false and the
 * reader silently keeps showing the original pages.
 */
object NativeUpscaler {

    private const val LIBRARY = "komikku-upscale"
    private const val MODELS_ASSET_DIR = "upscale-models"

    /** Tile pause (ms) between GPU tiles, leaves some GPU time for scrolling/zooming. */
    private const val TILE_SLEEP_MS = 10

    private val libraryLoaded: Boolean = try {
        System.loadLibrary(LIBRARY)
        true
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "Upscaling library not available on this device" }
        false
    }

    @Volatile
    private var gpuUnusable = false

    val isSupported: Boolean
        get() = libraryLoaded && !gpuUnusable && Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    private var loadedModel: String? = null

    /**
     * Loads [model] (file name without extension, e.g. `up2x-conservative`) if it is not the one
     * already loaded. Returns false when no Vulkan GPU can run it.
     */
    @Synchronized
    fun ensureModel(context: Context, model: String): Boolean {
        if (!isSupported || gpuUnusable) return false
        if (loadedModel == model) return true

        val dir = extractModels(context)
        // fp16 storage is fast; fall back to fp32 on drivers that cannot create the fp16 pipelines.
        val ok = nativeInit(dir.absolutePath, model, TILE_SLEEP_MS, 0) ||
            nativeInit(dir.absolutePath, model, TILE_SLEEP_MS, 1)
        if (!ok) {
            gpuUnusable = true
            loadedModel = null
            logcat(LogPriority.WARN) { "Upscaling disabled: could not initialise model $model on Vulkan" }
            return false
        }
        loadedModel = model
        return true
    }

    /** Returns a new bitmap at 2x size, or null on failure. [input] must be ARGB_8888. */
    fun upscale(input: Bitmap): Bitmap? {
        if (!isSupported || loadedModel == null) return null
        return try {
            nativeProcess(input)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Upscaling failed" }
            null
        }
    }

    @Synchronized
    fun release() {
        if (libraryLoaded) nativeDestroy()
        loadedModel = null
    }

    /** Copies the bundled models to internal storage (ncnn loads them by path). */
    private fun extractModels(context: Context): File {
        val dir = File(context.filesDir, MODELS_ASSET_DIR).also { it.mkdirs() }
        val names = context.assets.list(MODELS_ASSET_DIR).orEmpty()
        for (name in names) {
            val target = File(dir, name)
            if (target.length() > 0L) continue
            // Copy to a temp file first so an interrupted copy never leaves a truncated model behind.
            val tmp = File(dir, "$name.tmp")
            context.assets.open("$MODELS_ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.renameTo(target)
        }
        return dir
    }

    private external fun nativeInit(modelDir: String, modelName: String, tileSleepMs: Int, precision: Int): Boolean
    private external fun nativeProcess(bitmap: Bitmap): Bitmap?
    private external fun nativeDestroy()
}
// KMK <--
