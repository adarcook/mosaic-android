#include <jni.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <string>
#include <vector>
#include "whisper.h"

using Clock = std::chrono::steady_clock;
struct Session {
    std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx{nullptr, whisper_free};
    std::atomic_bool cancelled{false};
    Clock::time_point deadline;
};
static Session * session(jlong handle) { return reinterpret_cast<Session *>(handle); }
static bool should_abort(void *data) {
    auto *s = static_cast<Session *>(data);
    return s->cancelled.load() || Clock::now() >= s->deadline;
}
static void no_log(ggml_log_level, const char *, void *) {}
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_life_mosaic_voice_WhisperNative_create(JNIEnv *env, jobject, jstring path) {
    whisper_log_set(no_log, nullptr);
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string model(chars);
    env->ReleaseStringUTFChars(path, chars);
    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    auto s = std::make_unique<Session>();
    s->ctx.reset(whisper_init_from_file_with_params(model.c_str(), cp));
    if (!s->ctx) { fail(env, "Could not load the local Whisper model"); return 0; }
    return reinterpret_cast<jlong>(s.release());
}
extern "C" JNIEXPORT void JNICALL
Java_life_mosaic_voice_WhisperNative_prepare(JNIEnv *, jobject, jlong handle) {
    if (handle) session(handle)->cancelled = false;
}
extern "C" JNIEXPORT void JNICALL
Java_life_mosaic_voice_WhisperNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) session(handle)->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL
Java_life_mosaic_voice_WhisperNative_release(JNIEnv *, jobject, jlong handle) {
    delete session(handle);
}
extern "C" JNIEXPORT jstring JNICALL
Java_life_mosaic_voice_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm) {
    auto *s = session(handle);
    if (!s || !s->ctx) { fail(env, "Whisper session is not loaded"); return nullptr; }
    s->deadline = Clock::now() + std::chrono::seconds(30);
    if (should_abort(s)) { fail(env, "Cancelled"); return nullptr; }
    std::vector<float> audio(env->GetArrayLength(pcm));
    env->GetFloatArrayRegion(pcm, 0, audio.size(), audio.data());
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = 4;
    p.language = "he";
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.max_tokens = 96;
    p.greedy.best_of = 1;
    p.temperature_inc = 0.0f; // No repeated decoding retries in the short-command POC.
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.abort_callback = should_abort;
    p.abort_callback_user_data = s;
    if (whisper_full(s->ctx.get(), p, audio.data(), audio.size()) != 0 || should_abort(s)) {
        fail(env, s->cancelled ? "Cancelled" : "Transcription failed or exceeded the 30-second inference budget");
        return nullptr;
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(s->ctx.get()); ++i)
        text += whisper_full_get_segment_text(s->ctx.get(), i);
    return env->NewStringUTF(text.c_str());
}
