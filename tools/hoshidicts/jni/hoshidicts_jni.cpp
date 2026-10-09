// JNI for kumapie (mobile/.../lang/Hoshidicts.kt) over hoshidicts (bee-san/hoshidicts, GPL-3.0): import Yomitan
// dictionaries, query a word, scan text Yomitan-style. Results go to Kotlin as JSON strings.
#include <jni.h>

#include <memory>
#include <string>

#include "hoshidicts/deinflector.hpp"
#include "hoshidicts/importer.hpp"
#include "hoshidicts/lookup.hpp"
#include "hoshidicts/query.hpp"

namespace {

struct Handle {
  DictionaryQuery query;
  Deinflector deinflector;
  Lookup lookup{query, deinflector};
};

std::string str(JNIEnv* env, jstring s) {
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out(c);
  env->ReleaseStringUTFChars(s, c);
  return out;
}

// Java's modified UTF-8 can't carry 4-byte characters; go through UTF-16 bytes via String(byte[], "UTF-8").
jstring jstr(JNIEnv* env, const std::string& s) {
  jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
  env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
  jclass cls = env->FindClass("java/lang/String");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
  jstring utf8 = env->NewStringUTF("UTF-8");
  auto out = static_cast<jstring>(env->NewObject(cls, ctor, bytes, utf8));
  env->DeleteLocalRef(bytes);
  env->DeleteLocalRef(utf8);
  env->DeleteLocalRef(cls);
  return out;
}

void esc(std::string& o, const std::string& s) {
  o += '"';
  for (unsigned char c : s) {
    switch (c) {
      case '"': o += "\\\""; break;
      case '\\': o += "\\\\"; break;
      case '\n': o += "\\n"; break;
      case '\r': o += "\\r"; break;
      case '\t': o += "\\t"; break;
      default:
        if (c < 0x20) {
          char b[8];
          snprintf(b, sizeof b, "\\u%04x", c);
          o += b;
        } else {
          o += static_cast<char>(c);
        }
    }
  }
  o += '"';
}

void opt(std::string& o, const char* key, const std::optional<std::string>& v) {
  o += ",\"";
  o += key;
  o += "\":";
  if (v) esc(o, *v); else o += "null";
}

void term_json(std::string& o, const TermResult& t) {
  o += "{\"expression\":";
  esc(o, t.expression);
  o += ",\"reading\":";
  esc(o, t.reading);
  o += ",\"glossaries\":[";
  for (size_t i = 0; i < t.glossaries.size(); i++) {
    const auto& g = t.glossaries[i];
    if (i) o += ',';
    o += "{\"dict\":";
    esc(o, g.dict_name);
    o += ",\"glossary\":";
    esc(o, g.glossary);  // a JSON array as text (Yomitan glossary: strings and structured content)
    o += ",\"defTags\":";
    esc(o, g.definition_tags);
    o += ",\"termTags\":";
    esc(o, g.term_tags);
    o += '}';
  }
  o += "],\"frequencies\":[";
  for (size_t i = 0; i < t.frequencies.size(); i++) {
    if (i) o += ',';
    o += "{\"dict\":";
    esc(o, t.frequencies[i].dict_name);
    o += ",\"values\":[";
    for (size_t j = 0; j < t.frequencies[i].frequencies.size(); j++) {
      const auto& f = t.frequencies[i].frequencies[j];
      if (j) o += ',';
      o += "{\"value\":" + std::to_string(f.value) + ",\"display\":";
      esc(o, f.display_value);
      o += '}';
    }
    o += "]}";
  }
  o += "],\"pitches\":[";
  for (size_t i = 0; i < t.pitches.size(); i++) {
    if (i) o += ',';
    o += "{\"dict\":";
    esc(o, t.pitches[i].dict_name);
    o += ",\"positions\":[";
    for (size_t j = 0; j < t.pitches[i].pitches.size(); j++) {
      if (j) o += ',';
      o += std::to_string(t.pitches[i].pitches[j].position);
    }
    o += "],\"ipa\":[";
    for (size_t j = 0; j < t.pitches[i].transcriptions.size(); j++) {
      if (j) o += ',';
      esc(o, t.pitches[i].transcriptions[j]);
    }
    o += "]}";
  }
  o += "]}";
}

Handle* h(jlong p) { return reinterpret_cast<Handle*>(p); }

}  // namespace

#define FN(name) Java_io_github_pedrubik2000_kumapie_lang_Hoshidicts_##name

