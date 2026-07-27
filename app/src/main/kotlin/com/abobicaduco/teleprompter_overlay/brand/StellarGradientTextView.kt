package com.abobicaduco.teleprompter_overlay.brand

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Texto pintado com o gradiente Stellar da marca (#1E90FF → #B24BF3),
 * usado no wordmark do selo.
 */
class StellarGradientTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0) return
        paint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            0f,
            STELLAR_START,
            STELLAR_END,
            Shader.TileMode.CLAMP,
        )
    }

    private companion object {
        const val STELLAR_START = 0xFF1E90FF.toInt()
        const val STELLAR_END = 0xFFB24BF3.toInt()
    }
}
