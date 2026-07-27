package com.abobicaduco.teleprompter_overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import kotlin.math.roundToInt

/**
 * Segura a faixa do teleprompter por cima de qualquer app.
 *
 * Roda como foreground service porque a janela precisa continuar viva depois
 * que o PromptCue sai da tela e a Câmera assume.
 */
class PrompterService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var params: WindowManager.LayoutParams

    /** Janela do tamanho já rotacionado; só serve de moldura. */
    private lateinit var root: FrameLayout

    /** Conteúdo de verdade, sempre medido "em pé" e girado por [applyGeometry]. */
    private lateinit var content: LinearLayout

    private lateinit var scroll: PrompterScrollView
    private lateinit var textView: TextView
    private lateinit var playButton: ImageButton
    private lateinit var speedLabel: TextView

    /** Orientação física do aparelho (0/90/180/270), pelo acelerômetro. */
    private var deviceDegrees = 0

    /**
     * Só em build de debug: trava a orientação num valor, pra dar pra testar
     * o comportamento deitado por adb sem precisar virar o aparelho.
     */
    private var forcedDegrees: Int? = null

    private var playing = false
    private var userTouching = false
    private var lastFrameNanos = 0L
    private var scrollRemainder = 0f

    /** Evita refazer a geometria a cada evento do display sem nada ter mudado. */
    private var lastGeometryKey = ""

    private var attached = false

    /**
     * Gravidade suavizada, em m/s². O filtro passa-baixa tira o tremor da mão
     * — sem ele a faixa giraria a cada esbarrão.
     */
    private var gravityX = 0f
    private var gravityY = 0f

    private val gravityListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            if (forcedDegrees != null) return
            gravityX += (event.values[0] - gravityX) * GRAVITY_SMOOTHING
            gravityY += (event.values[1] - gravityY) * GRAVITY_SMOOTHING
            val bucket = Rotation.deviceDegrees(gravityX, gravityY) ?: return
            if (bucket == deviceDegrees) return
            deviceDegrees = bucket
            applyGeometry()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applyGeometry()
        }
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        onFrame(frameTimeNanos)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = Prefs(this)
        windowManager = getSystemService(WindowManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        if (!attached) {
            attachOverlay()
        }

        if (BuildConfig.DEBUG) {
            val forced = intent?.getIntExtra(EXTRA_FORCE_DEGREES, -1) ?: -1
            if (forced >= 0) {
                forcedDegrees = forced
                deviceDegrees = forced
            }
        }

        // Reabrir com um roteiro novo volta tudo pro começo.
        textView.text = prefs.script.ifBlank {
            getString(R.string.overlay_empty_script)
        }
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSize)
        scroll.scrollTo(0, 0)
        setPlaying(false)
        updateSpeedLabel()
        applyGeometry(force = true)

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        setPlaying(false)
        if (attached) {
            getSystemService(SensorManager::class.java).unregisterListener(gravityListener)
            displayManager.unregisterDisplayListener(displayListener)
            runCatching { windowManager.removeView(root) }
            attached = false
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay

    private fun attachOverlay() {
        root = LayoutInflater.from(this)
            .inflate(R.layout.overlay_prompter, null) as FrameLayout
        content = root.findViewById(R.id.content)
        scroll = root.findViewById(R.id.scroll)
        textView = root.findViewById(R.id.text)
        playButton = root.findViewById(R.id.play)
        speedLabel = root.findViewById(R.id.speed_label)

        params = WindowManager.LayoutParams(
            dp(PORTRAIT_WIDTH_DP),
            dp(PORTRAIT_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: a Câmera continua com o foco, e o teclado nunca
            // aparece por cima. LAYOUT_NO_LIMITS: deixa a faixa encostar no
            // furo da câmera, que é justamente onde ela precisa ficar.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        wireControls()

        windowManager.addView(root, params)
        attached = true

        // Ponto de partida: o que a tela está mostrando. O sensor corrige em
        // seguida, se o aparelho estiver inclinado o bastante pra ter certeza.
        deviceDegrees = Rotation.displayDegrees(currentSurfaceRotation())

        val sensorManager = getSystemService(SensorManager::class.java)
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gravity != null) {
            sensorManager.registerListener(
                gravityListener,
                gravity,
                SensorManager.SENSOR_DELAY_NORMAL,
            )
        }
        displayManager.registerDisplayListener(displayListener, null)
    }

    private fun wireControls() {
        playButton.setOnClickListener { setPlaying(!playing) }

        root.findViewById<View>(R.id.speed_down).setOnClickListener {
            prefs.speed -= Prefs.SPEED_STEP
            updateSpeedLabel()
        }
        root.findViewById<View>(R.id.speed_up).setOnClickListener {
            prefs.speed += Prefs.SPEED_STEP
            updateSpeedLabel()
        }
        root.findViewById<View>(R.id.font_down).setOnClickListener {
            prefs.fontSize -= Prefs.FONT_STEP
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSize)
        }
        root.findViewById<View>(R.id.font_up).setOnClickListener {
            prefs.fontSize += Prefs.FONT_STEP
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSize)
        }
        root.findViewById<View>(R.id.recenter).setOnClickListener {
            prefs.clearPosition(deviceDegrees)
            applyGeometry(force = true)
        }
        root.findViewById<View>(R.id.close).setOnClickListener { stopSelf() }

        scroll.onUserTouchChanged = { touching ->
            userTouching = touching
            // Retomar depois do dedo não pode contar o tempo parado como
            // rolagem devida, senão o texto dá um pulo.
            if (!touching) lastFrameNanos = 0L
        }

        setupDragHandle(root.findViewById(R.id.drag))
    }

    /**
     * Arrastar a faixa pela alça.
     *
     * `rawX`/`rawY` são coordenadas físicas da tela, as mesmas de `params.x/y`,
     * então a faixa acompanha o dedo mesmo com o conteúdo girado — não é
     * preciso converter nada.
     */
    private fun setupDragHandle(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    view.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    clampToScreen()
                    windowManager.updateViewLayout(root, params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    prefs.savePosition(deviceDegrees, params.x, params.y)
                    true
                }

                else -> false
            }
        }
    }

    // --------------------------------------------------------------- geometria

    /**
     * Recalcula tamanho, rotação e posição da faixa.
     *
     * Tamanho é fixo (dois presets: de pé e deitado) — só a posição é
     * ajustável, e ela é lembrada por orientação.
     */
    private fun applyGeometry(force: Boolean = false) {
        if (!attached) return

        val surfaceRotation = currentSurfaceRotation()
        val bounds = windowManager.currentWindowMetrics.bounds
        val key = "$deviceDegrees|$surfaceRotation|${bounds.width()}x${bounds.height()}"
        if (!force && key == lastGeometryKey) return
        lastGeometryKey = key

        val landscape = Rotation.isDeviceLandscape(deviceDegrees)
        val rotation = Rotation.contentRotation(
            Rotation.displayDegrees(surfaceRotation),
            deviceDegrees,
        )
        val swap = Rotation.swapsAxes(rotation)

        // Com o conteúdo girado, a largura da faixa ocupa a *altura* da tela.
        // Limitar sempre por bounds.width() encolheria a faixa deitada à
        // toa — foi o que aconteceu na primeira versão desta tela.
        val widthLimit = (if (swap) bounds.height() else bounds.width()) - dp(16)
        val contentWidth = dp(if (landscape) LANDSCAPE_WIDTH_DP else PORTRAIT_WIDTH_DP)
            .coerceAtMost(widthLimit)
        val heightLimit = (if (swap) bounds.width() else bounds.height()) - dp(16)
        val contentHeight = dp(if (landscape) LANDSCAPE_HEIGHT_DP else PORTRAIT_HEIGHT_DP)
            .coerceAtMost(heightLimit)

        params.width = if (swap) contentHeight else contentWidth
        params.height = if (swap) contentWidth else contentHeight

        // O filho é medido no tamanho "em pé" e centralizado; girando em torno
        // do próprio centro ele cobre a moldura exatamente.
        content.layoutParams = FrameLayout.LayoutParams(
            contentWidth,
            contentHeight,
            Gravity.CENTER,
        )
        content.rotation = rotation.toFloat()

        val saved = prefs.position(deviceDegrees)
        if (saved != null) {
            params.x = saved.first
            params.y = saved.second
        } else {
            val (x, y) = defaultPosition(bounds.width(), bounds.height())
            params.x = x
            params.y = y
        }
        clampToScreen()

        windowManager.updateViewLayout(root, params)
    }

    /**
     * Onde a faixa nasce: colada no furo da câmera frontal, que é pra onde o
     * olhar tem que ir na hora de gravar.
     *
     * O furo fica no topo com a tela em pé, mas vai pra uma das laterais
     * quando a própria tela gira (a Câmera da Samsung gira mesmo com a
     * rotação automática desligada) — daí os três casos.
     */
    private fun defaultPosition(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val cutout = windowManager.currentWindowMetrics.windowInsets.displayCutout
        val margin = dp(8)
        val left = cutout?.safeInsetLeft ?: 0
        val right = cutout?.safeInsetRight ?: 0
        val top = cutout?.safeInsetTop ?: 0
        val centeredY = (screenHeight - params.height) / 2
        return when {
            left > 0 -> (left + margin) to centeredY
            right > 0 -> (screenWidth - params.width - right - margin) to centeredY
            else -> (screenWidth - params.width) / 2 to (if (top > 0) top else dp(28)) + margin
        }
    }

    private fun clampToScreen() {
        val bounds = windowManager.currentWindowMetrics.bounds
        params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
    }

    private fun currentSurfaceRotation(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0

    // ----------------------------------------------------------------- rolagem

    private fun setPlaying(value: Boolean) {
        if (value == playing) return
        playing = value
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription =
            getString(if (playing) R.string.pause else R.string.play)

        val choreographer = Choreographer.getInstance()
        if (playing) {
            // Chegou no fim? Recomeça do topo.
            if (scroll.scrollY >= scroll.maxScroll()) scroll.scrollTo(0, 0)
            lastFrameNanos = 0L
            scrollRemainder = 0f
            choreographer.postFrameCallback(frameCallback)
        } else {
            choreographer.removeFrameCallback(frameCallback)
        }
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!playing) return
        Choreographer.getInstance().postFrameCallback(frameCallback)

        if (userTouching) {
            lastFrameNanos = frameTimeNanos
            return
        }
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
            return
        }

        val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameTimeNanos
        // Um quadro perdido (app em segundo plano, GC) não pode virar um salto.
        if (dt <= 0f || dt > 0.25f) return

        val max = scroll.maxScroll()
        if (max <= 0) {
            setPlaying(false)
            return
        }

        scrollRemainder += dpFloat(prefs.speed) * dt
        val step = scrollRemainder.toInt()
        if (step > 0) {
            scrollRemainder -= step
            val target = (scroll.scrollY + step).coerceAtMost(max)
            scroll.scrollTo(0, target)
            if (target >= max) setPlaying(false)
        }
    }

    private fun updateSpeedLabel() {
        speedLabel.text = prefs.speed.roundToInt().toString()
    }

    // ------------------------------------------------------------ notificação

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.channel_description)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PrompterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.close),
                    stop,
                ).build(),
            )
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(value: Int): Int = dpFloat(value.toFloat()).roundToInt()

    private fun dpFloat(value: Float): Float =
        value * resources.displayMetrics.density

    companion object {
        const val ACTION_STOP = "com.abobicaduco.teleprompter_overlay.STOP"

        /** Só pra tela principal saber se mostra "abrir" ou "fechar". */
        @Volatile
        var isRunning = false
            private set

        /** Ver [forcedDegrees]. */
        const val EXTRA_FORCE_DEGREES = "force_degrees"

        private const val CHANNEL_ID = "promptcue_overlay"
        private const val NOTIFICATION_ID = 42

        /** Peso da leitura nova no filtro da gravidade. */
        private const val GRAVITY_SMOOTHING = 0.35f

        // Tamanhos fixos da faixa, em dp. Deitado ela fica mais larga e mais
        // baixa: cabe mais texto por linha e sobra tela pro enquadramento.
        private const val PORTRAIT_WIDTH_DP = 400
        private const val PORTRAIT_HEIGHT_DP = 200
        private const val LANDSCAPE_WIDTH_DP = 520
        private const val LANDSCAPE_HEIGHT_DP = 170

        fun start(context: Context, forceDegrees: Int = -1) {
            context.startForegroundService(
                Intent(context, PrompterService::class.java)
                    .putExtra(EXTRA_FORCE_DEGREES, forceDegrees),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PrompterService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
