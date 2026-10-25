#include <jni.h>
#include "player_interpolation/frame_engine.h"

#include <limits>
#include <map>
#include <mutex>
#include <utility>
#include <array>
#include <algorithm>
#include <cstdlib>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

using innocent::interpolation::Backend;
using innocent::interpolation::Code;
using innocent::interpolation::Engine;
using innocent::interpolation::ImageView;
using innocent::interpolation::ModelView;
using innocent::interpolation::MutableImageView;
using innocent::interpolation::Options;
using innocent::interpolation::Status;

namespace {
constexpr std::size_t kMaxEngines = 2;
constexpr std::size_t kMaxPixels = 1920u * 1080u;
// 逐帧工具（RGBA→RGB8、场景分析）的上限：处理尺寸可到 4K；两者都按实际缓冲容量再校验。
constexpr std::size_t kMaxFramePixels = 4096u * 2160u;
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<Engine>> engines;
jlong sequence = 0;
std::size_t creating = 0;

std::shared_ptr<Engine> acquire(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = engines.find(id);
    return found == engines.end() ? nullptr : found->second;
}

jlongArray creation(JNIEnv* env, Code code, jlong handle) {
    const jlong values[] = {static_cast<jlong>(code), handle};
    auto result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

bool direct(JNIEnv* env, jobject buffer, void** data, std::size_t* capacity) {
    if (!buffer) return false;
    const auto length = env->GetDirectBufferCapacity(buffer);
    if (length <= 0 || static_cast<std::uint64_t>(length) > std::numeric_limits<std::size_t>::max()) return false;
    *data = env->GetDirectBufferAddress(buffer);
    *capacity = static_cast<std::size_t>(length);
    return *data != nullptr;
}
} // namespace

static jlongArray createEngine(JNIEnv* env, jint backend, jint gpuIndex, jint threads, jlong maxPixels) {
    if ((backend != 0 && backend != 1) || gpuIndex < 0 || gpuIndex > 15 || threads < 1 || threads > 4 ||
        maxPixels <= 0 || maxPixels > 4096LL * 2160)
        return creation(env, Code::InvalidArgument, 0);
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        if (engines.size() + creating >= kMaxEngines || sequence == std::numeric_limits<jlong>::max())
            return creation(env, Code::Busy, 0);
        ++creating;
    }
    Status status;
    std::shared_ptr<Engine> engine;
    try {
        Options options;
        options.backend = backend == 0 ? Backend::Cpu : Backend::Vulkan;
        options.gpuIndex = gpuIndex;
        options.threads = threads;
        options.maxPixels = static_cast<std::size_t>(maxPixels);
        engine = Engine::create(options, &status);
    } catch (...) {
        status.code = Code::AllocationFailed;
    }
    jlong id = 0;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        --creating;
        if (engine && status.ok()) {
            try {
                id = ++sequence;
                engines.emplace(id, std::move(engine));
            } catch (...) {
                id = 0;
                status.code = Code::AllocationFailed;
            }
        }
    }
    auto result = creation(env, status.code, id);
    // A failed JVM result allocation must not strand an unreachable native engine.
    if (!result && id != 0) {
        std::lock_guard<std::mutex> lock(registryMutex);
        engines.erase(id);
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_create(
    JNIEnv* env, jobject, jint backend, jint gpuIndex, jint threads) {
    return createEngine(env, backend, gpuIndex, threads, kMaxPixels);
}

// Explicit research budget. The production entry continues to call create() with its original limit.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_createWithPixelBudget(
    JNIEnv* env, jobject, jint backend, jint gpuIndex, jint threads, jlong maxPixels) {
    return createEngine(env, backend, gpuIndex, threads, maxPixels);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_load(
    JNIEnv* env, jobject, jlong id, jobject param, jobject weights) {
    auto engine = acquire(id);
    if (!engine) return static_cast<jint>(Code::NotLoaded);
    void *p = nullptr, *w = nullptr;
    std::size_t pSize = 0, wSize = 0;
    if (!direct(env, param, &p, &pSize) || !direct(env, weights, &w, &wSize))
        return static_cast<jint>(Code::InvalidArgument);
    return engine->load(ModelView{static_cast<std::uint8_t*>(p), pSize, static_cast<std::uint8_t*>(w), wSize}).value();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_process(
    JNIEnv* env, jobject, jlong id, jobject left, jobject right, jobject output,
    jint width, jint height, jint leftStride, jint rightStride, jint outputStride, jfloat timestep,
    jlong leftId, jlong rightId, jint flowScale) {
    auto engine = acquire(id);
    if (!engine) return static_cast<jint>(Code::NotLoaded);
    void *l = nullptr, *r = nullptr, *o = nullptr;
    std::size_t lSize = 0, rSize = 0, oSize = 0;
    if (width <= 0 || height <= 0 || leftStride <= 0 || rightStride <= 0 || outputStride <= 0 ||
        !direct(env, left, &l, &lSize) || !direct(env, right, &r, &rSize) || !direct(env, output, &o, &oSize))
        return static_cast<jint>(Code::InvalidArgument);
    const auto bufferClass = env->FindClass("java/nio/ByteBuffer");
    if (!bufferClass) return static_cast<jint>(Code::InvalidArgument);
    const auto isReadOnly = env->GetMethodID(bufferClass, "isReadOnly", "()Z");
    const auto readOnly = isReadOnly ? env->CallBooleanMethod(output, isReadOnly) : JNI_TRUE;
    env->DeleteLocalRef(bufferClass);
    if (env->ExceptionCheck() || readOnly) return static_cast<jint>(Code::InvalidArgument);
    return engine->processRgb8(
        ImageView{static_cast<std::uint8_t*>(l), width, height, static_cast<std::size_t>(leftStride), lSize},
        ImageView{static_cast<std::uint8_t*>(r), width, height, static_cast<std::size_t>(rightStride), rSize}, timestep,
        MutableImageView{static_cast<std::uint8_t*>(o), width, height, static_cast<std::size_t>(outputStride), oSize},
        leftId, rightId, flowScale).value();
}

extern "C" JNIEXPORT void JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_close(
    JNIEnv*, jobject, jlong id) {
    std::shared_ptr<Engine> removed;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        const auto found = engines.find(id);
        if (found == engines.end()) return;
        removed = std::move(found->second);
        engines.erase(found);
    }
    // An in-flight call owns a shared reference. Erasing never frees its live engine.
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_configure(
    JNIEnv*, jobject, jlong id, jint flags) {
    auto engine = acquire(id);
    if (!engine) return static_cast<jint>(Code::NotLoaded);
    // bit0 fp16, bit1 半分辨率光流, bit2 四分之一分辨率光流（bit1|bit2 为八分之一）, bit3 GPU RGB8 转换,
    // bit4 静态细节保护, bit5 融合光流上采样/warp/混合/RGB8 打包, bit6 RGB8 原帧输入, bit7 自适应光流比例。
    if ((flags & ~255) != 0) return static_cast<jint>(Code::InvalidArgument);
    const int flowScale = (flags & 6) == 6 ? 8 : (flags & 4) != 0 ? 4 : (flags & 2) != 0 ? 2 : 1;
    return engine->configure((flags & 1) != 0, flowScale, (flags & 8) != 0, (flags & 16) != 0,
        (flags & 32) != 0, (flags & 64) != 0, (flags & 128) != 0).value();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_unreliablePermille(
    JNIEnv*, jobject, jlong id) {
    auto engine = acquire(id);
    return engine ? engine->unreliablePermille() : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_histogramPermille(
    JNIEnv*, jobject, jlong id) {
    auto engine = acquire(id);
    return engine ? engine->histogramPermille() : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_patchedTiles(
    JNIEnv*, jobject, jlong id) {
    auto engine = acquire(id);
    return engine ? engine->patchedTiles() : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_setMotionGuard(
    JNIEnv*, jobject, jlong id, jboolean enabled) {
    if (auto engine = acquire(id)) engine->setMotionGuard(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_timings(
    JNIEnv* env, jobject, jlong id) {
    std::int64_t stages[3] = {0, 0, 0};
    if (auto engine = acquire(id)) engine->timings(stages);
    const jlong values[] = {stages[0], stages[1], stages[2]};
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

// Source-duplicate test for frame de-duplication: 1 when the mean sampled difference is at most 0.3, at most
// 0.5% of samples exceed 4 (compression noise) and none exceeds 16 (re-encoded copies of the same drawing);
// 0 otherwise, -1 for an invalid layout.
// Much stricter than analyzeScene's "effectively static": slow pans must not be dropped as duplicates.
extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_isDuplicate(
    JNIEnv* env, jobject, jobject left, jobject right, jint width, jint height, jint stride) {
    void *l = nullptr, *r = nullptr;
    std::size_t lSize = 0, rSize = 0;
    if (width <= 0 || height <= 0 || static_cast<std::uint64_t>(width) * height > kMaxFramePixels ||
        static_cast<std::int64_t>(stride) < static_cast<std::int64_t>(width) * 3 ||
        !direct(env, left, &l, &lSize) || !direct(env, right, &r, &rSize)) return -1;
    const auto required = static_cast<std::uint64_t>(height - 1) * stride + static_cast<std::uint64_t>(width) * 3;
    if (required > lSize || required > rSize) return -1;
    const auto* a = static_cast<const std::uint8_t*>(l);
    const auto* b = static_cast<const std::uint8_t*>(r);
    const int stepX = std::max(1, width / 320), stepY = std::max(1, height / 180);
    std::uint64_t sum = 0, count = 0, noisy = 0;
    for (int y = 0; y < height; y += stepY) {
        const auto* ra = a + static_cast<std::size_t>(y) * stride;
        const auto* rb = b + static_cast<std::size_t>(y) * stride;
        for (int x = 0; x < width * 3; x += stepX * 3) {
            for (int c = 0; c < 3; ++c) {
                const int d = std::abs(static_cast<int>(ra[x + c]) - rb[x + c]);
                if (d > 16) return 0;
                if (d > 4) ++noisy;
                sum += d;
                ++count;
            }
        }
    }
    return count > 0 && sum * 10 <= count * 3 && noisy * 200 <= count ? 1 : 0;
}

// 0: motion, 1: effectively static, 2: hard cut, -1: invalid layout. No allocation or pixel copies.
extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_analyzeScene(
    JNIEnv* env, jobject, jobject left, jobject right, jint width, jint height, jint stride) {
    void *l = nullptr, *r = nullptr;
    std::size_t lSize = 0, rSize = 0;
    if (width <= 0 || height <= 0 || static_cast<std::uint64_t>(width) * height > kMaxFramePixels ||
        static_cast<std::int64_t>(stride) < static_cast<std::int64_t>(width) * 3 ||
        !direct(env, left, &l, &lSize) || !direct(env, right, &r, &rSize)) return -1;
    const auto required = static_cast<std::uint64_t>(height - 1) * stride + static_cast<std::uint64_t>(width) * 3;
    if (required > lSize || required > rSize) return -1;
    const auto* a = static_cast<const std::uint8_t*>(l);
    const auto* b = static_cast<const std::uint8_t*>(r);
    std::uint64_t sum = 0, count = 0;
    bool still = true;
    // 采样网格约 320 列：成本不随处理尺寸增长（720p 步长 4，1080p 6，1440p 8，4K 12），判定语义按比例不变。
    const int step = std::max(4, width / 320);
    for (int y = 0; y < height && still; y += step) {
        for (int x = 0; x < width && still; x += step) {
            const auto i = static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 3;
            for (int c = 0; c < 3; ++c) {
                const int d = std::abs(static_cast<int>(a[i + c]) - b[i + c]);
                if (d > 6) { still = false; break; }
                sum += d;
                ++count;
            }
        }
    }
    if (still && count > 0 && sum * 2 <= count) return 1;

    std::array<int, 48> bins{};
    const int columns = std::min(width, 64), rows = std::min(height, 36);
    std::uint64_t difference = 0;
    int changed = 0;
    for (int gy = 0; gy < rows; ++gy) {
        const auto y = (gy * 2LL + 1) * height / (rows * 2LL);
        for (int gx = 0; gx < columns; ++gx) {
            const auto x = (gx * 2LL + 1) * width / (columns * 2LL);
            const auto i = static_cast<std::size_t>(y) * stride + static_cast<std::size_t>(x) * 3;
            int d = 0;
            for (int c = 0; c < 3; ++c) {
                ++bins[c * 16 + (a[i + c] >> 4)];
                --bins[c * 16 + (b[i + c] >> 4)];
                d += std::abs(static_cast<int>(a[i + c]) - b[i + c]);
            }
            difference += d;
            if (d >= 120) ++changed;
        }
    }
    int histogramDifference = 0;
    for (const int value : bins) histogramDifference += std::abs(value);
    const int samples = columns * rows;
    return changed * 10 >= samples * 7 && difference >= static_cast<std::uint64_t>(samples) * 135 &&
        histogramDifference * 20 >= samples * 6 * 9 ? 2 : 0;
}

// glReadPixels 的 RGBA8（自下而上已由绘制翻转处理）→ 带步长 RGB8；只做逐像素去 alpha 的内存搬运。
extern "C" JNIEXPORT jint JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameInterpolationNative_rgbaToRgb(
    JNIEnv* env, jobject, jobject rgba, jint width, jint height, jobject output, jint outputStride) {
    void *source = nullptr, *target = nullptr;
    std::size_t sourceSize = 0, targetSize = 0;
    if (width <= 0 || height <= 0 || static_cast<std::size_t>(width) * static_cast<std::size_t>(height) > kMaxFramePixels ||
        outputStride < width * 3 || !direct(env, rgba, &source, &sourceSize) || !direct(env, output, &target, &targetSize))
        return static_cast<jint>(Code::InvalidArgument);
    const auto rowBytes = static_cast<std::size_t>(width) * 4u;
    if (rowBytes * static_cast<std::size_t>(height) > sourceSize ||
        (static_cast<std::size_t>(height) - 1) * static_cast<std::size_t>(outputStride) +
            static_cast<std::size_t>(width) * 3u > targetSize)
        return static_cast<jint>(Code::InvalidArgument);
    const auto* in = static_cast<const std::uint8_t*>(source);
    auto* out = static_cast<std::uint8_t*>(target);
    for (int row = 0; row < height; ++row) {
        const std::uint8_t* pixel = in + static_cast<std::size_t>(row) * rowBytes;
        std::uint8_t* rgb = out + static_cast<std::size_t>(row) * static_cast<std::size_t>(outputStride);
        int column = 0;
#if defined(__ARM_NEON)
        // 16 像素一次去交错再紧凑写入；保留尾部逐像素路径，不跨行读取或覆盖步长 padding。
        for (; column + 16 <= width; column += 16, pixel += 64, rgb += 48) {
            const uint8x16x4_t rgbaPixels = vld4q_u8(pixel);
            uint8x16x3_t rgbPixels;
            rgbPixels.val[0] = rgbaPixels.val[0];
            rgbPixels.val[1] = rgbaPixels.val[1];
            rgbPixels.val[2] = rgbaPixels.val[2];
            vst3q_u8(rgb, rgbPixels);
        }
#endif
        for (; column < width; ++column, pixel += 4, rgb += 3) {
            rgb[0] = pixel[0]; rgb[1] = pixel[1]; rgb[2] = pixel[2];
        }
    }
    return static_cast<jint>(Code::Ok);
}
