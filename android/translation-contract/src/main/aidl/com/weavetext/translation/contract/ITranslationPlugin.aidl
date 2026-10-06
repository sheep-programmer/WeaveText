package com.weavetext.translation.contract;
import com.weavetext.translation.contract.ITranslationCallback;
interface ITranslationPlugin {
    int protocolVersion();
    oneway void request(String requestId, String json, ITranslationCallback callback);
    oneway void cancel(String requestId);
}
