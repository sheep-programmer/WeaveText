package com.weavetext.ime.models

/** Rust 实现的模型包解压（纯 Rust bzip2 + tar）。 Model archive extraction implemented in Rust. */
internal object NativeArchive {
    init {
        System.loadLibrary("weave")
    }

    /**
     * 解压 `.tar.bz2` 到 [dest]，只保留 [keep] 中的文件名（为空则全部），去掉顶层目录。
     * @return null 表示成功，否则为错误信息。 null on success, else the error.
     */
    @JvmStatic external fun nativeExtractTarBz2(archive: String, dest: String, keep: String): String?
}
