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
    std::atomic_int phase{0};
    Clock::time_point deadline;
};
static Session * session(jlong handle) { return reinterpret_cast<Session *>(handle); }
static bool should_abort(void *data) {
    auto *s = static_cast<Session *>(data);
    return s->cancelled.load() || Clock::now() >= s->deadline;
}
static bool encoder_begin(whisper_context *, whisper_state *, void *data) {
    auto *s = static_cast<Session *>(data);
    s->phase = 1;
    return !should_abort(s);
}
static void decoding(whisper_context *, whisper_state *, const whisper_token_data *, int, float *, void *data) {
    static_cast<Session *>(data)->phase = 2;
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
    if (handle) { session(handle)->cancelled = false; session(handle)->phase = 0; }
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
Java_life_mosaic_voice_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jboolean accurate) {
    auto *s = session(handle);
    if (!s || !s->ctx) { fail(env, "Whisper session is not loaded"); return nullptr; }
    s->deadline = Clock::now() + std::chrono::seconds(30);
    if (should_abort(s)) { fail(env, "Cancelled"); return nullptr; }
    std::vector<float> audio(env->GetArrayLength(pcm));
    env->GetFloatArrayRegion(pcm, 0, audio.size(), audio.data());
    auto p = whisper_full_default_params(accurate ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = 4;
    p.language = "he";
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.max_tokens = 96;
    p.greedy.best_of = 1;
    p.beam_search.beam_size = accurate ? 5 : 1;
    p.temperature = 0.0f;
    p.temperature_inc = 0.0f; // No repeated decoding retries in the short-command POC.
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    whisper_reset_timings(s->ctx.get());
    p.encoder_begin_callback = encoder_begin;
    p.encoder_begin_callback_user_data = s;
    p.logits_filter_callback = decoding;
    p.logits_filter_callback_user_data = s;
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

extern "C" JNIEXPORT jint JNICALL
Java_life_mosaic_voice_WhisperNative_phase(JNIEnv *, jobject, jlong handle) {
    return handle ? session(handle)->phase.load() : 0;
}
extern "C" JNIEXPORT jstring JNICALL
Java_life_mosaic_voice_WhisperNative_timings(JNIEnv *env, jobject, jlong handle) {
    if (!handle) return env->NewStringUTF("");
    const auto *t = whisper_get_timings(session(handle)->ctx.get());
    std::string result = "encode: " + std::to_string(t->encode_ms) +
        " ms; decode: " + std::to_string(t->decode_ms + t->batchd_ms + t->prompt_ms) +
        " ms; sample: " + std::to_string(t->sample_ms) + " ms";
    return env->NewStringUTF(result.c_str());
}
