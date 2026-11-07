// 六花声库 ↔ VOICEVOX CORE 官方 C API（libvoicevox_core.so）的薄封装。
//
// 为什么不用官方 Java API：Java API 的原生库会按“字段名”反射读取 Java 对象
// （如 Synthesizer.Builder.cpuNumThreads），打包混淆或版本稍有不一致就会 NoSuchFieldError。
// 这里改为 dlopen + dlsym 直接调用 C API：不需要头文件、不读任何 Java 字段，
// 函数签名按官方 voicevox_core.h（0.16 / 0.17）声明。
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

#define TAG "RikkaVV"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {
typedef int32_t VvResult;  // VOICEVOX_RESULT_OK = 0
struct VvLoadOrtOpts { const char *filename; };
struct VvInitOpts { int32_t acceleration_mode; uint16_t cpu_num_threads; };
struct VvLoadModelOpts { int32_t on_existing; };
struct VvSynthOpts { bool enable_interrogative_upspeak; };
}

typedef VvLoadOrtOpts (*fn_make_ort_opts)();
typedef VvResult (*fn_ort_load_once)(VvLoadOrtOpts, const void **);
typedef VvResult (*fn_ojt_new)(const char *, void **);
typedef VvInitOpts (*fn_make_init_opts)();
typedef VvResult (*fn_synth_new)(const void *, const void *, VvInitOpts, void **);
typedef VvResult (*fn_vvm_open)(const char *, void **);
typedef void (*fn_vvm_id)(const void *, uint8_t (*)[16]);
typedef char *(*fn_vvm_metas)(const void *);
typedef void (*fn_vvm_delete)(void *);
typedef VvLoadModelOpts (*fn_make_load_opts)();
typedef VvResult (*fn_load_model)(const void *, const void *, VvLoadModelOpts);
typedef VvResult (*fn_unload_model)(const void *, const uint8_t (*)[16]);
typedef VvResult (*fn_create_query)(const void *, const char *, uint32_t, char **);
typedef VvSynthOpts (*fn_make_synth_opts)();
typedef VvResult (*fn_synthesis)(const void *, const char *, uint32_t, VvSynthOpts, uintptr_t *, uint8_t **);
typedef void (*fn_str_free)(char *);
typedef void (*fn_bytes_free)(uint8_t *);
typedef const char *(*fn_err_msg)(VvResult);
typedef const char *(*fn_version)();

static std::mutex g_mu;
static void *g_lib = nullptr;
static void *g_synth = nullptr;
static struct {
    fn_make_ort_opts make_ort_opts; fn_ort_load_once ort_load_once; fn_ojt_new ojt_new;
    fn_make_init_opts make_init_opts; fn_synth_new synth_new;
    fn_vvm_open vvm_open; fn_vvm_id vvm_id; fn_vvm_metas vvm_metas; fn_vvm_delete vvm_delete;
    fn_make_load_opts make_load_opts; fn_load_model load_model; fn_unload_model unload_model;
    fn_create_query create_query; fn_make_synth_opts make_synth_opts; fn_synthesis synthesis;
    fn_str_free str_free; fn_bytes_free bytes_free; fn_err_msg err_msg; fn_version version;
} F;

static void throwRt(JNIEnv *env, const std::string &msg) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), msg.c_str());
}
static std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
static std::string errText(VvResult r) {
    std::string m = (F.err_msg ? F.err_msg(r) : nullptr) ?: "";
    return "VOICEVOX 错误 " + std::to_string(r) + (m.empty() ? "" : "：" + m);
}
static void freeStr(char *s) { if (s && F.str_free) F.str_free(s); }
static void freeBytes(uint8_t *b) { if (b && F.bytes_free) F.bytes_free(b); }

template <typename T> static bool sym(T &out, const char *name, std::string &missing) {
    out = reinterpret_cast<T>(dlsym(g_lib, name));
    if (!out) { if (!missing.empty()) missing += ", "; missing += name; }
    return out != nullptr;
}
template <typename T> static void symOpt(T &out, const char *a, const char *b = nullptr) {
    out = reinterpret_cast<T>(dlsym(g_lib, a));
    if (!out && b) out = reinterpret_cast<T>(dlsym(g_lib, b));
}

