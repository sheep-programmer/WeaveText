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
void weave_set_learning(WeaveEngine *h, bool on);
void weave_set_context(WeaveEngine *h, const char *prev_word);

/* {"commit","preedit","composing","total","candidates":[{"text","comment","user"}],"pinyinOptions","schema"} */
char *weave_snapshot_json(WeaveEngine *h);
/* [{"text","comment","user"}] */
char *weave_candidates_json(WeaveEngine *h, uint32_t offset, uint32_t limit);

uint32_t weave_user_word_count(WeaveEngine *h);
/* [{"pinyin","text","count"}] */
char *weave_user_words_json(WeaveEngine *h, const char *query, uint32_t offset, uint32_t limit);
bool weave_delete_user_word(WeaveEngine *h, const char *pinyin, const char *text);
bool weave_clear_user_words(WeaveEngine *h);

#ifdef __cplusplus
}
#endif

#endif
