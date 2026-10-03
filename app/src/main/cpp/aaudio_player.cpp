#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <stdint.h>
#include <stdio.h>

#define LOG_TAG "AAudioPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Ctx {
    AAudioStream *stream = nullptr;
    int32_t channels = 2;
};

const char *sharingName(aaudio_sharing_mode_t m) {
    return m == AAUDIO_SHARING_MODE_EXCLUSIVE ? "EXCLUSIVE" : "SHARED";
}

const char *perfName(aaudio_performance_mode_t m) {
    switch (m) {
        case AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: return "LOW_LATENCY";
        case AAUDIO_PERFORMANCE_MODE_POWER_SAVING: return "POWER_SAVING";
        default: return "NONE";
    }
}

// 지정한 공유 모드로 스트림 열기
aaudio_result_t openWith(aaudio_sharing_mode_t sharing, int32_t sampleRate, int32_t channels,
                         AAudioStream **out) {
    AAudioStreamBuilder *b = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    if (r != AAUDIO_OK) return r;

    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, sharing);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setSampleRate(b, sampleRate);

    r = AAudioStreamBuilder_openStream(b, out);
    AAudioStreamBuilder_delete(b);
    return r;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_myapp_AAudioPlayer_nativeOpen(JNIEnv *, jclass, jint sampleRate, jint channels) {
    AAudioStream *stream = nullptr;

    // MMAP 경로는 EXCLUSIVE + LOW_LATENCY 일 때 사용되므로 먼저 시도하고,
    // 실패하면 SHARED 로 다시 시도한다
    aaudio_result_t r = openWith(AAUDIO_SHARING_MODE_EXCLUSIVE, sampleRate, channels, &stream);
    if (r != AAUDIO_OK) {
        LOGI("EXCLUSIVE open failed (%s), retry SHARED", AAudio_convertResultToText(r));
        r = openWith(AAUDIO_SHARING_MODE_SHARED, sampleRate, channels, &stream);
    }
    if (r != AAUDIO_OK) {
        LOGE("open failed: %s", AAudio_convertResultToText(r));
        return 0;
    }

    Ctx *c = new Ctx();
    c->stream = stream;
    c->channels = channels;

    LOGI("opened: rate=%d ch=%d sharing=%s perf=%s burst=%d buffer=%d",
         AAudioStream_getSampleRate(stream), AAudioStream_getChannelCount(stream),
         sharingName(AAudioStream_getSharingMode(stream)),
         perfName(AAudioStream_getPerformanceMode(stream)),
         AAudioStream_getFramesPerBurst(stream),
         AAudioStream_getBufferSizeInFrames(stream));
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jstring JNICALL
Java_com_example_myapp_AAudioPlayer_nativeGetInfo(JNIEnv *env, jclass, jlong handle) {
    Ctx *c = reinterpret_cast<Ctx *>(handle);
    char buf[160];
    snprintf(buf, sizeof(buf), "AAudio %s / %s, %d Hz, burst %d frames, buffer %d frames",
             sharingName(AAudioStream_getSharingMode(c->stream)),
             perfName(AAudioStream_getPerformanceMode(c->stream)),
             AAudioStream_getSampleRate(c->stream),
             AAudioStream_getFramesPerBurst(c->stream),
             AAudioStream_getBufferSizeInFrames(c->stream));
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_example_myapp_AAudioPlayer_nativeStart(JNIEnv *, jclass, jlong handle) {
    Ctx *c = reinterpret_cast<Ctx *>(handle);
    return AAudioStream_requestStart(c->stream);
}

JNIEXPORT jint JNICALL
Java_com_example_myapp_AAudioPlayer_nativePause(JNIEnv *, jclass, jlong handle) {
    Ctx *c = reinterpret_cast<Ctx *>(handle);
    return AAudioStream_requestPause(c->stream);
}

// 반환값: 실제로 쓴 바이트 수 (타임아웃 시 0), 오류 시 음수
JNIEXPORT jint JNICALL
Java_com_example_myapp_AAudioPlayer_nativeWrite(JNIEnv *env, jclass, jlong handle,
                                                jobject buf, jint offset, jint bytes) {
    Ctx *c = reinterpret_cast<Ctx *>(handle);
    uint8_t *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buf));
    if (base == nullptr) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;

    const int32_t bytesPerFrame = 2 * c->channels;
    const int32_t frames = bytes / bytesPerFrame;
    if (frames <= 0) return bytes;  // 프레임 단위 미만의 자투리는 버림

    const int64_t timeoutNs = 100LL * 1000 * 1000;  // 100ms
    aaudio_result_t r = AAudioStream_write(c->stream, base + offset, frames, timeoutNs);
    if (r < 0) return r;
    return r * bytesPerFrame;
}

JNIEXPORT jlong JNICALL
Java_com_example_myapp_AAudioPlayer_nativeFramesRead(JNIEnv *, jclass, jlong handle) {
    Ctx *c = reinterpret_cast<Ctx *>(handle);
    return AAudioStream_getFramesRead(c->stream);
}

JNIEXPORT void JNICALL
Java_com_example_myapp_AAudioPlayer_nativeClose(JNIEnv *, jclass, jlong handle) {
    Ctx *c = reinterpret_cast<Ctx *>(handle);
    if (c == nullptr) return;
    AAudioStream_requestStop(c->stream);
    AAudioStream_close(c->stream);
    delete c;
}

}  // extern "C"