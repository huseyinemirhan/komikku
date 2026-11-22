// KMK: minimal JNI bridge around the Waifu2x/Real-CUGAN ncnn engine (Vulkan).
// Derived from the engine in HaoweiLi97/mihon_img_upscale (Apache-2.0); NPU, Anime4K and
// LiteRT backends were removed.

#include "monochrome_guard.h"
#include "openmp_runtime.h"
#include "periodic_texture_guard.h"
#include "waifu2x.h"

#include <android/bitmap.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <string>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#define TAG "KmkUpscale"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static Waifu2x *g_engine = nullptr;
static std::mutex g_lock;
static std::atomic<bool> g_abort{false};
static std::atomic<int> g_progress{0};
static std::atomic<int> g_ui_busy{0};

namespace {

// Real-CUGAN can invent faint chroma on neutral (black & white) manga pages. Pages that were
// classified as monochrome get their output flattened back to luma.
void force_monochrome_output(unsigned char *rgba, int width, int height, int stride) {
  if (!rgba || width <= 0 || height <= 0 || stride < width * 4) return;

  for (int y = 0; y < height; ++y) {
    unsigned char *row = rgba + static_cast<size_t>(y) * stride;
    int x = 0;
#if defined(__ARM_NEON)
    for (; x + 8 <= width; x += 8) {
      uint8x8x4_t pixels = vld4_u8(row + static_cast<size_t>(x) * 4);
      uint16x8_t luma = vmull_u8(pixels.val[0], vdup_n_u8(77));
      luma = vmlal_u8(luma, pixels.val[1], vdup_n_u8(150));
      luma = vmlal_u8(luma, pixels.val[2], vdup_n_u8(29));
      luma = vaddq_u16(luma, vdupq_n_u16(128));
      const uint8x8_t gray = vshrn_n_u16(luma, 8);
      pixels.val[0] = gray;
      pixels.val[1] = gray;
      pixels.val[2] = gray;
      vst4_u8(row + static_cast<size_t>(x) * 4, pixels);
    }
#endif
    for (; x < width; ++x) {
      unsigned char *pixel = row + static_cast<size_t>(x) * 4;
      const unsigned char luma =
          static_cast<unsigned char>((77 * pixel[0] + 150 * pixel[1] + 29 * pixel[2] + 128) >> 8);
      pixel[0] = luma;
      pixel[1] = luma;
      pixel[2] = luma;
    }
  }
}

}  // namespace

// The prebuilt ncnn package contains LLVM OpenMP, whose CPU affinity discovery can abort the
// process on some Android kernels. Must run before ncnn touches OpenMP.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
  configure_android_openmp();
  return JNI_VERSION_1_6;
}

