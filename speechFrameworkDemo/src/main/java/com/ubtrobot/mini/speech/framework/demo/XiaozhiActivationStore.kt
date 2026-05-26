package com.ubtrobot.mini.speech.framework.demo

import android.content.Context

/** Sau POST /activate HTTP 200 — device đã bind trên xiaozhi.me. */
object XiaozhiActivationStore {
    private const val PREFS = "xiaozhi_activation"
    private const val KEY_ACTIVATED = "activated"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isActivated(): Boolean {
        val ctx = appContext ?: return false
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVATED, false)
    }

    fun markActivated() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVATED, true)
            .apply()
    }

    fun clearActivated() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVATED, false)
            .apply()
    }
}
