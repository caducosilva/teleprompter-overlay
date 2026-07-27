package com.abobicaduco.teleprompter_overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.abobicaduco.teleprompter_overlay.databinding.ActivityMainBinding

/**
 * Tela única do PromptCue: cola o roteiro, abre a faixa, sai da frente.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            openPrompter()
        } else {
            toast(getString(R.string.permission_denied))
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Sem permissão o serviço roda igual, só não mostra a notificação. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        prefs = Prefs(this)
        binding.script.setText(prefs.script)
        binding.script.doAfterTextChanged { prefs.script = it?.toString().orEmpty() }

        binding.open.setOnClickListener { onOpenClicked() }
        binding.close.setOnClickListener {
            PrompterService.stop(this)
            binding.root.postDelayed({ refreshState() }, 250)
        }

        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    /**
     * A partir do targetSdk 35 a tela é desenhada de ponta a ponta, então a
     * barra de status e a de navegação passariam por cima do conteúdo. A
     * marca d'água continua sangrando até as bordas de propósito.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.body) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            windowInsets
        }
    }

    private fun refreshState() {
        binding.close.isEnabled = PrompterService.isRunning
        binding.open.setText(
            if (PrompterService.isRunning) R.string.reopen_prompter else R.string.open_prompter,
        )
    }

    private fun onOpenClicked() {
        if (binding.script.text.isNullOrBlank()) {
            toast(getString(R.string.empty_script))
            return
        }
        prefs.script = binding.script.text.toString()

        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.permission_needed))
            overlayPermission.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        openPrompter()
    }

    /**
     * Abre a faixa e manda o app pro fundo — a ideia é cair direto na Câmera
     * com só o teleprompter na tela.
     */
    private fun openPrompter() {
        PrompterService.start(this, forcedDegreesFromIntent())
        binding.root.postDelayed({ moveTaskToBack(true) }, 250)
    }

    /**
     * Atalho só de debug: `am start … --ei force_degrees 90` trava a faixa
     * numa orientação, pra dar pra conferir o modo deitado por adb sem virar
     * o aparelho na mão.
     */
    private fun forcedDegreesFromIntent(): Int {
        if (!BuildConfig.DEBUG) return -1
        return intent?.getIntExtra(PrompterService.EXTRA_FORCE_DEGREES, -1) ?: -1
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
