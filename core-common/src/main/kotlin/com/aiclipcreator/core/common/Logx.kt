package com.aiclipcreator.core.common

import android.util.Log

/** Small logging facade so every module logs through one place. */
object Logx {
    private const val DEFAULT_TAG = "AiClipCreator"

    fun d(message: String, tag: String = DEFAULT_TAG) = Log.d(tag, message)
    fun i(message: String, tag: String = DEFAULT_TAG) = Log.i(tag, message)
    fun w(message: String, tag: String = DEFAULT_TAG) = Log.w(tag, message)
    fun w(message: String, tr: Throwable?, tag: String = DEFAULT_TAG) = Log.w(tag, message, tr)
    fun e(message: String, tr: Throwable? = null, tag: String = DEFAULT_TAG) = Log.e(tag, message, tr)
}
