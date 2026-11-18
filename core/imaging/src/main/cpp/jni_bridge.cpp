#include <algorithm>

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <string>

#include "luma/engine/build_info.h"
#include "luma/engine/raw_pipeline.h"
#include "luma/engine/resample.h"

namespace {

constexpr const char* kTag = "LumaEngine";
using luma::engine::Burst;

luma::engine::Mat3 mat_at(const float* v, int index) {
    luma::engine::Mat3 m{};
    for (int i = 0; i < 9; ++i) m[static_cast<size_t>(i)] = v[index * 9 + i];
    return m;
}

Burst* from_handle(jlong handle) { return reinterpret_cast<Burst*>(handle); }

}  // namespace

/// Resamples one ARGB_8888 bitmap into another of a different size (Lanczos-3, multithreaded).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_datarohit_luma_core_imaging_ImagingEngine_nativeResample(JNIEnv* env, jclass, jobject src, jobject dst) {
    AndroidBitmapInfo si, di;
    if (AndroidBitmap_getInfo(env, src, &si) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, dst, &di) != ANDROID_BITMAP_RESULT_SUCCESS ||
        si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || di.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* sp = nullptr;
    void* dp = nullptr;
    if (AndroidBitmap_lockPixels(env, src, &sp) != ANDROID_BITMAP_RESULT_SUCCESS || !sp) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, dst, &dp) != ANDROID_BITMAP_RESULT_SUCCESS || !dp) {
        AndroidBitmap_unlockPixels(env, src);
        return JNI_FALSE;
    }
    const bool ok = luma::engine::resample_rgba8(static_cast<const uint8_t*>(sp), static_cast<int>(si.width), static_cast<int>(si.height),
                                                 static_cast<int>(si.stride), static_cast<uint8_t*>(dp), static_cast<int>(di.width),
                                                 static_cast<int>(di.height), static_cast<int>(di.stride));
    AndroidBitmap_unlockPixels(env, dst);
    AndroidBitmap_unlockPixels(env, src);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_datarohit_luma_core_imaging_ImagingEngine_nativeBuildInfo(JNIEnv* env, jclass) {
    const auto info = luma::engine::build_info();
    std::string text;
    text += info.version;
    text += '\n';
    text += info.architecture;
    text += '\n';
    text += info.compiler;
    text += '\n';
    text += info.simd_neon ? "neon" : "scalar";
    return env->NewStringUTF(text.c_str());
}

/// ints: sensorW, sensorH, cfa, shadingCols, shadingRows, cropX, cropY, cropW, cropH
/// floats: black[4], white, neutral[3], noiseScale, noiseOffset
extern "C" JNIEXPORT jlong JNICALL
Java_com_datarohit_luma_core_imaging_RawBurst_nativeCreate(JNIEnv* env, jclass, jintArray ints_in, jfloatArray floats_in,
                                                          jfloatArray shading_in) {
    if (env->GetArrayLength(ints_in) < 9 || env->GetArrayLength(floats_in) < 10) return 0;
    jint ints[9];
    jfloat floats[10];
    env->GetIntArrayRegion(ints_in, 0, 9, ints);
    env->GetFloatArrayRegion(floats_in, 0, 10, floats);
    luma::engine::RawMetadata meta;
    meta.sensor_width = ints[0];
    meta.sensor_height = ints[1];
    meta.cfa = static_cast<luma::engine::Cfa>(ints[2] & 3);
    for (int i = 0; i < 4; ++i) meta.black_level[static_cast<size_t>(i)] = floats[i];
    meta.white_level = floats[4];
    meta.as_shot_neutral = {floats[5], floats[6], floats[7]};
    meta.noise_scale = floats[8];
    meta.noise_offset = floats[9];
    if (shading_in && ints[3] > 1 && ints[4] > 1) {
        const jsize n = env->GetArrayLength(shading_in);
        if (n == ints[3] * ints[4] * 4) {
            meta.shading.columns = ints[3];
            meta.shading.rows = ints[4];
            meta.shading.gains.resize(static_cast<size_t>(n));
            env->GetFloatArrayRegion(shading_in, 0, n, meta.shading.gains.data());
        }
    }
    luma::engine::Rect crop{ints[5], ints[6], ints[7], ints[8]};
    auto* burst = new (std::nothrow) Burst(meta, crop);
    return reinterpret_cast<jlong>(burst);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_datarohit_luma_core_imaging_RawBurst_nativeAddFrame(JNIEnv* env, jclass, jlong handle, jobject buffer,
                                                            jint row_stride_bytes, jfloat brightness_scale) {
    Burst* burst = from_handle(handle);
    if (!burst || !buffer) return JNI_FALSE;
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!data || capacity <= 0) return JNI_FALSE;
    const auto& crop = burst->crop();
    const jlong needed = static_cast<jlong>(crop.y + crop.height - 1) * row_stride_bytes + static_cast<jlong>(crop.x + crop.width) * 2;
    if (needed > capacity) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "RAW buffer too small: %lld < %lld", static_cast<long long>(capacity),
                            static_cast<long long>(needed));
        return JNI_FALSE;
    }
    return burst->add_frame(reinterpret_cast<const uint16_t*>(data), row_stride_bytes / 2, brightness_scale) ? JNI_TRUE : JNI_FALSE;
}

