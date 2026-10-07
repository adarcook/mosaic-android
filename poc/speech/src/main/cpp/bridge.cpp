#include <jni.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <string>
#include <vector>
#include "whisper.h"
#include "ggml-backend.h"

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
Java_life_mosaic_voice_WhisperNative_create(JNIEnv *env, jobject, jstring path, jboolean use_gpu) {
    whisper_log_set(no_log, nullptr);
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string model(chars);
    env->ReleaseStringUTFChars(path, chars);
    auto cp = whisper_context_default_params();
#ifndef MOSAIC_VULKAN
    if (use_gpu) { fail(env, "This APK has no Vulkan backend"); return 0; }
#endif
    // Match whisper.cpp's selection: phone GPUs may be registered as IGPU.
    if (use_gpu && !ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) &&
        !ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU)) {
        fail(env, "No compatible Vulkan GPU found; switch off GPU to use CPU"); return 0;
    }
    cp.use_gpu = use_gpu;
    cp.flash_attn = true;
    auto s = std::make_unique<Session>();
    s->ctx.reset(whisper_init_from_file_with_params(model.c_str(), cp));
    if (!s->ctx) { fail(env, "Could not load the local Whisper model"); return 0; }
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT jlong JNICALL
Java_life_mosaic_voice_WhisperNative_createHybrid(JNIEnv *env, jobject, jstring path) {
    whisper_log_set(no_log, nullptr);
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string model(chars);
    env->ReleaseStringUTFChars(path, chars);

    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    cp.mosaic_external_encoder = true;

    auto s = std::make_unique<Session>();
    s->ctx.reset(whisper_init_from_file_with_params(model.c_str(), cp));
    if (!s->ctx) {
        fail(env, "Could not load the local Whisper decoder model in external-encoder mode");
        return 0;
    }

    if (whisper_model_n_mels(s->ctx.get()) != 128 ||
        whisper_model_n_audio_ctx(s->ctx.get()) != 1500 ||
        whisper_model_n_audio_state(s->ctx.get()) != 1280) {
        fail(env, "Hybrid gate requires Whisper Large-v3 128x3000 -> 1500x1280 architecture");
        return 0;
    }

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
Java_life_mosaic_voice_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jboolean accurate, jint budget_seconds, jint audio_context) {
    auto *s = session(handle);
    if (!s || !s->ctx) { fail(env, "Whisper session is not loaded"); return nullptr; }
    if (budget_seconds != 30 && budget_seconds != 60 && budget_seconds != 90) {
        fail(env, "Invalid inference budget"); return nullptr;
    }
    s->deadline = Clock::now() + std::chrono::seconds(budget_seconds);
    if (should_abort(s)) { fail(env, "Cancelled"); return nullptr; }
    std::vector<float> audio(env->GetArrayLength(pcm));
    env->GetFloatArrayRegion(pcm, 0, audio.size(), audio.data());
    auto p = whisper_full_default_params(accurate ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = 2;
    // Short-command experiment: 512 frames = 10.24 seconds, including silence.
    // Never truncate input; retain the full 30-second context as an A/B control.
    if (audio.size() < 8000 || audio.size() > 16000 * 8 ||
        (audio_context != 0 && audio_context != 512) ||
        (audio_context != 0 && audio_context > whisper_n_audio_ctx(s->ctx.get()))) {
        fail(env, "Invalid short-command input/context"); return nullptr;
    }
    p.audio_ctx = audio_context;
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
        fail(env, s->cancelled ? "Cancelled" : "Transcription failed or exceeded the selected inference budget");
        return nullptr;
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(s->ctx.get()); ++i)
        text += whisper_full_get_segment_text(s->ctx.get(), i);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_life_mosaic_voice_WhisperNative_prepareEncoderInput(JNIEnv *env, jobject, jlong handle, jfloatArray pcm) {
    auto *s = session(handle);
    if (!s || !s->ctx) {
        fail(env, "Whisper hybrid session is not loaded");
        return nullptr;
    }

    const jsize n_samples = env->GetArrayLength(pcm);
    if (n_samples < 8000 || n_samples > 16000 * 8) {
        fail(env, "Hybrid gate accepts 0.5 to 8 seconds of 16 kHz PCM");
        return nullptr;
    }

    std::vector<float> audio(n_samples);
    env->GetFloatArrayRegion(pcm, 0, n_samples, audio.data());

    whisper_reset_timings(s->ctx.get());
    if (whisper_pcm_to_mel(s->ctx.get(), audio.data(), audio.size(), 2) != 0) {
        fail(env, "Whisper log-mel frontend failed");
        return nullptr;
    }

    const int n_mels = whisper_model_n_mels(s->ctx.get());
    const int n_frames = 2 * whisper_model_n_audio_ctx(s->ctx.get());
    const int n_elements = n_mels * n_frames;
    std::vector<float> input(n_elements);
    if (whisper_mosaic_copy_encoder_input(s->ctx.get(), input.data(), n_elements) != 0) {
        fail(env, "Could not copy Whisper encoder input");
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(n_elements);
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, n_elements, input.data());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_life_mosaic_voice_WhisperNative_transcribeEncoded(
        JNIEnv *env,
        jobject,
        jlong handle,
        jfloatArray encoded,
        jboolean accurate,
        jint budget_seconds) {
    auto *s = session(handle);
    if (!s || !s->ctx) {
        fail(env, "Whisper hybrid session is not loaded");
        return nullptr;
    }
    if (budget_seconds != 30 && budget_seconds != 60 && budget_seconds != 90) {
        fail(env, "Invalid hybrid decode budget");
        return nullptr;
    }

    const int expected =
            whisper_model_n_audio_ctx(s->ctx.get()) *
            whisper_model_n_audio_state(s->ctx.get());
    const jsize n_encoded = env->GetArrayLength(encoded);
    if (n_encoded != expected) {
        fail(env, "Unexpected Tensor G5 encoder output size");
        return nullptr;
    }

    std::vector<float> encoder_output(n_encoded);
    env->GetFloatArrayRegion(encoded, 0, n_encoded, encoder_output.data());
    if (whisper_mosaic_set_encoder_output(
            s->ctx.get(), encoder_output.data(), encoder_output.size()) != 0) {
        fail(env, "Could not inject Tensor G5 encoder output");
        return nullptr;
    }

    s->cancelled = false;
    s->phase = 1;
    s->deadline = Clock::now() + std::chrono::seconds(budget_seconds);

    auto p = whisper_full_default_params(
            accurate ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = 2;
    p.audio_ctx = 0;  // Tensor artifact is compiled for the full 1500-frame audio context.
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
    p.temperature_inc = 0.0f;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.encoder_begin_callback = encoder_begin;
    p.encoder_begin_callback_user_data = s;
    p.logits_filter_callback = decoding;
    p.logits_filter_callback_user_data = s;
    p.abort_callback = should_abort;
    p.abort_callback_user_data = s;

    // PCM has already been converted to the state-owned mel spectrogram.
    // n_samples=0 deliberately preserves that mel and only runs the external
    // encoder seam + existing cross-attention/decoder path.
    if (whisper_full(s->ctx.get(), p, nullptr, 0) != 0 || should_abort(s)) {
        fail(env, s->cancelled
                ? "Cancelled"
                : "Hybrid decode failed or exceeded the selected inference budget");
        return nullptr;
    }

    std::string text;
    for (int i = 0; i < whisper_full_n_segments(s->ctx.get()); ++i) {
        text += whisper_full_get_segment_text(s->ctx.get(), i);
    }
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

extern "C" JNIEXPORT jboolean JNICALL
Java_life_mosaic_voice_WhisperNative_gpuBuild(JNIEnv *, jobject) {
#ifdef MOSAIC_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
