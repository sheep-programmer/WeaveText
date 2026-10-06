package com.weavetext.translation.contract;

/** Lightweight IPC identifiers: no provider runtime or language model is linked here. */
public final class TranslationPluginContract {
    private TranslationPluginContract() {}
    public static final int VERSION = 1;
    public static final String PERMISSION = "com.weavetext.ime.permission.TRANSLATION_PLUGIN";
    public static final String PLUGIN_PACKAGE = "com.weavetext.translate.google";
    public static final String PLUGIN_SERVICE = "com.weavetext.translate.google.TranslationPluginService";
    public static final String PLUGIN_ACTIVITY = "com.weavetext.translate.google.MainActivity";
    public static final int MAX_TEXT_CHARS = 32000;
    public static final int MAX_JSON_CHARS = 131072;
}
