package com.neuroflow.cam

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Busca cámaras ONVIF en el WiFi (WS-Discovery). Sirve cuando el router le cambia la IP a la cámara.
 * Devuelve las IPs encontradas.
 */
object Discovery {
    fun find(ctx: Context, timeoutMs: Int = 3000): List<String> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("neurocam:discovery").apply { setReferenceCounted(false); acquire() }
        val found = linkedSetOf<String>()
        try {
            MulticastSocket().use { sock ->
                sock.soTimeout = 500
                val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>""".toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(probe, probe.size, group, 3702)) }
                val buf = ByteArray(8192)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val txt = String(pkt.data, 0, pkt.length)
                        Regex("""http://(\d+\.\d+\.\d+\.\d+)(:\d+)?/onvif""").findAll(txt).forEach { found.add(it.groupValues[1]) }
                        if (!txt.contains("onvif")) pkt.address?.hostAddress?.let { found.add(it) }
                    } catch (_: SocketTimeoutException) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
        return found.toList()
    }
}
