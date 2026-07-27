package com.abobicaduco.teleprompter_overlay

import android.view.Surface
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Matemática de rotação da faixa — o coração do app, e o motivo da versão
 * anterior nunca funcionar deitada.
 *
 * O problema: a Câmera da Samsung (como a maioria dos apps de câmera) pode
 * travar a tela em retrato e só girar os próprios ícones quando você deita o
 * aparelho. Quando isso acontece, a tela nunca gira de verdade, então uma
 * janela de overlay também não gira — e o texto do teleprompter fica de lado
 * justo na hora de gravar em 16:9.
 *
 * A solução é não depender da orientação da tela: medir a orientação física
 * do aparelho pela gravidade e girar o conteúdo da faixa por conta própria.
 */
object Rotation {

    /**
     * Abaixo desta força (m/s²) na horizontal, o aparelho está deitado demais
     * pra ter uma orientação confiável — em cima da mesa, a gravidade quase
     * toda vai pro eixo Z e o que sobra em X/Y é ruído.
     *
     * 4,5 m/s² equivale a uns 27° de inclinação: segurando pra gravar sempre
     * passa disso; largado na mesa, nunca.
     */
    private const val MIN_TILT = 4.5f

    /**
     * Orientação física do aparelho (0/90/180/270) a partir do vetor
     * gravidade, ou null quando não dá pra saber — nesse caso quem chama deve
     * manter a orientação que já estava, e não chutar uma nova.
     *
     * Convenção igual à do [android.view.OrientationEventListener]: 90 é o
     * lado esquerdo pra cima, 270 é o direito.
     */
    fun deviceDegrees(gravityX: Float, gravityY: Float): Int? {
        if (hypot(gravityX, gravityY) < MIN_TILT) return null
        val degrees = Math.toDegrees(atan2(-gravityX, gravityY).toDouble())
        val normalized = ((degrees % 360) + 360) % 360
        return bucketOf(normalized.roundToInt())
    }

    /**
     * Arredonda um ângulo pra 0/90/180/270, ou null na zona morta entre duas
     * posições.
     *
     * A zona morta (20° de cada lado de cada diagonal) é o que impede a faixa
     * de ficar piscando entre duas orientações com o aparelho quase na
     * diagonal.
     */
    fun bucketOf(degrees: Int): Int? = when {
        degrees >= 340 || degrees < 20 -> 0
        degrees in 70..109 -> 90
        degrees in 160..199 -> 180
        degrees in 250..289 -> 270
        else -> null
    }

    /**
     * Pra qual orientação física a tela está desenhada, na mesma convenção do
     * sensor (ver [deviceDegrees]).
     *
     * Cuidado: o Android usa duas convenções opostas e isso é fonte clássica
     * de erro. [Surface.ROTATION_90] quer dizer que a tela girou pra
     * acompanhar um aparelho com o lado *direito* pra cima — que o sensor
     * chama de 270. Tratar os dois como a mesma escala deixa a faixa de
     * cabeça pra baixo assim que a Câmera gira a tela.
     *
     * Medido no S25 Ultra: com a Câmera aberta e deitada, o sistema reporta
     * ROTATION_90 enquanto a gravidade aponta +x (lado direito pra cima).
     */
    fun displayDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        Surface.ROTATION_90 -> 270
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 90
        else -> 0
    }

    /**
     * Quanto girar o conteúdo da faixa, em graus no sentido horário.
     *
     * Se a tela já acompanhou o aparelho (um app que gira), os dois valores
     * são iguais e o resultado é 0 — não giramos nada, o sistema já fez. Se a
     * tela ficou travada em retrato e o aparelho está deitado, a diferença é
     * exatamente a compensação que falta.
     */
    fun contentRotation(displayDegrees: Int, deviceDegrees: Int): Int =
        (displayDegrees - deviceDegrees + 360) % 360

    /** O usuário está segurando o aparelho deitado? */
    fun isDeviceLandscape(deviceDegrees: Int): Boolean =
        deviceDegrees == 90 || deviceDegrees == 270

    /** Rotação de 90/270 troca largura por altura na janela. */
    fun swapsAxes(contentRotation: Int): Boolean =
        contentRotation == 90 || contentRotation == 270
}
