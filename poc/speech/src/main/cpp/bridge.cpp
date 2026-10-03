#include <jni.h>
#include <atomic>
#include <memory>
#include <string>
#include <vector>
#include "whisper.h"

static std::atomic_bool cancelled{false};
static bool should_abort(void *) { return cancelled.load(); }
static void no_log(ggml_log_level, const char *, void *) {}
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT void JNICALL
Java_life_mosaic_voice_WhisperNative_cancel(JNIEnv *, jobject) { cancelled = true; }

extern "C" JNIEXPORT jstring JNICALL
Java_life_mosaic_voice_WhisperNative_transcribe(JNIEnv *env, jobject, jstring path, jfloatArray pcm) {
    cancelled = false;
    whisper_log_set(no_log, nullptr); // Do not log personal audio or transcripts.
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return nullptr;
    std::string model(chars);
    env->ReleaseStringUTFChars(path, chars);
    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(
        whisper_init_from_file_with_params(model.c_str(), cp), whisper_free);
    if (!ctx) { fail(env, "Could not load the local ivrit.ai GGML model"); return nullptr; }
    if (cancelled) { fail(env, "Cancelled"); return nullptr; }
    std::vector<float> audio(env->GetArrayLength(pcm));
    env->GetFloatArrayRegion(pcm, 0, audio.size(), audio.data());
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = 4;
    p.language = "he";
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.abort_callback = should_abort;
    if (whisper_full(ctx.get(), p, audio.data(), audio.size()) != 0 || cancelled) {
        fail(env, "Transcription failed or was cancelled"); return nullptr;
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(ctx.get()); ++i)
        text += whisper_full_get_segment_text(ctx.get(), i);
    return env->NewStringUTF(text.c_str());
}
