package com.neuroflow.cam

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.RectF
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
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

/**
 * El "cerebro": lee el video de la cámara, corre la IA, sigue a la persona y avisa.
 * Vive dentro de VigiaService, así que sigue andando con la app cerrada o la pantalla apagada.
 * El video para la IA se decodifica a un ImageReader (no necesita pantalla).
 */
@OptIn(UnstableApi::class)
class Engine(private val ctx: Context) {

    /** La pantalla se suscribe acá para mostrar estado, diagnóstico y recuadros. */
    interface Ui {
        fun onStatus(text: String)
        fun onHint(text: String)
        fun onBoxes(boxes: List<RectF>, tracking: Boolean)
    }
    var ui: Ui? = null
        set(v) { field = v; v?.onStatus(status); v?.onHint(hint) }

    private val prefs: SharedPreferences = ctx.getSharedPreferences("neurocam", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val aiThread = Executors.newSingleThreadExecutor()
    private val ptzThread = Executors.newSingleThreadExecutor()
    private val netThread = Executors.newSingleThreadExecutor()
    private val frameThread = HandlerThread("frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)
    private val http = OkHttpClient()

    var onvif: Onvif? = null
        private set
    private var player: ExoPlayer? = null
    private var reader: ImageReader? = null
    private var detector: ObjectDetector? = null
    private var zone: List<PointF> = emptyList()
    private var running = false

    var status = "Conectando..."
        private set(v) { field = v; main.post { ui?.onStatus(v) } }
    var hint = ""
        private set(v) { field = v; main.post { ui?.onHint(v) } }

    @Volatile private var wantFrame = false
    private var detecting = false
    var ptzBusy = false
        private set
    var paused = false               // mientras se dibuja el sector
    private var tracking = false
    private var lastSeen = 0L
    private var lastAlert = 0L
    private var homeArrivedAt = 0L
    private var settleUntil = 0L
    private var prevCx = 0.5f
    private var prevT = 0L
    private var videoLag = 500L
    private var lastCx = 0.5f
    private var searches = 0
    private var panGain = 0.55f
    private var lastAimDx = 0f
    private var lastAimAt = 0L
    private var prevBoxes: List<RectF> = emptyList()

    companion object {
        const val ALERT_COOLDOWN = 60_000L
        const val LOST_TIMEOUT = 15_000L
        const val TICK = 200L
        const val SETTLE_EXTRA = 400L
        const val TILT_GAIN = 0.9f
        const val MOTOR_BASE = 250f
        const val MOTOR_MS_PER_DEG = 18f      // velocidad media: gira más despacio pero sin forzar
        const val TRACK_SPEED = 0.5f          // velocidad del motor al seguir (1 = máximo, hace ruido)
        const val MANUAL_SPEED = 0.8f
    }

    fun start() {
        if (running) return
        running = true
        panGain = prefs.getFloat("panGain", 0.55f)
        reloadZone()
        aiThread.execute {
            try {
                val opts = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite2.tflite").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.5f)
                    .setMaxResults(5)
                    .setCategoryAllowlist(listOf("person"))
                    .build()
                detector = ObjectDetector.createFromOptions(ctx, opts)
            } catch (e: Exception) {
                status = "Error cargando la IA: ${e.message}"
            }
        }
        connect()
        main.postDelayed(tick, TICK)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null
        reader?.close(); reader = null
        aiThread.execute { detector?.close(); detector = null }
        frameThread.quitSafely()
    }

    fun reloadZone() {
        zone = ZoneOverlay.parse(prefs.getString("zone", "") ?: "")
    }

    // ---------- Cámara ----------

    fun connect() {
        val ip = prefs.getString("ip", "") ?: ""
        if (ip.isEmpty()) { status = "Falta configurar la cámara"; return }
        val user = prefs.getString("user", "")!!
        val pass = prefs.getString("pass", "")!!
        onvif = Onvif(ip, user, pass).also { it.invertPan = prefs.getBoolean("invert", true) }

        player?.release()
        val lowLatency = DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 1000, 100, 200)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(ctx).setLoadControl(lowLatency).build()
        player = p
        p.volume = 0f
        setReader(p, 640, 360)
        val url = "rtsp://${Uri.encode(user)}:${Uri.encode(pass)}@$ip:554/stream2"
        p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !tracking) status = "👁 Vigilando"
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                val r = reader
                if (size.width > 0 && (r == null || r.width != size.width || r.height != size.height)) {
                    setReader(p, size.width, size.height)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                status = "Sin video, reconectando..."
                main.postDelayed({ if (running) connect() }, 5000)
            }
        })
        p.prepare()
        p.playWhenReady = true
        status = "Conectando a la cámara..."
        ptzThread.execute {
            val err = onvif?.test()
            if (err != null) hint = "Movimiento: $err"
        }
    }

    /** El video para la IA va a un ImageReader: anda sin pantalla. */
    private fun setReader(p: ExoPlayer, w: Int, h: Int) {
        val old = reader
        val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                if (wantFrame) {
                    wantFrame = false
                    val bmp = Yuv.toBitmap(img)
                    main.post { analyze(bmp) }
                }
            } catch (_: Exception) {
            } finally { img.close() }
        }, frameHandler)
        reader = r
        p.setVideoSurface(r.surface)
        old?.close()
    }

    fun manualMove(x: Float, y: Float, onError: (String) -> Unit) {
        val o = onvif ?: return
        if (ptzBusy) return
        ptzBusy = true
        ptzThread.execute {
            try { o.pulse(x * MANUAL_SPEED, y * MANUAL_SPEED, 500) } catch (e: Exception) { main.post { onError(e.message ?: "error") } }
            ptzBusy = false
        }
    }

    fun setInvert(v: Boolean) { onvif?.invertPan = v }

    fun saveHome(done: (String?) -> Unit) {
        val o = onvif ?: return done("Sin cámara")
        ptzThread.execute {
            try {
                prefs.edit().putString("preset", o.savePreset()).apply()
                main.post { done(null) }
            } catch (e: Exception) {
                main.post { done(e.message ?: "error") }
            }
        }
    }

    private fun goHome() {
        val o = onvif ?: return
        val preset = prefs.getString("preset", null)
        tracking = false
        homeArrivedAt = System.currentTimeMillis() + 4000
        status = "↩ Volviendo a su posición"
        if (preset == null) return
        ptzThread.execute { try { o.gotoPreset(preset) } catch (_: Exception) {} }
    }

    // ---------- IA ----------

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            main.postDelayed(this, TICK)
            val p = player ?: return
            if (p.playbackState != Player.STATE_READY) return
            catchUp(p)
            if (detector == null || detecting || paused || ptzBusy) return
            detecting = true
            wantFrame = true
            // si en 2 s no llegó imagen, libera para volver a pedir
            main.postDelayed({ if (wantFrame) { wantFrame = false; detecting = false } }, 2000)
        }
    }

    private fun analyze(bmp: Bitmap) {
        val d = detector ?: run { detecting = false; return }
        aiThread.execute {
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            val boxes = try {
                d.detect(BitmapImageBuilder(bmp).build()).detections().map {
                    val b = it.boundingBox()
                    RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)
                }
            } catch (_: Exception) { emptyList() }
            main.post {
                detecting = false
                handle(boxes, bmp)
            }
        }
    }

    private fun catchUp(p: ExoPlayer) {
        val lag = (p.bufferedPosition - p.currentPosition).coerceIn(0, 3000)
        videoLag = lag
        val want = when {
            lag > 700 -> 1.3f
            lag < 250 -> 1f
            else -> p.playbackParameters.speed
        }
        if (want != p.playbackParameters.speed) p.setPlaybackSpeed(want)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right); val bt = minOf(a.bottom, b.bottom)
        if (r <= l || bt <= t) return 0f
        val inter = (r - l) * (bt - t)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    private fun handle(raw: List<RectF>, frame: Bitmap) {
        if (paused) return
        val shaped = raw.filter { it.width() <= it.height() * 1.2f && it.width() < 0.6f }
        val boxes = shaped.filter { b -> prevBoxes.any { iou(it, b) > 0.3f } }
        prevBoxes = shaped
        ui?.onBoxes(if (tracking) shaped else boxes, tracking)
        val now = System.currentTimeMillis()

        if (tracking) {
            if (shaped.isNotEmpty()) {
                lastSeen = now
                searches = 0
                val target = shaped.minBy { abs(it.centerX() - lastCx) }
                lastCx = target.centerX()
                follow(target)
            } else if (now - lastSeen > LOST_TIMEOUT) {
                goHome()
            } else {
                search(now)
            }
            return
        }

        if (now < homeArrivedAt) return
        if (status.startsWith("↩")) status = "👁 Vigilando"

        if (boxes.isNotEmpty()) hint = "Diagnóstico: veo ${boxes.size} persona(s)" +
            (if (boxes.none { ZoneOverlay.contains(zone, it.centerX(), it.bottom) }) " fuera del sector" else "") +
            (if (!prefs.getBoolean("track", true)) " · seguimiento apagado" else "")
        val intruder = boxes.firstOrNull { ZoneOverlay.contains(zone, it.centerX(), it.bottom) } ?: return
        lastSeen = now
        if (now - lastAlert > ALERT_COOLDOWN) {
            lastAlert = now
            sendAlert(frame)
        }
        if (prefs.getBoolean("track", true) && onvif != null) {
            tracking = true
            prevT = 0L
            lastCx = intruder.centerX()
            searches = 0
            status = "🎯 Siguiendo persona"
            follow(intruder)
        }
    }

    private fun follow(b: RectF) {
        val now = System.currentTimeMillis()
        if (ptzBusy || now < settleUntil || onvif == null) return
        val cx = b.centerX()
        val dt = now - prevT
        val vx = if (prevT != 0L && dt in 100L..1500L) ((cx - prevCx) / dt * 1000f).coerceIn(-0.4f, 0.4f) else 0f
        prevCx = cx; prevT = now
        val dx = (cx + vx * 0.3f).coerceIn(0f, 1f) - 0.5f
        val dy = b.centerY() - 0.5f
        val moveX = abs(dx) > 0.08f
        val moveY = abs(dy) > 0.22f
        if (!moveX && !moveY) return

        val o = onvif!!
        if (o.relativeOk != false) {
            val rawDx = cx - 0.5f
            if (now - lastAimAt < 5000 && abs(lastAimDx) > 0.08f) {
                val r = (rawDx / lastAimDx).coerceIn(-1f, 1f)
                panGain = (panGain * (1f + 0.5f * r)).coerceIn(0.15f, 2f)
                prefs.edit().putFloat("panGain", panGain).apply()
            }
            val tx = if (moveX) dx * panGain else 0f
            val ty = if (moveY) -dy * TILT_GAIN else 0f
            lastAimDx = if (moveX) rawDx else 0f
            aimTurn(tx, ty, "persona a la ${lado(tx, ty)} (${"%.2f".format(dx)})")
            return
        }

        val x = if (moveX) (if (dx > 0) TRACK_SPEED else -TRACK_SPEED) else 0f
        val y = if (moveY) (if (dy > 0) -TRACK_SPEED else TRACK_SPEED) else 0f
        val ms = (maxOf(if (moveX) abs(dx) else 0f, if (moveY) abs(dy) else 0f) * 2400).toLong().coerceIn(200, 1100)
        turn(x, y, ms, "persona a la ${lado(x, y)} (${"%.2f".format(dx)})")
    }

    private fun aimTurn(tx: Float, ty: Float, why: String) {
        val o = onvif ?: return
        ptzBusy = true
        settleUntil = Long.MAX_VALUE
        ptzThread.execute {
            val r = try { o.aim(tx, ty, TRACK_SPEED) } catch (e: Exception) { "error: ${e.message}" }
            main.post {
                val deg = maxOf(abs(tx) * 180f, abs(ty) * 60f)
                val wait = (MOTOR_BASE + deg * MOTOR_MS_PER_DEG).toLong() + videoLag + SETTLE_EXTRA
                settleUntil = System.currentTimeMillis() + wait
                lastAimAt = System.currentTimeMillis() + wait
                prevT = 0L
                prevBoxes = emptyList()
                ptzBusy = false
                hint = "Diagnóstico: $why · ganancia ${"%.2f".format(panGain)} · espera $wait ms · cámara: $r"
            }
        }
    }

    private fun search(now: Long) {
        if (ptzBusy || now < settleUntil || searches >= 2) return
        if (now - lastSeen < 800) return
        val edge = lastCx - 0.5f
        if (abs(edge) < 0.25f) return
        searches++
        val x = if (edge > 0) TRACK_SPEED else -TRACK_SPEED
        turn(x, 0f, 700, "buscando a la ${lado(x, 0f)}")
    }

    private fun lado(x: Float, y: Float) = when { x > 0 -> "derecha"; x < 0 -> "izquierda"; y > 0 -> "arriba"; else -> "abajo" }

    private fun turn(x: Float, y: Float, ms: Long, why: String) {
        val o = onvif ?: return
        ptzBusy = true
        settleUntil = Long.MAX_VALUE
        ptzThread.execute {
            val r = try { o.pulse(x, y, ms) } catch (e: Exception) { "error: ${e.message}" }
            main.post {
                val wait = videoLag + SETTLE_EXTRA
                settleUntil = System.currentTimeMillis() + wait
                prevT = 0L
                prevBoxes = emptyList()
                ptzBusy = false
                hint = "Diagnóstico: $why · giro $ms ms · espera $wait ms · cámara: $r"
            }
        }
    }

    private fun sendAlert(frame: Bitmap) {
        val hook = prefs.getString("webhook", "") ?: ""
        val hora = SimpleDateFormat("HH:mm:ss", Locale("es", "AR")).format(Date())
        status = "🚨 Persona detectada $hora"
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
                hint = "No se pudo avisar: ${e.message}"
            }
        }
    }
}