/// color: cm1, cm2, fm1, fm2, cal1, cal2 (6 x 9 floats); colorInts: illuminant1, illuminant2, hasSecond, hasForward
/// options floats: exposureEv, localTone, shadows, highlights, contrast, saturation, sharpen, lumaDenoise,
///                 chromaDenoise, wbKelvin, wbTint[, superResolution]
/// options ints: outputW, outputH, look, space, merge, threads
/// returns stats floats (see RenderStats.fromArray)
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_datarohit_luma_core_imaging_RawBurst_nativeProcess(JNIEnv* env, jclass, jlong handle, jfloatArray color_in,
                                                           jintArray color_ints_in, jfloatArray opt_f_in, jintArray opt_i_in,
                                                           jobject bitmap) {
    Burst* burst = from_handle(handle);
    if (!burst) return nullptr;
    jfloat color[54];
    jint color_ints[4];
    jfloat of[12] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1.0f};
    jint oi[6];
    if (env->GetArrayLength(color_in) < 54 || env->GetArrayLength(color_ints_in) < 4 || env->GetArrayLength(opt_f_in) < 11 ||
        env->GetArrayLength(opt_i_in) < 6)
        return nullptr;
    env->GetFloatArrayRegion(color_in, 0, 54, color);
    env->GetIntArrayRegion(color_ints_in, 0, 4, color_ints);
    env->GetFloatArrayRegion(opt_f_in, 0, std::min<jsize>(12, env->GetArrayLength(opt_f_in)), of);
    env->GetIntArrayRegion(opt_i_in, 0, 6, oi);

    luma::engine::ColorCalibration cal;
    cal.color_matrix1 = mat_at(color, 0);
    cal.color_matrix2 = mat_at(color, 1);
    cal.forward_matrix1 = mat_at(color, 2);
    cal.forward_matrix2 = mat_at(color, 3);
    cal.calibration1 = mat_at(color, 4);
    cal.calibration2 = mat_at(color, 5);
    cal.illuminant1 = color_ints[0];
    cal.illuminant2 = color_ints[1];
    cal.has_second = color_ints[2] != 0;
    cal.has_forward = color_ints[3] != 0;

    luma::engine::ProcessOptions opt;
    opt.exposure_ev = of[0];
    opt.local_tone = of[1];
    opt.shadows = of[2];
    opt.highlights = of[3];
    opt.contrast = of[4];
    opt.saturation = of[5];
    opt.sharpen = of[6];
    opt.luma_denoise = of[7];
    opt.chroma_denoise = of[8];
    opt.white_balance_kelvin = of[9];
    opt.white_balance_tint = of[10];
    opt.super_resolution = of[11];
    opt.output_width = oi[0];
    opt.output_height = oi[1];
    opt.look = static_cast<luma::engine::Look>(oi[2]);
    opt.space = static_cast<luma::engine::OutputSpace>(oi[3]);
    opt.merge = oi[4] != 0;
    opt.threads = oi[5];

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "bitmap must be RGBA_8888");
        return nullptr;
    }
    int ow = 0, oh = 0;
    burst->output_size(opt, ow, oh);
    if (static_cast<int>(info.width) != ow || static_cast<int>(info.height) != oh) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "bitmap %ux%u does not match output %dx%d", info.width, info.height, ow, oh);
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return nullptr;
    const auto stats = burst->process(cal, opt, static_cast<uint8_t*>(pixels), static_cast<int>(info.stride));
    AndroidBitmap_unlockPixels(env, bitmap);
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", luma::engine::describe(stats).c_str());

    const float out[] = {static_cast<float>(stats.frames_added), static_cast<float>(stats.frames_merged),
                         static_cast<float>(stats.reference_index), stats.mean_merge_weight, stats.noise_reduction_db,
                         stats.applied_ev, stats.scene_key, stats.dynamic_range_ev, static_cast<float>(stats.output_width),
                         static_cast<float>(stats.output_height), stats.ms_align, stats.ms_merge, stats.ms_demosaic,
                         stats.ms_color, stats.ms_tone, stats.ms_finish, stats.ms_total, stats.super_resolution,
                         stats.subpixel_spread, stats.coverage_gap};
    const jsize n = static_cast<jsize>(sizeof(out) / sizeof(out[0]));
    jfloatArray result = env->NewFloatArray(n);
    if (result) env->SetFloatArrayRegion(result, 0, n, out);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_datarohit_luma_core_imaging_RawBurst_nativeMergedMosaic(JNIEnv* env, jclass, jlong handle, jobject buffer) {
    Burst* burst = from_handle(handle);
    if (!burst || !buffer) return JNI_FALSE;
    auto* data = static_cast<uint16_t*>(env->GetDirectBufferAddress(buffer));
    const auto& crop = burst->crop();
    if (!data || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(crop.width) * crop.height * 2) return JNI_FALSE;
    return burst->merged_mosaic(data) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_datarohit_luma_core_imaging_RawBurst_nativeCrop(JNIEnv* env, jclass, jlong handle) {
    Burst* burst = from_handle(handle);
    if (!burst) return nullptr;
    const auto& c = burst->crop();
    const jint v[] = {c.x, c.y, c.width, c.height, burst->frame_count()};
    jintArray out = env->NewIntArray(5);
    if (out) env->SetIntArrayRegion(out, 0, 5, v);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_datarohit_luma_core_imaging_RawBurst_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete from_handle(handle);
}
