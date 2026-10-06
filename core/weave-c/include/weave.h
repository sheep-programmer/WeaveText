/*
 * 织文内核 C 接口（core/weave-c）。 C ABI of the WeaveText engine (core/weave-c).
 * 返回 char* 的函数交出所有权，用 weave_string_free 释放。 Free returned strings with weave_string_free.
 */
#ifndef WEAVE_H
#define WEAVE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct WeaveEngine WeaveEngine;

WeaveEngine *weave_create(const char *data_dir, const char *user_dir);
void weave_destroy(WeaveEngine *h);
void weave_string_free(char *s);

bool weave_set_schema(WeaveEngine *h, const char *key);
bool weave_has_schema(WeaveEngine *h, const char *key);
bool weave_set_option(WeaveEngine *h, const char *key, bool on);

bool weave_input_char(WeaveEngine *h, uint32_t code_point);
bool weave_input_key(WeaveEngine *h, uint32_t code_point, uint32_t near, float closeness);
bool weave_backspace(WeaveEngine *h);
bool weave_select(WeaveEngine *h, uint32_t index);
bool weave_select_pinyin(WeaveEngine *h, uint32_t index);
bool weave_forget(WeaveEngine *h, uint32_t index);
void weave_commit_first(WeaveEngine *h);
void weave_commit_raw(WeaveEngine *h);
void weave_clear(WeaveEngine *h);
void weave_flush(WeaveEngine *h);
bool weave_is_composing(WeaveEngine *h);
/* 联想词：上屏后快照里 composing=false、predicting=true，candidates 为联想；选中用 weave_select。
   Predictions: after a commit the snapshot has composing=false, predicting=true and the predictions as candidates. */
void weave_dismiss_predictions(WeaveEngine *h);
void weave_set_learning(WeaveEngine *h, bool on);
void weave_set_context(WeaveEngine *h, const char *prev_word);
/* 本地时区相对 UTC 的分钟数（rq/sj 日期时间候选）。 Local UTC offset in minutes (date/time candidates). */
void weave_set_utc_offset(WeaveEngine *h, int32_t minutes);
/* 专业词库：数据目录与用户目录下的 packs/<id>.wvz 在 weave_create 时自动载入（同名时用户目录优先）。
   Domain dictionaries: packs/<id>.wvz under the data dir and the user dir load at weave_create (the user dir wins). */
bool weave_load_pack(WeaveEngine *h, const char *id, const char *path);
bool weave_unload_pack(WeaveEngine *h, const char *id);
/* 云端热词（hotwords.tsv + .sig，验签后作为扩展词库 cloud）；返回词数，失败 -1。卸载：weave_unload_pack(h, "cloud")。
   Cloud hot words (verified, loaded as the pack "cloud"); word count or -1. Remove with weave_unload_pack(h, "cloud"). */
int32_t weave_load_hotwords(WeaveEngine *h, const char *tsv_path, const char *sig_path);
/* 验签失败（-1）时已挂上的旧热词保持不变。 On failure (-1) the previously attached hot words stay as they were. */
/* ["med","cloud",…] */
char *weave_pack_ids_json(WeaveEngine *h);
/* 宿主自己写了字（标点、空格、符号）：退格不再撤销学习，不与前面连成新词。
   The host wrote text itself: backspace no longer undoes learning, no chaining with the previous commit. */
void weave_break_chain(WeaveEngine *h);
/* 算式结果；非算式返回 NULL。 Result of an arithmetic expression; NULL if it isn't one. */
char *weave_eval(const char *expr);

/* {"commit","preedit","composing","predicting","total","candidates":[{"text","comment","user"}],"pinyinOptions","schema",
    "marks":[{"start","end","kind","removed"}]}
   marks：预编辑里被自动纠错改动的地方，位置按 Unicode 标量计、左闭右开；kind 为 swap（两字母换回来了）/ insert（补上的
   字母）/ replace（换掉的字母）/ delete（去掉多打的字母，start==end，removed 是去掉的字母）。界面用红色标出。
   marks: places in the preedit changed by auto-correction, half-open ranges in Unicode scalars; kind is swap (two letters
   swapped back) / insert (added letter) / replace (replaced letter) / delete (extra letter dropped, start==end, removed
   holds it). Shown in red by the UI. */
char *weave_snapshot_json(WeaveEngine *h);
/* [{"text","comment","user"}] */
char *weave_candidates_json(WeaveEngine *h, uint32_t offset, uint32_t limit);

uint32_t weave_user_word_count(WeaveEngine *h);
/* [{"pinyin","text","count"}] */
char *weave_user_words_json(WeaveEngine *h, const char *query, uint32_t offset, uint32_t limit);
bool weave_delete_user_word(WeaveEngine *h, const char *pinyin, const char *text);
bool weave_clear_user_words(WeaveEngine *h);
uint32_t weave_import_user_words(WeaveEngine *h, const char *text);
char *weave_features_json(WeaveEngine *h, const char *command);

/* ---- 织文互联 / WeaveLink（命令与事件见 core/weave-link/src/lib.rs） ---- */
typedef struct WeaveLink WeaveLink;
/* {"name","platform","stateDir","inboxDir","port"?,"mdns"?} */
WeaveLink *weave_link_start(const char *config_json);
/* 事件 JSON；超时 {"type":"idle"}；停止后 NULL。 Event JSON; {"type":"idle"} on timeout; NULL once stopped. */
char *weave_link_poll(WeaveLink *h, uint32_t timeout_ms);
char *weave_link_call(WeaveLink *h, const char *command_json);
void weave_link_stop(WeaveLink *h);
void weave_link_destroy(WeaveLink *h);

/* Lua plugins share the Android host and .xipk format. Returned strings use weave_string_free. */
typedef struct WeavePluginHost WeavePluginHost;
typedef struct WeaveSpeech WeaveSpeech;
WeavePluginHost *weave_plugin_create(const char *plugins_dir, const char *config_dir);
void weave_plugin_destroy(WeavePluginHost *h);
char *weave_plugin_inspect(const char *path);
char *weave_plugin_command(WeavePluginHost *h, const char *json);
/* Build .xipk from a downloaded source directory; returns NULL on success or an error. */
char *weave_plugin_package(const char *source_dir, const char *output);
/* Callback context ownership transfers to Rust, even on failure. free_context runs only after
 * every callback is finished, including replacements arriving after end. Callbacks can be concurrent.
 * event_json is borrowed for the duration of the callback; never destroy a host/session in a callback. */
typedef void (*WeaveSpeechEvent)(void *context, const char *event_json);
typedef void (*WeaveSpeechFree)(void *context);
WeaveSpeech *weave_plugin_speech(WeavePluginHost *h, const char *id, void *context,
                               WeaveSpeechEvent event, WeaveSpeechFree free_context);
void weave_speech_feed(WeaveSpeech *s, const uint8_t *pcm, size_t length);
void weave_speech_stop(WeaveSpeech *s);
void weave_speech_destroy(WeaveSpeech *s);

#ifdef __cplusplus
}
#endif

#endif
