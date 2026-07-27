package com.abobicaduco.teleprompter_overlay.brand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.abobicaduco.teleprompter_overlay.R

/**
 * Marca d'água CADUCOSILVA no fundo da tela: Orbitron 900, na diagonal
 * (-22°), opacidade baixa. Puramente decorativa — não intercepta toque.
 */
class BrandWatermarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val text = "CADUCOSILVA"
    private val bounds = Rect()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val orbitron = ResourcesCompat.getFont(context, R.font.orbitron)
        typeface = if (orbitron != null) {
            Typeface.create(orbitron, 900, false)
        } else {
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        color = 0xFFFFFFFF.toInt()
        alpha = 12 // ~4,7%
        letterSpacing = 0.12f
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // Uma medida só: o corpo é escolhido pra palavra atravessar a tela.
        paint.textSize = width * 0.19f
        paint.getTextBounds(text, 0, text.length, bounds)

        canvas.save()
        canvas.rotate(-22f, width / 2f, height / 2f)
        canvas.drawText(
            text,
            (width - bounds.width()) / 2f - bounds.left,
            height / 2f + bounds.height() / 2f,
            paint,
        )
        canvas.restore()
    }
}