static std::string hex(const uint8_t id[16]) {
    char buf[33];
    for (int i = 0; i < 16; i++) snprintf(buf + i * 2, 3, "%02x", id[i]);
    return std::string(buf, 32);
}
static bool unhex(const std::string &s, uint8_t out[16]) {
    if (s.size() != 32) return false;
    for (int i = 0; i < 16; i++) out[i] = (uint8_t) strtoul(s.substr(i * 2, 2).c_str(), nullptr, 16);
    return true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rikka_voice_engines_VvNative_init(JNIEnv *env, jobject, jstring jcore, jstring jort, jstring jdict, jint threads) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_synth) return env->NewStringUTF("");
    std::string core = jstr(env, jcore), ort = jstr(env, jort), dict = jstr(env, jdict);
    if (!g_lib) {
        g_lib = dlopen(core.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!g_lib) return env->NewStringUTF((std::string("无法载入 libvoicevox_core.so：") + (dlerror() ?: "")).c_str());
    }
    std::string missing;
    sym(F.ort_load_once, "voicevox_onnxruntime_load_once", missing);
    sym(F.ojt_new, "voicevox_open_jtalk_rc_new", missing);
    sym(F.make_init_opts, "voicevox_make_default_initialize_options", missing);
    sym(F.synth_new, "voicevox_synthesizer_new", missing);
    sym(F.vvm_open, "voicevox_voice_model_file_open", missing);
    sym(F.vvm_id, "voicevox_voice_model_file_id", missing);
    sym(F.vvm_metas, "voicevox_voice_model_file_create_metas_json", missing);
    sym(F.vvm_delete, "voicevox_voice_model_file_delete", missing);
    sym(F.load_model, "voicevox_synthesizer_load_voice_model", missing);
    sym(F.unload_model, "voicevox_synthesizer_unload_voice_model", missing);
    sym(F.create_query, "voicevox_synthesizer_create_audio_query", missing);
    sym(F.make_synth_opts, "voicevox_make_default_synthesis_options", missing);
    sym(F.synthesis, "voicevox_synthesizer_synthesis", missing);
    symOpt(F.make_ort_opts, "voicevox_make_default_load_onnxruntime_options");
    symOpt(F.make_load_opts, "voicevox_make_default_load_voice_model_options");   // 0.17+，0.16 没有
    symOpt(F.str_free, "voicevox_string_free", "voicevox_json_free");
    symOpt(F.bytes_free, "voicevox_bytes_free", "voicevox_wav_free");
    symOpt(F.err_msg, "voicevox_error_result_to_message");
    symOpt(F.version, "voicevox_get_version");
    if (!missing.empty())
        return env->NewStringUTF(("libvoicevox_core.so 缺少函数（需要 0.16 及以上、load-onnxruntime 版本的 C API）：" + missing).c_str());

    VvLoadOrtOpts oo = F.make_ort_opts ? F.make_ort_opts() : VvLoadOrtOpts{nullptr};
    oo.filename = ort.c_str();
    const void *ortH = nullptr;
    VvResult r = F.ort_load_once(oo, &ortH);
    if (r != 0) return env->NewStringUTF(("载入 ONNX Runtime 失败（" + ort + "）：" + errText(r)).c_str());

    void *ojt = nullptr;
    r = F.ojt_new(dict.c_str(), &ojt);
    if (r != 0) return env->NewStringUTF(("载入 OpenJTalk 词典失败：" + errText(r)).c_str());

    VvInitOpts io = F.make_init_opts();
    io.acceleration_mode = 1;  // CPU：手机上不走 GPU
    io.cpu_num_threads = (uint16_t) (threads > 0 ? threads : 0);
    r = F.synth_new(ortH, ojt, io, &g_synth);
    if (r != 0) { g_synth = nullptr; return env->NewStringUTF(("创建合成器失败：" + errText(r)).c_str()); }
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rikka_voice_engines_VvNative_version(JNIEnv *env, jobject) {
    return env->NewStringUTF(F.version ? (F.version() ?: "") : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rikka_voice_engines_VvNative_metas(JNIEnv *env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_lib) { throwRt(env, "VOICEVOX 尚未初始化"); return nullptr; }
    void *m = nullptr;
    VvResult r = F.vvm_open(jstr(env, jpath).c_str(), &m);
    if (r != 0) { throwRt(env, "打开声音包失败：" + errText(r)); return nullptr; }
    char *js = F.vvm_metas(m);
    jstring out = env->NewStringUTF(js ? js : "[]");
    freeStr(js);
    F.vvm_delete(m);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rikka_voice_engines_VvNative_load(JNIEnv *env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_synth) { throwRt(env, "VOICEVOX 尚未初始化"); return nullptr; }
    void *m = nullptr;
    VvResult r = F.vvm_open(jstr(env, jpath).c_str(), &m);
    if (r != 0) { throwRt(env, "打开声音包失败：" + errText(r)); return nullptr; }
    uint8_t id[16] = {0};
    F.vvm_id(m, &id);
    // 0.16 的 load_voice_model 只有 2 个参数；arm64 调用约定下多传的第 3 个参数会被忽略。
    // on_existing = 2（SKIP）：已载入时不报错
    VvLoadModelOpts lo = F.make_load_opts ? F.make_load_opts() : VvLoadModelOpts{0};
    lo.on_existing = F.make_load_opts ? 2 : 0;
    r = F.load_model(g_synth, m, lo);
    F.vvm_delete(m);
    if (r != 0 && r != 18 /* MODEL_ALREADY_LOADED */ && r != 26 /* STYLE_ALREADY_LOADED */) {
        throwRt(env, "载入声音包失败：" + errText(r)); return nullptr;
    }
    return env->NewStringUTF(hex(id).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rikka_voice_engines_VvNative_unload(JNIEnv *env, jobject, jstring jid) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_synth) return;
    uint8_t id[16];
    if (unhex(jstr(env, jid), id)) F.unload_model(g_synth, &id);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rikka_voice_engines_VvNative_audioQuery(JNIEnv *env, jobject, jstring jtext, jint style) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_synth) { throwRt(env, "VOICEVOX 尚未初始化"); return nullptr; }
    char *q = nullptr;
    VvResult r = F.create_query(g_synth, jstr(env, jtext).c_str(), (uint32_t) style, &q);
    if (r != 0) { throwRt(env, "分析文字失败：" + errText(r)); return nullptr; }
    jstring out = env->NewStringUTF(q);
    freeStr(q);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_rikka_voice_engines_VvNative_synthesis(JNIEnv *env, jobject, jstring jquery, jint style) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_synth) { throwRt(env, "VOICEVOX 尚未初始化"); return nullptr; }
    VvSynthOpts so = F.make_synth_opts();
    uintptr_t len = 0; uint8_t *wav = nullptr;
    VvResult r = F.synthesis(g_synth, jstr(env, jquery).c_str(), (uint32_t) style, so, &len, &wav);
    if (r != 0) { throwRt(env, "合成失败：" + errText(r)); return nullptr; }
    jbyteArray arr = env->NewByteArray((jsize) len);
    env->SetByteArrayRegion(arr, 0, (jsize) len, reinterpret_cast<const jbyte *>(wav));
    freeBytes(wav);
    return arr;
}