/**
 * Loads a Real-CUGAN 2x model ("<modelDir>/<modelName>.param/.bin") on Vulkan GPU 0.
 * precision: 0 = fp16 storage (fast), 1 = fp32 (slowest, most compatible).
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_upscale_NativeUpscaler_nativeInit(
    JNIEnv *env, jobject, jstring model_dir, jstring model_name, jint tile_sleep_ms, jint precision) {
  g_abort = true;  // stop anything running on the old engine
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort = false;

  ncnn::create_gpu_instance();
  if (ncnn::get_gpu_count() <= 0) {
    LOGE("No Vulkan device available");
    return JNI_FALSE;
  }

  delete g_engine;
  g_engine = nullptr;

  const char *dir = env->GetStringUTFChars(model_dir, nullptr);
  const char *name = env->GetStringUTFChars(model_name, nullptr);
  const std::string base = std::string(dir) + "/" + name;
  env->ReleaseStringUTFChars(model_dir, dir);
  env->ReleaseStringUTFChars(model_name, name);

  auto *engine = new Waifu2x(0, false, 0, precision, false);
  engine->noise = 0;
  engine->scale = 2;
  engine->tilesize = 128;   // short GPU tasks keep the UI responsive
  engine->prepadding = 18;  // official Real-CUGAN 2x padding, avoids seams
  engine->tile_sleep_ms = tile_sleep_ms;
  engine->progress_ptr = &g_progress;
  engine->ui_busy_ptr = &g_ui_busy;
  engine->should_abort_ptr = &g_abort;

  if (engine->load(base + ".param", base + ".bin") != 0) {
    LOGE("Failed to load model %s", base.c_str());
    delete engine;
    return JNI_FALSE;
  }
  g_engine = engine;
  LOGD("Model loaded: %s (precision=%d, fused=%d)", base.c_str(), precision,
       engine->has_gpu_pipeline() ? 1 : 0);
  return JNI_TRUE;
}

/** Returns a new 2x ARGB_8888 bitmap, or null on failure / abort. The input is left untouched. */
extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_upscale_NativeUpscaler_nativeProcess(JNIEnv *env, jobject, jobject bitmap) {
  int ret = -1;
  jobject out_bitmap = nullptr;

  {
    std::unique_lock<std::mutex> lock(g_lock);
    if (!g_engine) return nullptr;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return nullptr;

    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);

    ncnn::Mat packed_input(w, h, static_cast<size_t>(4u), 1);
    if (packed_input.empty()) {
      AndroidBitmap_unlockPixels(env, bitmap);
      return nullptr;
    }
    for (int y = 0; y < h; y++) {
      memcpy(static_cast<unsigned char *>(packed_input.data) + static_cast<size_t>(y) * w * 4,
             static_cast<const unsigned char *>(pixels) + static_cast<size_t>(y) * info.stride,
             static_cast<size_t>(w) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    const unsigned char *packed = static_cast<const unsigned char *>(packed_input.data);
    const bool is_monochrome = monochrome_guard::is_neutral_rgba(packed, w, h);
    // Texture guard is a Real-CUGAN Pro workaround; always disabled for the SE models we ship.
    periodic_texture_guard::Analysis texture_analysis(packed, w, h, w * 4, false);

    const int out_w = w * g_engine->scale;
    const int out_h = h * g_engine->scale;

    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jmethodID create = env->GetStaticMethodID(
        bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(config_class, argb);
    out_bitmap = env->CallStaticObjectMethod(bitmap_class, create, out_w, out_h, config);
    if (env->ExceptionCheck()) {  // OutOfMemoryError on very large pages
      env->ExceptionClear();
      return nullptr;
    }

    if (out_bitmap) {
      void *out_pixels = nullptr;
      if (AndroidBitmap_lockPixels(env, out_bitmap, &out_pixels) == 0) {
        AndroidBitmapInfo out_info{};
        AndroidBitmap_getInfo(env, out_bitmap, &out_info);

        bool has_alpha = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) != ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
        if (has_alpha) {
          has_alpha = false;
          for (int i = 0; i < w * h; i++) {
            if (packed[i * 4 + 3] != 255) {
              has_alpha = true;
              break;
            }
          }
        }

        g_progress.store(0);
        const periodic_texture_guard::TilePlan &plan = texture_analysis.plan(g_engine->tilesize);

        if (!plan.any_risky && g_engine->has_gpu_pipeline()) {
          ret = g_engine->process_gpu(packed_input, out_pixels, out_info.stride, has_alpha, &g_progress);
        }
        if (ret != 0 && !g_abort.load()) {
          ncnn::Mat in = ncnn::Mat::from_pixels(packed, ncnn::Mat::PIXEL_RGBA, w, h);
          ret = g_engine->process(in, out_pixels, out_info.stride, has_alpha, lock, plan, &g_progress);
        }
        if (ret == 0 && is_monochrome) {
          force_monochrome_output(static_cast<unsigned char *>(out_pixels), out_info.width,
                                  out_info.height, out_info.stride);
        }
        AndroidBitmap_unlockPixels(env, out_bitmap);
      }
    }
  }

  if (ret != 0 || !out_bitmap) {
    if (out_bitmap) {
      jclass cls = env->GetObjectClass(out_bitmap);
      env->CallVoidMethod(out_bitmap, env->GetMethodID(cls, "recycle", "()V"));
      env->DeleteLocalRef(cls);
    }
    return nullptr;  // caller keeps showing the original page
  }
  return out_bitmap;
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_upscale_NativeUpscaler_nativeDestroy(JNIEnv *, jobject) {
  g_abort.store(true);
  std::lock_guard<std::mutex> lock(g_lock);
  delete g_engine;
  g_engine = nullptr;
  g_abort.store(false);
  // The ncnn GPU instance is intentionally kept; recreating it is slow.
}