extern "C" {

JNIEXPORT jstring JNICALL FN(nativeImport)(JNIEnv* env, jclass, jstring source, jstring outDir) {
  std::string o;
  try {
    ImportResult r = dictionary_importer::import(str(env, source), str(env, outDir), true);
    const Summary& s = r.summary;
    o = "{\"ok\":";
    o += r.success ? "true" : "false";
    o += ",\"title\":";
    esc(o, r.title);
    o += ",\"folder\":";
    esc(o, dictionary_importer::folder_name(r.title));
    o += ",\"error\":";
    esc(o, r.error);
    o += ",\"revision\":";
    esc(o, s.revision);
    o += ",\"terms\":" + std::to_string(s.counts.terms.total);
    size_t freq = 0, pitch = 0;
    for (const auto& [mode, n] : s.counts.termMeta) {
      if (mode == "freq") freq += n;
      if (mode == "pitch" || mode == "ipa") pitch += n;  // IPA dictionaries load as the pitch kind
    }
    o += ",\"freq\":" + std::to_string(freq) + ",\"pitch\":" + std::to_string(pitch);
    o += ",\"kanji\":" + std::to_string(s.counts.kanji.total);
    o += ",\"isUpdatable\":";
    o += s.isUpdatable.value_or(false) ? "true" : "false";
    opt(o, "indexUrl", s.indexUrl);
    opt(o, "downloadUrl", s.downloadUrl);
    opt(o, "sourceLanguage", s.sourceLanguage);
    opt(o, "targetLanguage", s.targetLanguage);
    o += '}';
  } catch (const std::exception& e) {
    o = "{\"ok\":false,\"error\":";
    esc(o, e.what());
    o += '}';
  }
  return jstr(env, o);
}

JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(new Handle()); }

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv*, jclass, jlong p) { delete h(p); }

// kind: 0 term, 1 frequency, 2 pitch, 3 kanji
JNIEXPORT jboolean JNICALL FN(nativeAdd)(JNIEnv* env, jclass, jlong p, jstring path, jint kind) {
  try {
    std::string dir = str(env, path);
    auto& q = h(p)->query;
    switch (kind) {
      case 0: return q.add_term_dict(dir);
      case 1: return q.add_freq_dict(dir);
      case 2: return q.add_pitch_dict(dir);
      default: return q.add_kanji_dict(dir);
    }
  } catch (...) {
    return false;
  }
}

JNIEXPORT jstring JNICALL FN(nativeQuery)(JNIEnv* env, jclass, jlong p, jstring word) {
  std::string o = "[";
  try {
    auto& q = h(p)->query;
    auto res = q.query(str(env, word));
    q.query_freq(res);
    // Pitch/IPA rows only match a term with the same reading; kty's German terms have none while their IPA rows use
    // the word itself, so look those up with the expression as the reading.
    std::vector<bool> no_reading;
    for (auto& t : res) {
      no_reading.push_back(t.reading.empty());
      if (t.reading.empty()) t.reading = t.expression;
    }
    q.query_pitch(res);
    for (size_t i = 0; i < res.size(); i++) {
      if (no_reading[i]) res[i].reading.clear();
    }
    for (size_t i = 0; i < res.size(); i++) {
      if (i) o += ',';
      term_json(o, res[i]);
    }
  } catch (...) {
  }
  o += ']';
  return jstr(env, o);
}

// Every loaded dictionary's styles.css: {"dict name": "css", ...}.
JNIEXPORT jstring JNICALL FN(nativeStyles)(JNIEnv* env, jclass, jlong p) {
  std::string o = "{";
  try {
    bool first = true;
    for (const auto& s : h(p)->query.get_styles()) {
      if (s.styles.empty()) continue;
      if (!first) o += ',';
      first = false;
      esc(o, s.dict_name);
      o += ':';
      esc(o, s.styles);
    }
  } catch (...) {
  }
  o += '}';
  return jstr(env, o);
}

// A file a dictionary's structured content shows (an image), or null.
JNIEXPORT jbyteArray JNICALL FN(nativeMedia)(JNIEnv* env, jclass, jlong p, jstring dict, jstring path) {
  try {
    std::vector<uint8_t> out;
    size_t n = h(p)->query.read_media_file(str(env, dict), str(env, path), out, 8 * 1024 * 1024);
    if (n == 0 || out.empty()) return nullptr;
    jbyteArray a = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return a;
  } catch (...) {
    return nullptr;
  }
}

// Yomitan's scan from the start of `text` (Japanese deinflection), longest match first.
JNIEXPORT jstring JNICALL FN(nativeLookup)(JNIEnv* env, jclass, jlong p, jstring text, jint max) {
  std::string o = "[";
  try {
    auto res = h(p)->lookup.lookup(str(env, text), max);
    for (size_t i = 0; i < res.size(); i++) {
      if (i) o += ',';
      o += "{\"matched\":";
      esc(o, res[i].matched);
      o += ",\"deinflected\":";
      esc(o, res[i].deinflected);
      o += ",\"term\":";
      term_json(o, res[i].term);
      o += '}';
    }
  } catch (...) {
  }
  o += ']';
  return jstr(env, o);
}

}  // extern "C"
