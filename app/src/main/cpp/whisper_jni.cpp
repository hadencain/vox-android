#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_hadencain_vox_asr_WhisperBridge_init(JNIEnv* env, jobject, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hadencain_vox_asr_WhisperBridge_transcribe(
        JNIEnv* env, jobject, jlong handle, jfloatArray samples, jstring biasPrompt) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(samples);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.n_threads = 6;
    params.no_timestamps = true;
    std::string prompt;
    if (biasPrompt) {
        const char* p = env->GetStringUTFChars(biasPrompt, nullptr);
        prompt = p;
        env->ReleaseStringUTFChars(biasPrompt, p);
        if (!prompt.empty()) params.initial_prompt = prompt.c_str();
    }
    std::string out;
    if (whisper_full(ctx, params, data, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_hadencain_vox_asr_WhisperBridge_release(JNIEnv*, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context*>(handle));
}
