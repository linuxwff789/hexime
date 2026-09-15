// SPDX-License-Identifier: GPL-3.0-or-later
//
// librime C API 的 benchmark JNI 封装。
// 使用现代 RimeApi (rime_get_api()) 而非 deprecated 全局函数。

#include <jni.h>

#include <android/log.h>
#include <rime_api.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "HeximeBenchJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// librime-lua 以对象形式链接进本 .so。定义 HEXIME_FORCE_LINK_PLUGINS 时
// 强制引用 lua 模块，确保其被加载。
// ---------------------------------------------------------------------------
#ifdef HEXIME_FORCE_LINK_PLUGINS
// 注意：rime_require_module_lua 是 C++ 链接（定义时无 extern "C"），
// 因此这里不能包在 extern "C" 里，否则会去找未修饰符号而链接失败。
void rime_require_module_lua();

static void force_link_modules() {
  rime_require_module_lua();
}
#else
static void force_link_modules() {}
#endif

static RimeApi* g_rime = nullptr;
static bool g_initialized = false;

static RimeApi* api() {
  if (g_rime == nullptr) {
    g_rime = rime_get_api();
  }
  return g_rime;
}

static void to_string(JNIEnv* env, jstring s, std::string* out) {
  out->clear();
  if (s == nullptr) return;
  const char* c = env->GetStringUTFChars(s, nullptr);
  if (c != nullptr) {
    out->assign(c);
    env->ReleaseStringUTFChars(s, c);
  }
}

static std::vector<std::string> context_candidates(RimeApi* rime,
                                                   RimeSessionId sid) {
  std::vector<std::string> out;
  RIME_STRUCT(RimeContext, ctx);
  if (!rime->get_context(sid, &ctx)) return out;
  for (int i = 0; i < ctx.menu.num_candidates; ++i) {
    const RimeCandidate& c = ctx.menu.candidates[i];
    std::string item = c.text ? c.text : "";
    if (c.comment && c.comment[0] != '\0') {
      item += '\t';
      item += c.comment;
    }
    out.push_back(std::move(item));
  }
  rime->free_context(&ctx);
  return out;
}

