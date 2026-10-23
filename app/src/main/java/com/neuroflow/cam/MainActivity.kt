package com.neuroflow.cam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Pantalla: muestra el video, los recuadros y los botones.
 * La vigilancia y el seguimiento los hace VigiaService (Engine), que sigue andando con la app cerrada.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity(), Engine.Ui {

    private lateinit var prefs: SharedPreferences
    private lateinit var playerView: PlayerView
    private lateinit var overlay: ZoneOverlay
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var btnZone: Button
    private lateinit var btnTrack: Button

    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val engine get() = VigiaService.engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("neurocam", MODE_PRIVATE)

        playerView = findViewById(R.id.player)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        btnZone = findViewById(R.id.btnZone)
        btnTrack = findViewById(R.id.btnTrack)

        overlay.zoneFromString(prefs.getString("zone", "") ?: "")
        updateTrackButton()
        updateHint()

        btnZone.setOnClickListener { toggleDrawing() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            overlay.drawing = false
            engine?.paused = false
            overlay.zone.clear()
            overlay.invalidate()
            prefs.edit().putString("zone", "").apply()
            engine?.reloadZone()
            btnZone.text = "✏️  Dibujar sector"
            updateHint()
            toast("Sector borrado: ahora vigila toda la imagen")
        }
        btnTrack.setOnClickListener {
            prefs.edit().putBoolean("track", !prefs.getBoolean("track", true)).apply()
            updateTrackButton()
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener {
            val e = engine ?: return@setOnClickListener toast("Esperá a que conecte la cámara")
            e.saveHome { err ->
                if (err == null) { toast("Posición de vigilancia guardada"); updateHint() }
                else toast("No se pudo guardar: $err")
            }
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.flip).setOnClickListener {
            val v = !prefs.getBoolean("invert", true)
            prefs.edit().putBoolean("invert", v).apply()
            engine?.setInvert(v)
            toast(if (v) "Izquierda/derecha invertidas" else "Izquierda/derecha normales")
        }
        findViewById<Button>(R.id.left).setOnClickListener { move(-1f, 0f) }
        findViewById<Button>(R.id.right).setOnClickListener { move(1f, 0f) }
        findViewById<Button>(R.id.up).setOnClickListener { move(0f, 1f) }
        findViewById<Button>(R.id.down).setOnClickListener { move(0f, -1f) }

        askPermissions()
        if ((prefs.getString("ip", "") ?: "").isEmpty()) showSettings()
    }

    override fun onStart() {
        super.onStart()
        if ((prefs.getString("ip", "") ?: "").isNotEmpty()) {
            VigiaService.start(this)
            startViewer()
        }
        attach()
    }

    override fun onStop() {
        // la pantalla deja de mostrar, pero la vigilancia sigue en el servicio
        engine?.ui = null
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null
        super.onStop()
    }

    /** Se conecta al servicio para recibir estado y recuadros (reintenta hasta que exista). */
    private fun attach() {
        val e = engine
        if (e == null) { main.postDelayed({ attach() }, 500); return }
        e.ui = this
    }

    // ---------- Engine.Ui ----------

    override fun onStatus(text: String) { status.text = text }
    override fun onHint(text: String) { if (!overlay.drawing && text.isNotEmpty()) hint.text = text }
    override fun onBoxes(boxes: List<RectF>, tracking: Boolean) {
        overlay.showZone = !tracking
        overlay.setBoxes(boxes)
    }

    // ---------- Video para mirar ----------

    private fun startViewer() {
        val ip = prefs.getString("ip", "") ?: return
        val user = prefs.getString("user", "")!!
        val pass = prefs.getString("pass", "")!!
        player?.release()
        val lowLatency = DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 1000, 100, 200)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(lowLatency).build()
        player = p
        playerView.player = p
        val url = "rtsp://${Uri.encode(user)}:${Uri.encode(pass)}@$ip:554/stream2"
        p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                main.postDelayed({ if (player === p) startViewer() }, 5000)
            }
        })
        p.prepare()
        p.playWhenReady = true
    }

    private fun move(x: Float, y: Float) {
        val e = engine ?: return toast("Esperá a que conecte la cámara")
        e.manualMove(x, y) { toast("No se movió: $it") }
    }

    // ---------- Pantalla ----------

    private fun toggleDrawing() {
        if (!overlay.drawing) {
            overlay.zone.clear()
            overlay.setBoxes(emptyList())
            overlay.drawing = true
            overlay.showZone = true
            engine?.paused = true
            btnZone.text = "✔  Listo, guardar"
            hint.text = "Tocá la imagen para marcar las esquinas del sector (mínimo 3). Sin puntos = toda la imagen."
        } else {
            overlay.drawing = false
            prefs.edit().putString("zone", overlay.zoneToString()).apply()
            engine?.reloadZone()
            engine?.paused = false
            btnZone.text = "✏️  Dibujar sector"
            updateHint()
        }
    }

    private fun updateTrackButton() {
        val on = prefs.getBoolean("track", true)
        btnTrack.text = if (on) "🎯  Seguimiento: SÍ" else "🎯  Seguimiento: NO"
        btnTrack.setBackgroundResource(if (on) R.drawable.btn_green else R.drawable.btn_gray)
    }

    private fun updateHint() {
        hint.text = when {
            prefs.getString("preset", null) == null ->
                "1) Apuntá la cámara con las flechas  2) Tocá \"Guardar posición\"  3) Dibujá el sector"
            overlay.zone.size < 3 -> "Vigila toda la imagen, también con la app cerrada."
            else -> "Avisa cuando una persona entra al sector rojo, también con la app cerrada."
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        fun field(hint: String, key: String, def: String, pass: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(prefs.getString(key, def))
            if (pass) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            box.addView(this)
        }
        val ip = field("IP de la cámara", "ip", "192.168.1.50")
        val user = field("Usuario (Cuenta de la cámara)", "user", "")
        val pass = field("Clave (Cuenta de la cámara)", "pass", "", true)
        val hook = field("Webhook de n8n", "webhook", "https://vps-6415136-x.dattaweb.com/webhook/camara-mc200")

        AlertDialog.Builder(this)
            .setTitle("Ajustes de la cámara")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit()
                    .putString("ip", ip.text.toString().trim())
                    .putString("user", user.text.toString().trim())
                    .putString("pass", pass.text.toString())
                    .putString("webhook", hook.text.toString().trim())
                    .apply()
                VigiaService.start(this)
                engine?.connect()
                startViewer()
                attach()
            }
            .show()
    }

    /** Notificaciones (Android 13+) y que el sistema no corte la app para ahorrar batería. */
    @SuppressLint("BatteryLife")
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
