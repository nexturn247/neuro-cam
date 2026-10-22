package com.neuroflow.cam

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.TextureView
import android.view.WindowManager
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var playerView: PlayerView
    private lateinit var overlay: ZoneOverlay
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var btnZone: Button
    private lateinit var btnTrack: Button

    private var player: ExoPlayer? = null
    private var detector: ObjectDetector? = null
    private var onvif: Onvif? = null

    private val main = Handler(Looper.getMainLooper())
    private val aiThread = Executors.newSingleThreadExecutor()
    private val ptzThread = Executors.newSingleThreadExecutor()
    private val netThread = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient()

    private var detecting = false
    private var ptzBusy = false
    private var tracking = false      // la cámara está siguiendo a alguien
    private var lastSeen = 0L
    private var lastAlert = 0L
    private var homeArrivedAt = 0L
    private var settleUntil = 0L
    private var prevCx = 0.5f
    private var prevT = 0L
    private var lastPos = -1L         // para detectar video congelado
    private var lastPosChange = 0L      // espera a que el video se ponga al día después de girar

    companion object {
        const val ALERT_COOLDOWN = 60_000L   // un aviso por minuto como máximo
        const val LOST_TIMEOUT = 15_000L     // sin ver a nadie 15 s: vuelve a su posición
        const val TICK = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            overlay.zone.clear()
            overlay.invalidate()
            prefs.edit().putString("zone", "").apply()
            btnZone.text = "Dibujar sector"
            updateHint()
            toast("Sector borrado: ahora vigila toda la imagen")
        }
        btnTrack.setOnClickListener {
            prefs.edit().putBoolean("track", !prefs.getBoolean("track", true)).apply()
            updateTrackButton()
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener { saveHome() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.flip).setOnClickListener {
            val v = !prefs.getBoolean("invert", true)
            prefs.edit().putBoolean("invert", v).apply()
            onvif?.invertPan = v
            toast(if (v) "Izquierda/derecha invertidas" else "Izquierda/derecha normales")
        }
        findViewById<Button>(R.id.left).setOnClickListener { manualMove(-0.6f, 0f) }
        findViewById<Button>(R.id.right).setOnClickListener { manualMove(0.6f, 0f) }
        findViewById<Button>(R.id.up).setOnClickListener { manualMove(0f, 0.6f) }
        findViewById<Button>(R.id.down).setOnClickListener { manualMove(0f, -0.6f) }

        aiThread.execute {
            try {
                val opts = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite2.tflite").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.3f)
                    .setMaxResults(5)
                    .setCategoryAllowlist(listOf("person"))
                    .build()
                detector = ObjectDetector.createFromOptions(this, opts)
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error cargando la IA: ${e.message}" }
            }
        }

        if ((prefs.getString("ip", "") ?: "").isEmpty()) showSettings() else connect()
        main.postDelayed(tick, TICK)
    }

    // ---------- Cámara ----------

    private fun connect() {
        val ip = prefs.getString("ip", "")!!
        val user = prefs.getString("user", "")!!
        val pass = prefs.getString("pass", "")!!
        onvif = Onvif(ip, user, pass).also { it.invertPan = prefs.getBoolean("invert", true) }

        player?.release()
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        val url = "rtsp://${Uri.encode(user)}:${Uri.encode(pass)}@$ip:554/stream2"
        val src = RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(src)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status.text = "👁 Vigilando"
            }
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Sin video, reconectando..."
                main.postDelayed({ connect() }, 5000)
            }
        })
        p.prepare()
        p.playWhenReady = true
        status.text = "Conectando a la cámara..."

        ptzThread.execute {
            val err = onvif?.test()
            if (err != null) runOnUiThread { toast("Movimiento: $err") }
        }
    }

    private fun manualMove(x: Float, y: Float) {
        val o = onvif ?: return
        if (ptzBusy) return
        ptzBusy = true
        ptzThread.execute {
            try { o.pulse(x, y, 500) } catch (e: Exception) { runOnUiThread { toast("No se movió: ${e.message}") } }
            ptzBusy = false
        }
    }

    private fun saveHome() {
        val o = onvif ?: return
        ptzThread.execute {
            try {
                val token = o.savePreset()
                prefs.edit().putString("preset", token).apply()
                runOnUiThread { toast("Posición de vigilancia guardada"); updateHint() }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo guardar: ${e.message}") }
            }
        }
    }

    private fun goHome() {
        val o = onvif ?: return
        val preset = prefs.getString("preset", null)
        tracking = false
        homeArrivedAt = System.currentTimeMillis() + 4000 // espera a que llegue
        overlay.showZone = true
        status.text = "↩ Volviendo a su posición"
        if (preset == null) return
        ptzThread.execute { try { o.gotoPreset(preset) } catch (_: Exception) {} }
    }

    // ---------- IA ----------

    private val tick = object : Runnable {
        override fun run() {
            main.postDelayed(this, TICK)
            val d = detector ?: return
            if (detecting || overlay.drawing) return
            val tv = playerView.videoSurfaceView as? TextureView ?: return
            if (player?.playbackState != Player.STATE_READY) return
            val bmp = tv.getBitmap(960, 540) ?: return
            detecting = true
            aiThread.execute {
                val boxes = try {
                    d.detect(BitmapImageBuilder(bmp).build()).detections().map {
                        val b = it.boundingBox()
                        RectF(b.left / 960f, b.top / 540f, b.right / 960f, b.bottom / 540f)
                    }
                } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    detecting = false
                    handle(boxes, bmp)
                }
            }
        }
    }

    private fun handle(boxes: List<RectF>, frame: Bitmap) {
        overlay.setBoxes(boxes)
        val now = System.currentTimeMillis()

        if (tracking) {
            if (boxes.isNotEmpty()) {
                lastSeen = now
                follow(boxes.maxBy { it.width() * it.height() })
            } else if (now - lastSeen > LOST_TIMEOUT) {
                goHome()
            }
            return
        }

        if (now < homeArrivedAt) return // todavía volviendo
        if (status.text.startsWith("↩")) status.text = "👁 Vigilando"

        if (boxes.isNotEmpty()) hint.text = "Diagnóstico: veo ${boxes.size} persona(s)" +
            (if (boxes.none { overlay.contains(it.centerX(), it.bottom) }) " fuera del sector" else "") +
            (if (!prefs.getBoolean("track", true)) " · seguimiento apagado" else "")
        val intruder = boxes.firstOrNull { overlay.contains(it.centerX(), it.bottom) } ?: return
        lastSeen = now
        if (now - lastAlert > ALERT_COOLDOWN) {
            lastAlert = now
            sendAlert(frame)
        }
        if (prefs.getBoolean("track", true) && onvif != null) {
            tracking = true
            prevT = 0L
            overlay.showZone = false
            status.text = "🎯 Siguiendo persona"
            follow(intruder)
        }
    }

    /**
     * Gira la cámara para dejar a la persona en el centro.
     * Giros cortos proporcionales a la distancia, y después espera a que el video
     * se actualice antes de decidir otro giro (así no se pasa de largo).
     */
    private fun follow(b: RectF) {
        val o = onvif ?: return
        val now = System.currentTimeMillis()
        if (ptzBusy || now < settleUntil) return
        // la lente es muy abierta: la persona se mueve poco en la imagen,
        // así que corrige apenas se corre del centro y se adelanta a hacia dónde camina
        val cx = b.centerX()
        val dt = (now - prevT).coerceAtLeast(1)
        val vx = if (prevT != 0L && dt < 2000) (cx - prevCx) / dt * 1000f else 0f
        prevCx = cx; prevT = now
        val dx = (cx + vx * 0.8f).coerceIn(0f, 1f) - 0.5f
        val dy = b.centerY() - 0.5f
        val moveX = abs(dx) > 0.07f
        val moveY = abs(dy) > 0.22f
        if (!moveX && !moveY) return
        // velocidad máxima, y el tiempo de giro según qué tan lejos del centro está
        // misma velocidad que las flechas (que andan bien)
        val x = if (moveX) (if (dx > 0) 0.6f else -0.6f) else 0f
        val y = if (moveY) (if (dy > 0) -0.6f else 0.6f) else 0f
        val ms = (maxOf(abs(dx), abs(dy)) * 2000).toLong().coerceIn(200, 900)
        val lado = when { x > 0 -> "derecha"; x < 0 -> "izquierda"; y > 0 -> "arriba"; else -> "abajo" }
        ptzBusy = true
        settleUntil = now + ms + 700
        ptzThread.execute {
            val r = try { o.pulse(x, y, ms) } catch (e: Exception) { "error: ${e.message}" }
            runOnUiThread { hint.text = "Diagnóstico: persona a la $lado (${"%.2f".format(dx)}) · giro $ms ms · cámara: $r" }
            ptzBusy = false
        }
    }

    private fun sendAlert(frame: Bitmap) {
        val hook = prefs.getString("webhook", "") ?: ""
        val hora = SimpleDateFormat("HH:mm:ss", Locale("es", "AR")).format(Date())
        status.text = "🚨 Persona detectada $hora"
        if (hook.isEmpty()) return
        netThread.execute {
            try {
                val out = ByteArrayOutputStream()
                frame.compress(Bitmap.CompressFormat.JPEG, 70, out)
                val json = JSONObject()
                    .put("texto", "Persona en el sector a las $hora")
                    .put("camara", "MC200")
                    .put("imagen", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                val req = Request.Builder().url(hook)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().close()
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo avisar: ${e.message}") }
            }
        }
    }

    // ---------- Pantalla ----------

    private fun toggleDrawing() {
        if (!overlay.drawing) {
            overlay.zone.clear()
            overlay.setBoxes(emptyList())
            overlay.drawing = true
            overlay.showZone = true
            btnZone.text = "✔ Listo"
            hint.text = "Tocá la imagen para marcar las esquinas del sector (mínimo 3). Sin puntos = toda la imagen."
        } else {
            overlay.drawing = false
            prefs.edit().putString("zone", overlay.zoneToString()).apply()
            btnZone.text = "Dibujar sector"
            updateHint()
        }
    }

    private fun updateTrackButton() {
        btnTrack.text = if (prefs.getBoolean("track", true)) "Seguimiento: SÍ" else "Seguimiento: NO"
    }

    private fun updateHint() {
        hint.text = when {
            prefs.getString("preset", null) == null ->
                "1) Apuntá la cámara con las flechas  2) Tocá \"Guardar esta posición\"  3) Dibujá el sector"
            overlay.zone.size < 3 -> "Vigila toda la imagen. Tocá \"Dibujar sector\" para marcar una zona."
            else -> "Avisa cuando una persona entra al sector rojo."
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
                connect()
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player?.release()
        detector?.close()
        super.onDestroy()
    }
}
