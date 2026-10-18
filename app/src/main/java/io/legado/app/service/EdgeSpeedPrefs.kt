package io.legado.app.service

import android.content.Context

/**
 * 把界面设置的「语速倍数」存到 SharedPreferences("TTS_CONFIG")，
 * 键名与设置界面（SpeakEngineDialog）保持一致。
 */
object EdgeSpeedPrefs {

    private const val PREF_NAME = "TTS_CONFIG"
    private const val KEY_SCALE = "tts_edge_speed_scale"

    fun getScale(context: Context): Float {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SCALE, null) ?: return EdgeSpeed.DEFAULT_SCALE
        return (raw.toFloatOrNull() ?: EdgeSpeed.DEFAULT_SCALE).coerceIn(
            EdgeSpeed.MIN_SCALE, EdgeSpeed.MAX_SCALE
        )
    }

    fun putScale(context: Context, scale: Float) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(
            KEY_SCALE,
            scale.coerceIn(EdgeSpeed.MIN_SCALE, EdgeSpeed.MAX_SCALE).toString()
        ).apply()
    }
}
