// SPDX-License-Identifier: GPL-3.0-or-later
//
// hexime 输入法 · librime JNI 封装
// 对应 Kotlin 侧 com.hexime.ime.engine.RimeNative。

#include <jni.h>

#include <android/log.h>
#include <rime_api.h>

#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "HeximeRime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// rime_require_module_* 是 C++ 链接（定义时无 extern "C"），不能包 extern "C"。
#ifdef HEXIME_FORCE_LINK_PLUGINS
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
  if (g_rime == nullptr) g_rime = rime_get_api();
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

static jobjectArray to_string_array(JNIEnv* env, const std::vector<std::string>& items) {
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

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hexime_ime_engine_RimeNative_init(JNIEnv* env, jobject, jstring j_shared,
                                           jstring j_user, jstring j_log,
                                           jstring j_dist_name, jstring j_dist_version) {
  RimeApi* rime = api();
  if (rime == nullptr) return JNI_FALSE;
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
  traits.log_dir = log_dir.c_str();
  traits.distribution_name = dist_name.c_str();
  traits.distribution_code_name = "hexime";
  traits.distribution_version = dist_version.c_str();
  traits.app_name = "rime.hexime";

  rime->setup(&traits);
  rime->initialize(&traits);
  g_initialized = true;
  LOGI("initialized: shared=%s user=%s", shared.c_str(), user.c_str());
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_hexime_ime_engine_RimeNative_deploy(JNIEnv*, jobject, jboolean full_check) {
  RimeApi* rime = api();
  if (rime == nullptr) return JNI_FALSE;
  Bool started = rime->start_maintenance(full_check ? True : False);
  if (started) rime->join_maintenance_thread();
  return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_hexime_ime_engine_RimeNative_createSession(JNIEnv*, jobject) {
  RimeApi* rime = api();
  if (rime == nullptr) return 0;
  return static_cast<jlong>(rime->create_session());
}

JNIEXPORT void JNICALL
Java_com_hexime_ime_engine_RimeNative_destroySession(JNIEnv*, jobject, jlong sid) {
  RimeApi* rime = api();
  if (rime != nullptr) rime->destroy_session(static_cast<RimeSessionId>(sid));
}

JNIEXPORT jboolean JNICALL
Java_com_hexime_ime_engine_RimeNative_selectSchema(JNIEnv* env, jobject, jlong sid,
                                                   jstring j_schema) {
  RimeApi* rime = api();
  if (rime == nullptr) return JNI_FALSE;
  std::string schema;
  to_string(env, j_schema, &schema);
  if (schema.empty()) return JNI_FALSE;
  return rime->select_schema(static_cast<RimeSessionId>(sid), schema.c_str()) ? JNI_TRUE
                                                                              : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_hexime_ime_engine_RimeNative_currentSchema(JNIEnv* env, jobject, jlong sid) {
  RimeApi* rime = api();
  if (rime == nullptr) return env->NewStringUTF("");
  char buffer[256] = {0};
  if (rime->get_current_schema(static_cast<RimeSessionId>(sid), buffer, sizeof(buffer))) {
    return env->NewStringUTF(buffer);
  }
  return env->NewStringUTF("");
}

JNIEXPORT jboolean JNICALL
Java_com_hexime_ime_engine_RimeNative_processKey(JNIEnv*, jobject, jlong sid,
                                                 jint key_sym, jint mask) {
  RimeApi* rime = api();
  if (rime == nullptr) return JNI_FALSE;
  return rime->process_key(static_cast<RimeSessionId>(sid), key_sym, mask) ? JNI_TRUE
                                                                           : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hexime_ime_engine_RimeNative_selectCandidate(JNIEnv*, jobject, jlong sid,
                                                      jint index) {
  RimeApi* rime = api();
  if (rime == nullptr || index < 0) return JNI_FALSE;
  return rime->select_candidate(static_cast<RimeSessionId>(sid),
                                static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_hexime_ime_engine_RimeNative_composition(JNIEnv* env, jobject, jlong sid) {
  RimeApi* rime = api();
  if (rime == nullptr) return env->NewStringUTF("");
  RIME_STRUCT(RimeContext, ctx);
  if (!rime->get_context(static_cast<RimeSessionId>(sid), &ctx)) {
    return env->NewStringUTF("");
  }
  jstring result =
      env->NewStringUTF(ctx.composition.preedit ? ctx.composition.preedit : "");
  rime->free_context(&ctx);
  return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_hexime_ime_engine_RimeNative_candidates(JNIEnv* env, jobject, jlong sid) {
  RimeApi* rime = api();
  std::vector<std::string> out;
  if (rime == nullptr) return to_string_array(env, out);

  RIME_STRUCT(RimeContext, ctx);
  if (rime->get_context(static_cast<RimeSessionId>(sid), &ctx)) {
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
  }
  return to_string_array(env, out);
}

JNIEXPORT jintArray JNICALL
Java_com_hexime_ime_engine_RimeNative_pageInfo(JNIEnv* env, jobject, jlong sid) {
  RimeApi* rime = api();
  jint values[2] = {0, 1};
  if (rime != nullptr) {
    RIME_STRUCT(RimeContext, ctx);
    if (rime->get_context(static_cast<RimeSessionId>(sid), &ctx)) {
      values[0] = ctx.menu.page_no;
      values[1] = ctx.menu.is_last_page ? 1 : 0;
      rime->free_context(&ctx);
    }
  }
  jintArray result = env->NewIntArray(2);
  env->SetIntArrayRegion(result, 0, 2, values);
  return result;
}

JNIEXPORT jstring JNICALL
Java_com_hexime_ime_engine_RimeNative_commit(JNIEnv* env, jobject, jlong sid) {
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

JNIEXPORT void JNICALL
Java_com_hexime_ime_engine_RimeNative_clearComposition(JNIEnv*, jobject, jlong sid) {
  RimeApi* rime = api();
  if (rime != nullptr) rime->clear_composition(static_cast<RimeSessionId>(sid));
}

JNIEXPORT void JNICALL
Java_com_hexime_ime_engine_RimeNative_setOption(JNIEnv* env, jobject, jlong sid,
                                                jstring j_name, jboolean value) {
  RimeApi* rime = api();
  if (rime == nullptr) return;
  std::string name;
  to_string(env, j_name, &name);
  rime->set_option(static_cast<RimeSessionId>(sid), name.c_str(), value ? True : False);
}

JNIEXPORT jstring JNICALL
Java_com_hexime_ime_engine_RimeNative_version(JNIEnv* env, jobject) {
  RimeApi* rime = api();
  const char* version = (rime != nullptr) ? rime->get_version() : nullptr;
  return env->NewStringUTF(version ? version : "unknown");
}

JNIEXPORT void JNICALL
Java_com_hexime_ime_engine_RimeNative_shutdown(JNIEnv*, jobject) {
  RimeApi* rime = api();
  if (rime != nullptr && g_initialized) {
    rime->cleanup_all_sessions();
    rime->finalize();
    g_initialized = false;
    LOGI("finalized");
  }
}

}  // extern "C"