static jobjectArray to_string_array(JNIEnv* env,
                                    const std::vector<std::string>& items) {
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray arr =
      env->NewObjectArray(static_cast<jsize>(items.size()), string_class, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(items.size()); ++i) {
    jstring s = env->NewStringUTF(items[i].c_str());
    env->SetObjectArrayElement(arr, i, s);
    env->DeleteLocalRef(s);
  }
  return arr;
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hexime_bench_RimeBench_nativeInit(JNIEnv* env, jclass, jstring j_shared,
                                           jstring j_user, jstring j_log,
                                           jstring j_dist_name,
                                           jstring j_dist_version) {
  RimeApi* rime = api();
  if (rime == nullptr) {
    LOGE("rime_get_api() returned null");
    return JNI_FALSE;
  }
  force_link_modules();

  std::string shared, user, log_dir, dist_name, dist_version;
  to_string(env, j_shared, &shared);
  to_string(env, j_user, &user);
  to_string(env, j_log, &log_dir);
  to_string(env, j_dist_name, &dist_name);
  to_string(env, j_dist_version, &dist_version);

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.log_dir = log_dir.c_str();  // 空串 => 仅输出到 stderr/logcat
  traits.distribution_name = dist_name.c_str();
  traits.distribution_code_name = "hexime";
  traits.distribution_version = dist_version.c_str();
  traits.app_name = "rime.hexime_bench";

  rime->setup(&traits);
  rime->initialize(&traits);
  g_initialized = true;
  LOGI("initialized: shared=%s user=%s", shared.c_str(), user.c_str());
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hexime_bench_RimeBench_nativeDeploy(JNIEnv*, jclass, jboolean full_check) {
  RimeApi* rime = api();
  if (rime == nullptr) return JNI_FALSE;
  Bool started = rime->start_maintenance(full_check ? True : False);
  if (started) {
    rime->join_maintenance_thread();
  }
  return started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hexime_bench_RimeBench_nativeCreateSession(JNIEnv* env, jclass,
                                                    jstring j_schema) {
  RimeApi* rime = api();
  if (rime == nullptr) return 0;
  RimeSessionId sid = rime->create_session();
  if (sid != 0 && j_schema != nullptr) {
    std::string schema;
    to_string(env, j_schema, &schema);
    if (!schema.empty()) rime->select_schema(sid, schema.c_str());
  }
  return static_cast<jlong>(sid);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hexime_bench_RimeBench_nativeDestroySession(JNIEnv*, jclass, jlong sid) {
  RimeApi* rime = api();
  if (rime != nullptr) rime->destroy_session(static_cast<RimeSessionId>(sid));
}

extern "C" JNIEXPORT void JNICALL
Java_com_hexime_bench_RimeBench_nativeClearComposition(JNIEnv*, jclass, jlong sid) {
  RimeApi* rime = api();
  if (rime != nullptr) rime->clear_composition(static_cast<RimeSessionId>(sid));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hexime_bench_RimeBench_nativeSimulateKeys(JNIEnv* env, jclass, jlong sid,
                                                   jstring j_keys) {
  RimeApi* rime = api();
  if (rime == nullptr) return JNI_FALSE;
  std::string keys;
  to_string(env, j_keys, &keys);
  Bool ok = rime->simulate_key_sequence(static_cast<RimeSessionId>(sid), keys.c_str());
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_hexime_bench_RimeBench_nativeGetCandidates(JNIEnv* env, jclass, jlong sid) {
  RimeApi* rime = api();
  if (rime == nullptr) return to_string_array(env, {});
  return to_string_array(
      env, context_candidates(rime, static_cast<RimeSessionId>(sid)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hexime_bench_RimeBench_nativeGetComposition(JNIEnv* env, jclass, jlong sid) {
  RimeApi* rime = api();
  if (rime == nullptr) return env->NewStringUTF("");
  RIME_STRUCT(RimeContext, ctx);
  const char* preedit = "";
  if (rime->get_context(static_cast<RimeSessionId>(sid), &ctx)) {
    if (ctx.composition.preedit != nullptr) preedit = ctx.composition.preedit;
    jstring result = env->NewStringUTF(preedit);
    rime->free_context(&ctx);
    return result;
  }
  return env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hexime_bench_RimeBench_nativeGetCommit(JNIEnv* env, jclass, jlong sid) {
  RimeApi* rime = api();
  if (rime == nullptr) return env->NewStringUTF("");
  RIME_STRUCT(RimeCommit, commit);
  if (rime->get_commit(static_cast<RimeSessionId>(sid), &commit)) {
    jstring result = env->NewStringUTF(commit.text ? commit.text : "");
    rime->free_commit(&commit);
    return result;
  }
  return env->NewStringUTF("");
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_hexime_bench_RimeBench_nativeBenchLatency(JNIEnv* env, jclass, jlong sid,
                                                   jstring j_keys,
                                                   jint iterations) {
  RimeApi* rime = api();
  jdoubleArray result = env->NewDoubleArray(5);
  if (rime == nullptr) return result;

  std::string keys;
  to_string(env, j_keys, &keys);
  RimeSessionId session = static_cast<RimeSessionId>(sid);

  auto round_trip = [&](char key) {
    char buf[2] = {key, '\0'};
    auto t0 = std::chrono::steady_clock::now();
    rime->simulate_key_sequence(session, buf);
    RIME_STRUCT(RimeContext, ctx);
    if (rime->get_context(session, &ctx)) rime->free_context(&ctx);
    auto t1 = std::chrono::steady_clock::now();
    return std::chrono::duration<double, std::milli>(t1 - t0).count();
  };

  // 预热
  for (int w = 0; w < 3; ++w) {
    rime->clear_composition(session);
    for (char c : keys) round_trip(c);
  }
  rime->clear_composition(session);

  std::vector<double> samples;
  samples.reserve(keys.size() * static_cast<size_t>(iterations));
  for (int it = 0; it < iterations; ++it) {
    rime->clear_composition(session);
    for (char c : keys) samples.push_back(round_trip(c));
  }

  std::sort(samples.begin(), samples.end());
  auto percentile = [&](double p) -> double {
    if (samples.empty()) return 0.0;
    size_t idx = static_cast<size_t>(p * (samples.size() - 1));
    return samples[idx];
  };
  double sum = 0.0;
  for (double s : samples) sum += s;
  double avg = samples.empty() ? 0.0 : sum / samples.size();

  jdouble values[5] = {avg, percentile(0.50), percentile(0.95), percentile(0.99),
                       samples.empty() ? 0.0 : samples.back()};
  env->SetDoubleArrayRegion(result, 0, 5, values);
  return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hexime_bench_RimeBench_nativeGetVersion(JNIEnv* env, jclass) {
  RimeApi* rime = api();
  const char* version = (rime != nullptr) ? rime->get_version() : nullptr;
  return env->NewStringUTF(version ? version : "unknown");
}

extern "C" JNIEXPORT void JNICALL
Java_com_hexime_bench_RimeBench_nativeFinalize(JNIEnv*, jclass) {
  RimeApi* rime = api();
  if (rime != nullptr && g_initialized) {
    rime->cleanup_all_sessions();
    rime->finalize();
    g_initialized = false;
    LOGI("finalized");
  }
}
