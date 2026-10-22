package com.neuroflow.cam

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Control de movimiento de la cámara por ONVIF (puerto 2020 en Mercusys/Tapo). */
class Onvif(private val ip: String, private val user: String, private val pass: String, private val port: Int = 2020) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val url get() = "http://$ip:$port/onvif/service"
    private var profile: String? = null

    private fun security(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce); sha.update(created.toByteArray()); sha.update(pass.toByteArray())
        val digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP)
        val n = Base64.encodeToString(nonce, Base64.NO_WRAP)
        return """<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${esc(user)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$n</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security></s:Header>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun call(body: String): String {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">${security()}<s:Body>$body</s:Body></s:Envelope>"""
        val req = Request.Builder().url(url)
            .post(xml.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { return it.body?.string() ?: "" }
    }

    private fun token(): String {
        profile?.let { return it }
        val r = call("<trt:GetProfiles/>")
        val t = Regex("""Profiles[^>]*token="([^"]+)"""").find(r)?.groupValues?.get(1)
            ?: throw IllegalStateException("La cámara no respondió (revisá usuario y clave)")
        profile = t
        return t
    }

    /** Prueba la conexión. Devuelve null si anda, o el error. */
    fun test(): String? = try { token(); null } catch (e: Exception) { e.message ?: "Error" }

    /** Mueve un rato y frena. x: -1 izquierda / 1 derecha. y: -1 abajo / 1 arriba. */
    fun pulse(x: Float, y: Float, ms: Long = 400) {
        val t = token()
        val r = call("""<tptz:ContinuousMove><tptz:ProfileToken>$t</tptz:ProfileToken><tptz:Velocity><tt:PanTilt x="$x" y="$y"/></tptz:Velocity></tptz:ContinuousMove>""")
        if (r.contains("Fault")) {
            call("""<tptz:RelativeMove><tptz:ProfileToken>$t</tptz:ProfileToken><tptz:Translation><tt:PanTilt x="${x * 0.1f}" y="${y * 0.1f}"/></tptz:Translation></tptz:RelativeMove>""")
            return
        }
        Thread.sleep(ms)
        call("""<tptz:Stop><tptz:ProfileToken>$t</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>false</tptz:Zoom></tptz:Stop>""")
    }

    /** Guarda la posición actual. Devuelve el token del preset. */
    fun savePreset(): String {
        val t = token()
        val r = call("""<tptz:SetPreset><tptz:ProfileToken>$t</tptz:ProfileToken><tptz:PresetName>Vigilancia</tptz:PresetName></tptz:SetPreset>""")
        return Regex("""PresetToken>([^<]+)<""").find(r)?.groupValues?.get(1)
            ?: throw IllegalStateException("La cámara no guardó la posición")
    }

    fun gotoPreset(preset: String) {
        val t = token()
        call("""<tptz:GotoPreset><tptz:ProfileToken>$t</tptz:ProfileToken><tptz:PresetToken>$preset</tptz:PresetToken></tptz:GotoPreset>""")
    }
}
