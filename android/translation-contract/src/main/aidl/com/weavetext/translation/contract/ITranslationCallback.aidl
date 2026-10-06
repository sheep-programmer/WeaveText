package com.weavetext.translation.contract;
oneway interface ITranslationCallback {
    void onEvent(String requestId, String json);
}
