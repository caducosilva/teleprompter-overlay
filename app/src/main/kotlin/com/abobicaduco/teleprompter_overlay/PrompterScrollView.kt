package com.abobicaduco.teleprompter_overlay

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * ScrollView do teleprompter.
 *
 * Duas diferenças em relação ao padrão, as duas por causa da rolagem
 * automática:
 *
 * 1. [fling] é anulado. Sem isso, ao soltar o dedo a inércia continuaria
 *    rolando a tela por mais um tempo, brigando com o `scrollBy` do
 *    auto-scroll. Com o fling desligado, soltar o dedo devolve o controle na
 *    hora e a rolagem retoma exatamente de onde o dedo parou.
 * 2. [onUserTouchChanged] avisa quando o dedo encosta e larga, pra quem
 *    controla a rolagem pausar enquanto o usuário ajusta na mão.
 */
class PrompterScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    var onUserTouchChanged: ((touching: Boolean) -> Unit)? = null

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onUserTouchChanged?.invoke(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUserTouchChanged?.invoke(false)
        }
        return super.onTouchEvent(ev)
    }

    override fun fling(velocityY: Int) {
        // Sem inércia: ver comentário da classe.
    }

    /** Quanto ainda dá pra rolar pra baixo, em px. */
    fun maxScroll(): Int {
        val child = getChildAt(0) ?: return 0
        return (child.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
    }
}
