package com.abobicaduco.teleprompter_overlay

import android.content.Context

/**
 * Estado que sobrevive entre aberturas: o roteiro, a velocidade, o tamanho
 * da fonte e a posição da faixa.
 *
 * A posição é guardada por orientação física do aparelho (0/90/180/270 lidos
 * do sensor, não da tela) — de pé e deitado são dois enquadramentos
 * diferentes, e cada um merece lembrar onde a faixa foi deixada.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("promptcue", Context.MODE_PRIVATE)

    var script: String
        get() = sp.getString(KEY_SCRIPT, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SCRIPT, value).apply()

    /** Velocidade de rolagem em dp por segundo. */
    var speed: Float
        get() = sp.getFloat(KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
        set(value) = sp.edit().putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()

    /** Corpo do texto em sp. */
    var fontSize: Float
        get() = sp.getFloat(KEY_FONT, DEFAULT_FONT).coerceIn(MIN_FONT, MAX_FONT)
        set(value) = sp.edit().putFloat(KEY_FONT, value.coerceIn(MIN_FONT, MAX_FONT)).apply()

    /** Posição salva pra uma orientação, ou null se nunca foi arrastada nela. */
    fun position(rotationBucket: Int): Pair<Int, Int>? {
        val x = sp.getInt(keyX(rotationBucket), Int.MIN_VALUE)
        val y = sp.getInt(keyY(rotationBucket), Int.MIN_VALUE)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) return null
        return x to y
    }

    fun savePosition(rotationBucket: Int, x: Int, y: Int) {
        sp.edit()
            .putInt(keyX(rotationBucket), x)
            .putInt(keyY(rotationBucket), y)
            .apply()
    }

    /** Esquece a posição salva pra essa orientação (botão de recentrar). */
    fun clearPosition(rotationBucket: Int) {
        sp.edit()
            .remove(keyX(rotationBucket))
            .remove(keyY(rotationBucket))
            .apply()
    }

    private fun keyX(bucket: Int) = "pos_${bucket}_x"

    private fun keyY(bucket: Int) = "pos_${bucket}_y"

    companion object {
        private const val KEY_SCRIPT = "script"
        private const val KEY_SPEED = "speed"
        private const val KEY_FONT = "font"

        const val DEFAULT_SPEED = 22f
        const val MIN_SPEED = 6f
        const val MAX_SPEED = 90f
        const val SPEED_STEP = 4f

        const val DEFAULT_FONT = 22f
        const val MIN_FONT = 12f
        const val MAX_FONT = 44f
        const val FONT_STEP = 2f
    }
}
