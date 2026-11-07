// 六花声库 ↔ piper-plus C API 的薄封装。
//
// ⚠ 这个文件按 piper 系 C API 的常见形态编写（create / synthesize_start / synthesize_next / free）。
//   piper-plus 的 piper_plus.h 函数名、结构体字段如有不同，只需要改下面【对齐区】里的宏和结构体用法，
//   Kotlin 侧（PiperPlusEngine.kt）不用动。改好后在 gradle.properties 打开 rikka.engine.piperplus=true。
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "piper_plus.h"

#define LOG_TAG "RikkaPiper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ───────────────【对齐区：按 piper_plus.h 修改】───────────────
#define PP_SYNTH                 piper_synthesizer
#define PP_CREATE(model, cfg, dict)  piper_create((model), (cfg), (dict))
#define PP_FREE(s)               piper_free((s))
#define PP_OPTIONS               piper_synthesize_options
#define PP_DEFAULT_OPTIONS(s)    piper_default_synthesize_options((s))
#define PP_START(s, text, opt)   piper_synthesize_start((s), (text), (opt))
#define PP_CHUNK                 piper_audio_chunk
#define PP_NEXT(s, chunk)        piper_synthesize_next((s), (chunk))
#define PP_DONE                  PIPER_DONE
#define PP_OK                    PIPER_OK
// ─────────────────────────────────────────────────────────────

struct Ctx { PP_SYNTH* synth = nullptr; int sampleRate = 22050; };

static std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static void throwRt(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, msg);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rikka_voice_engines_PiperPlusNative_create(JNIEnv* env, jobject, jstring model, jstring config, jstring dict, jint) {
    auto* ctx = new Ctx();
    ctx->synth = PP_CREATE(str(env, model).c_str(), str(env, config).c_str(), str(env, dict).c_str());
    if (!ctx->synth) { delete ctx; LOGE("create failed"); return 0; }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rikka_voice_engines_PiperPlusNative_synthesize(JNIEnv* env, jobject, jlong h, jstring text, jint speaker, jfloat lengthScale) {
    auto* ctx = reinterpret_cast<Ctx*>(h);
    if (!ctx || !ctx->synth) { throwRt(env, "piper-plus 未初始化"); return nullptr; }
    PP_OPTIONS opt = PP_DEFAULT_OPTIONS(ctx->synth);
    opt.speaker_id = speaker;
    opt.length_scale = lengthScale;
    std::string t = str(env, text);
    if (PP_START(ctx->synth, t.c_str(), &opt) != PP_OK) { throwRt(env, "piper-plus 合成启动失败"); return nullptr; }
    std::vector<float> all;
    PP_CHUNK chunk;
    while (true) {
        int rc = PP_NEXT(ctx->synth, &chunk);
        if (rc == PP_DONE) break;
        if (rc != PP_OK) { throwRt(env, "piper-plus 合成失败"); return nullptr; }
        all.insert(all.end(), chunk.samples, chunk.samples + chunk.num_samples);
        ctx->sampleRate = chunk.sample_rate;
        if (chunk.is_last) break;
    }
    jfloatArray arr = env->NewFloatArray((jsize) all.size());
    env->SetFloatArrayRegion(arr, 0, (jsize) all.size(), all.data());
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rikka_voice_engines_PiperPlusNative_sampleRate(JNIEnv*, jobject, jlong h) {
    auto* ctx = reinterpret_cast<Ctx*>(h);
    return ctx ? ctx->sampleRate : 22050;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rikka_voice_engines_PiperPlusNative_destroy(JNIEnv*, jobject, jlong h) {
    auto* ctx = reinterpret_cast<Ctx*>(h);
    if (!ctx) return;
    if (ctx->synth) PP_FREE(ctx->synth);
    delete ctx;
}
