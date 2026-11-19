/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Receives an iPhone's screen (AirPlay screen mirroring, by UxPlay's server in [Native]) and hands it
 * on to IVO LIVE as one more "Outro celular": the same packets IVO LIVE's own Enviar sends, on its
 * network discovery, so the live phone finds it in its list like any other phone.
 *
 * The iPhone sends a whole picture only once, at the start, then only changes: a phone that misses a
 * bit could never recover. So the picture is decoded here and encoded again ([Transcoder]) like an
 * Android phone sends its screen: a whole picture every second, and on request.
 */
class ReceiverService : Service(), Native.Listener {

    companion object {
        private const val CHANNEL = "receiver"
        private const val NOTIF = 1
        const val ACTION_STOP = "com.evilgreen.airplay.STOP"

        /** IVO LIVE's discovery and wire (see IVO LIVE's MirrorSender.kt: same packets). */
        private const val IVO_SERVICE = "_ivolive._tcp."
        private const val P_INFO = 2
        private const val P_CONFIG = 3
        private const val P_VIDEO = 4
        private const val P_AUDIO = 5
        private const val P_KEY = 6
        private const val FLAG_KEY = 1
        private const val IVO_RATE = 48000

        @Volatile var running = false
            private set
        /** What the screen shows (main thread reads). */
        @Volatile var status = ""
            private set
        @Volatile var iphone: String? = null
            private set
        @Volatile var picture = ""
            private set
        @Volatile var watchers = 0
            private set
        @Volatile var airplayName = ""
            private set
        /** Where things stand, for the screen's colours (the words are in [status]). */
        @Volatile var phase = Phase.OFF
            private set
        var onChange: (() -> Unit)? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }

        /** Qualidade Alta: the iPhone is told about a 2560x1440 screen (standing up: about twice as sharp). */
        fun highQuality(context: Context) = context.getSharedPreferences("mirror", Context.MODE_PRIVATE).getBoolean("high", false)

        fun setHighQuality(context: Context, on: Boolean) {
            context.getSharedPreferences("mirror", Context.MODE_PRIVATE).edit().putBoolean("high", on).apply()
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private val announced = ConcurrentHashMap<String, NsdManager.RegistrationListener>()
    private var wifiLock: WifiManager.WifiLock? = null
    private var server: ServerSocket? = null
    private var ivoRegistration: NsdManager.RegistrationListener? = null

    // The picture.
    @Volatile private var width = 0
    @Volatile private var height = 0
    /** The iPhone's own setup (SPS + PPS): a new one restarts the transcoder. */
    private var iphoneConfig: ByteArray? = null
    @Volatile private var transcoder: Transcoder? = null
    /** The iPhone's last SPS and PPS, to remake the transcoder at a safe size if the first one gives nothing. */
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    /** The re-encoded picture's setup, as IVO LIVE gets it first. */
    @Volatile private var config: ByteArray? = null
    /**
     * The re-encoded frames since its last whole picture (at most ~2 s): a phone that joins while the
     * iPhone's screen stands still (it sends nothing then) still gets the picture at once.
     */
    private val recent = ArrayList<ByteArray>()
    @Volatile private var lastOutAt = 0L
    private val clients = CopyOnWriteArrayList<Client>()
    private var framesIn = 0
    private var framesOut = 0
    private var framesAt = System.currentTimeMillis()
    private val videoLock = Any()

    // The sound: AAC-ELD from the iPhone, decoded here, sent as 48 kHz stereo PCM.
    private val audioIn = LinkedBlockingQueue<ByteArray>(200)
    @Volatile private var audioCt = 0
    @Volatile private var audioThread: Thread? = null
    private var audioFrames = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // Test builds only: plays an H.264 file (one picture per access unit, AUD-separated) as if an iPhone sent it.
        if (intent?.action == "com.evilgreen.airplay.TEST_FEED" && BuildConfig.DEBUG && running) {
            val path = intent.getStringExtra("path") ?: return START_NOT_STICKY
            // Test only: start clean, and with "lose" (ms) act as if Android took the encoder away then.
            synchronized(videoLock) { transcoder?.release(); transcoder = null; iphoneConfig = null }
            val lose = intent.getLongExtra("lose", 0L)
            if (lose > 0) main.postDelayed({ transcoder?.testLoseEncoder() }, lose)
            Thread({ testFeed(File(path)) }, "IvoAirPlay-test").start()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForeground(NOTIF, notification(getString(R.string.notif_waiting)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        running = true
        LogBook.add("Receptor ligado · app ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        nsd = getSystemService(NsdManager::class.java)
        wifiLock = (applicationContext.getSystemService(WifiManager::class.java))
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "IvoAirPlay").apply { setReferenceCounted(false); acquire() }
        startIvoServer()
        Native.listener = this
        airplayName = "IVO Mirror (${deviceName()})"
        val key = File(filesDir, "airplay-key.pem").absolutePath
        // The "screen" the iPhone is told about: it fits its picture inside (never bigger than its own screen).
        val high = highQuality(this)
        val (w, h) = if (high) 2560 to 1440 else 1920 to 1080
        val port = Native.start(airplayName, macAddress(), key, w, h, 60)
        LogBook.add("Servidor AirPlay: porta $port · nome \"$airplayName\" · qualidade ${if (high) "Alta" else "Normal"} (${w}x$h)")
        if (port > 0) say(getString(R.string.status_ready, airplayName), Phase.READY)
        else say(getString(R.string.status_failed, port), Phase.FAILED)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        phase = Phase.OFF
        Native.listener = null
        try { Native.stop() } catch (_: Throwable) {}
        announced.values.forEach { l -> try { nsd?.unregisterService(l) } catch (_: Exception) {} }
        announced.clear()
        ivoRegistration?.let { try { nsd?.unregisterService(it) } catch (_: Exception) {} }
        try { server?.close() } catch (_: IOException) {}
        clients.forEach { it.close() }
        clients.clear()
        synchronized(videoLock) { transcoder?.release(); transcoder = null; iphoneConfig = null }
        audioThread?.interrupt()
        wifiLock?.release()
        iphone = null
        picture = ""
        watchers = 0
        LogBook.add("Receptor desligado")
        say("")
        super.onDestroy()
    }

    private fun deviceName(): String =
        android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** A made-up hardware address, the same every time (the iPhone remembers the receiver by it). */
    private fun macAddress(): String {
        val sp = getSharedPreferences("receiver", MODE_PRIVATE)
        sp.getString("mac", null)?.let { return it }
        val b = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xFE) or 0x02).toByte()
        val mac = b.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        sp.edit().putString("mac", mac).apply()
        return mac
    }

    private fun say(text: String, now: Phase = phase) {
        status = text
        phase = now
        main.post {
            onChange?.invoke()
            if (running) getSystemService(NotificationManager::class.java).notify(NOTIF, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF00E27A.toInt())
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    // --- AirPlay (from the native server's threads) ---

    override fun announce(type: String, name: String, port: Int, txt: ByteArray?) {
        val m = nsd ?: return
        announced.remove(type)?.let { try { m.unregisterService(it) } catch (_: Exception) {} }
        if (port <= 0) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "$type."
            this.port = port
            txt?.let { parseTxt(it).forEach { (k, v) -> setAttribute(k, v) } }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { LogBook.add("Anunciado na rede: ${i.serviceName} ($type)") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { LogBook.add("ERRO ao anunciar $type: código $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); announced[type] = listener }
        catch (e: Exception) { LogBook.add("ERRO ao anunciar $type: ${e.message}") }
    }

    /** DNS TXT bytes (a length, then "key=value") as pairs. */
    private fun parseTxt(b: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < b.size) {
            val n = b[i].toInt() and 0xFF
            i++
            if (n == 0 || i + n > b.size) break
            val s = String(b, i, n, Charsets.UTF_8)
            i += n
            val eq = s.indexOf('=')
            if (eq > 0) out += s.substring(0, eq) to s.substring(eq + 1)
        }
        return out
    }

    override fun client(name: String, model: String) {
        iphone = name
        LogBook.add("iPhone conectando: $name ($model)")
        say(getString(R.string.status_iphone, name), Phase.RECEIVING)
    }

    override fun connections(count: Int) {
        LogBook.add("Conexões do iPhone abertas: $count")
        if (count == 0) {
            iphone = null
            picture = ""
            synchronized(videoLock) { transcoder?.release(); transcoder = null; iphoneConfig = null }
            say(getString(R.string.status_ready, airplayName), Phase.READY)
        }
    }

    override fun size(width: Int, height: Int) {
        LogBook.add("Tamanho da imagem do iPhone: ${width}x$height")
        if (width == this.width && height == this.height) return
        this.width = width
        this.height = height
        // The transcoder is remade at the next setup from the iPhone (it always comes with a new size).
    }

    override fun log(level: Int, msg: String) {
        LogBook.add("[uxplay] ${msg.trim()}")
    }

    override fun video(data: ByteArray, ntp: Long) {
        // Annex B: split into NAL units; the setup (SPS, PPS) restarts the transcoder when it changes.
        val nals = splitNals(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frame = ByteArrayOutputStream(data.size)
        for ((s, e) in nals) {
            when (data[s].toInt() and 0x1F) {
                7 -> sps = data.copyOfRange(s, e)
                8 -> pps = data.copyOfRange(s, e)
                else -> { frame.write(START); frame.write(data, s, e - s) }
            }
        }
        synchronized(videoLock) {
            val sp = sps
            val pp = pps
            if (sp != null && pp != null) {
                val setup = START + sp + START + pp
                if (!setup.contentEquals(iphoneConfig) || transcoder == null) {
                    iphoneConfig = setup
                    lastSps = sp
                    lastPps = pp
                    transcoder?.release()
                    transcoder = try { Transcoder(sp, pp, spsSize(sp), output) } catch (e: Exception) {
                        LogBook.add("ERRO ao preparar o vídeo: ${e.javaClass.simpleName}: ${e.message}"); null
                    }
                }
            }
            if (frame.size() > 0) transcoder?.feed(frame.toByteArray())
        }
        framesIn++
        val now = System.currentTimeMillis()
        if (now - framesAt >= 2000) {
            val secs = (now - framesAt) / 1000.0
            picture = getString(R.string.picture_line, width, height, (framesIn / secs).toInt(), (framesOut / secs).toInt())
            if (framesOut == 0 && framesIn >= 10 && transcoder != null) LogBook.add("Aviso: chegam ${framesIn} quadros do iPhone mas nenhum saiu do codificador em 2 s")
            framesIn = 0
            framesOut = 0
            framesAt = now
            main.post { onChange?.invoke() }
        }
    }

    private fun testFeed(f: File) {
        val all = f.readBytes()
        val nals = splitNals(all)
        // Access units: a new one at each AUD (type 9).
        val aus = ArrayList<ByteArray>()
        var cur = ByteArrayOutputStream()
        for ((s, e) in nals) {
            if ((all[s].toInt() and 0x1F) == 9 && cur.size() > 0) { aus += cur.toByteArray(); cur = ByteArrayOutputStream() }
            cur.write(START); cur.write(all, s, e - s)
        }
        if (cur.size() > 0) aus += cur.toByteArray()
        LogBook.add("Teste: tocando ${f.name} como se fosse o iPhone (${aus.size} quadros)")
        iphone = "iPhone de teste"
        val t0 = System.nanoTime()
        aus.forEachIndexed { i, au ->
            val due = t0 + i * 33_333_333L
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
            if (!running) return
            video(au, 0)
        }
        LogBook.add("Teste: fim")
    }

    private fun splitNals(b: ByteArray): List<Pair<Int, Int>> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                if (b[i + 2].toInt() == 1) { starts += i + 3; i += 3; continue }
                if (b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) { starts += i + 4; i += 4; continue }
            }
            i++
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (k in starts.indices) {
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else b.size
            if (k + 1 < starts.size && end > starts[k] && b[end - 1].toInt() == 0) end--
            if (end > starts[k]) out += starts[k] to end
        }
        return out
    }

    /**
     * The iPhone's H.264 decoded straight into an encoder's surface (no copy through memory), encoded
     * again with a whole picture every second, and when a phone asks for one.
     */
    /** What the transcoder sends goes to every IVO LIVE watching (and the pictures since the last whole one are kept). */
    private val output = object : Transcoder.Output {
        override fun config(bytes: ByteArray) {
            config = bytes
            val p = packet(P_CONFIG, 0, 0, bytes)
            clients.forEach { it.send(p, true) }
        }

        override fun frame(bytes: ByteArray, key: Boolean, ptsUs: Long) {
            val p = packet(P_VIDEO, if (key) FLAG_KEY else 0, ptsUs, bytes)
            synchronized(recent) {
                if (key) recent.clear()
                if (key || recent.isNotEmpty()) { if (recent.size < 400) recent += p else recent.clear() }
            }
            lastOutAt = System.currentTimeMillis()
            clients.forEach { it.send(p, key) }
            framesOut++
        }

        override fun size(width: Int, height: Int) {
            // A new encoder: the old one's setup and pictures don't fit it any more.
            config = null
            synchronized(recent) { recent.clear() }
            this@ReceiverService.width = width
            this@ReceiverService.height = height
            clients.forEach { it.send(infoPacket(), true) }
        }
    }

    /** Width and height from an H.264 SPS (what the iPhone really sends), or null. */
    private fun spsSize(sps: ByteArray): Pair<Int, Int>? = try {
        // Unescape (drop the 0x03 of 00 00 03), then read the fields up to the size.
        val raw = ByteArrayOutputStream()
        var zeros = 0
        for (i in 1 until sps.size) {
            val b = sps[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            raw.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        val r = BitReader(raw.toByteArray())
        val profile = r.bits(8); r.bits(16); r.ue()
        var chroma = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.bits(1)
            r.ue(); r.ue(); r.bits(1)
            if (r.bits(1) == 1) for (k in 0 until (if (chroma != 3) 8 else 12)) if (r.bits(1) == 1) {
                var last = 8; var next = 8
                for (j in 0 until (if (k < 6) 16 else 64)) { if (next != 0) next = (last + r.se() + 256) % 256; last = if (next == 0) last else next }
            }
        }
        r.ue()
        val poc = r.ue()
        if (poc == 0) r.ue() else if (poc == 1) { r.bits(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        r.ue(); r.bits(1)
        val mbW = r.ue() + 1
        val mbH = r.ue() + 1
        val frameOnly = r.bits(1)
        if (frameOnly == 0) r.bits(1)
        r.bits(1)
        var w = mbW * 16
        var h = mbH * 16 * (2 - frameOnly)
        if (r.bits(1) == 1) {
            val cl = r.ue(); val cr = r.ue(); val ct = r.ue(); val cb = r.ue()
            val ux = if (chroma == 0) 1 else 2
            val uy = (if (chroma == 1) 2 else 1) * (2 - frameOnly)
            w -= (cl + cr) * ux
            h -= (ct + cb) * uy
        }
        w to h
    } catch (_: Exception) { null }

    private class BitReader(val d: ByteArray) {
        var pos = 0
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or ((d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1); pos++ }; return v }
        fun ue(): Int { var z = 0; while (bits(1) == 0) z++; return ((1 shl z) - 1) + bits(z) }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    override fun audioFormat(ct: Int, spf: Int) {
        LogBook.add("Som do iPhone: tipo $ct (${when (ct) { 2 -> "ALAC"; 4 -> "AAC"; 8 -> "AAC-ELD"; else -> "?" }}), $spf amostras por pacote")
        audioCt = ct
        audioIn.clear()
        if (audioThread == null && (ct == 8 || ct == 4)) {
            audioThread = Thread({ decodeAudio() }, "IvoAirPlay-audio").apply { start() }
        }
        if (ct == 2) LogBook.add("Aviso: som ALAC (música, não espelhamento) ainda não é repassado")
    }

    override fun audio(data: ByteArray, ct: Int, ntp: Long) {
        if (ct != 8 && ct != 4) return
        if (audioThread == null) audioFormat(ct, 480)
        if (!audioIn.offer(data)) LogBook.addRare("Aviso: fila do som cheia (pacotes descartados)")
        if (++audioFrames == 1) LogBook.add("Som: primeiro pacote chegou (${data.size} bytes)")
    }

    /** AAC (ELD for screen mirroring) → 16-bit stereo, brought to IVO LIVE's 48 kHz. */
    private fun decodeAudio() {
        var codec: MediaCodec? = null
        var ct = 0
        val info = MediaCodec.BufferInfo()
        var pos = 0.0
        var last = ShortArray(2)
        var outCount = 0
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val frame = audioIn.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (codec == null || ct != audioCt) {
                    codec?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
                    ct = audioCt
                    val eld = ct != 4
                    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, if (eld) 39 else 2)
                        val asc = if (eld) byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00) else byteArrayOf(0x12, 0x10)
                        setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                    }
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(f, null, null, 0); start() }
                    LogBook.add("Som: decodificador ${codec!!.name} (${if (eld) "AAC-ELD" else "AAC"})")
                }
                val c = codec!!
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    c.getInputBuffer(i)!!.apply { clear(); put(frame) }
                    c.queueInputBuffer(i, 0, frame.size, 0, 0)
                }
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val buf = c.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val pcm = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val n = pcm.remaining() / 2
                    val inS = ShortArray(n * 2).also { pcm.get(it) }
                    c.releaseOutputBuffer(o, false)
                    val step = 44100.0 / IVO_RATE
                    val out = ByteArrayOutputStream(n * 5)
                    while (pos < n) {
                        val k = pos.toInt()
                        val t = pos - k
                        for (ch in 0..1) {
                            val a = if (k == 0) last[ch].toInt() else inS[(k - 1) * 2 + ch].toInt()
                            val b = inS[k * 2 + ch].toInt()
                            val v = (a + (b - a) * t).toInt()
                            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                        }
                        pos += step
                    }
                    pos -= n
                    if (n > 0) last = shortArrayOf(inS[(n - 1) * 2], inS[(n - 1) * 2 + 1])
                    if (out.size() > 0) {
                        if (++outCount == 1) LogBook.add("Som: primeiro pedaço decodificado (${n} amostras)")
                        val p = packet(P_AUDIO, 0, System.nanoTime() / 1000, out.toByteArray())
                        clients.forEach { it.send(p, true) }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            LogBook.add("ERRO no som: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            audioThread = null
        }
    }

    // --- IVO LIVE (the live phone connects here, like to another phone's Enviar) ---

    private fun packet(type: Int, flags: Int, pts: Long, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(14 + data.size)
        b.put(type.toByte()).put(flags.toByte()).putLong(pts).putInt(data.size).put(data)
        return b.array()
    }

    private fun infoPacket(): ByteArray {
        val name = iphone ?: "iPhone"
        return packet(P_INFO, 0, 0, "$name\n$width\n$height\n$IVO_RATE\n2\nSCREEN\n0".toByteArray())
    }

    private fun startIvoServer() {
        val s = ServerSocket(0)
        server = s
        Thread({
            while (running) {
                val sock = try { s.accept() } catch (_: IOException) { break }
                Thread({ serve(sock) }, "IvoAirPlay-ivo").start()
            }
        }, "IvoAirPlay-accept").start()
        val info = NsdServiceInfo().apply {
            serviceName = "iPhone · ${deviceName()}"
            serviceType = IVO_SERVICE
            port = s.localPort
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { LogBook.add("IVO LIVE pode achar: ${i.serviceName} (porta ${s.localPort})") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { LogBook.add("ERRO ao anunciar para o IVO LIVE: código $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); ivoRegistration = l } catch (_: Exception) {}
    }

    private fun serve(sock: Socket) {
        val c = Client(sock)
        try {
            sock.tcpNoDelay = true
            val input = DataInputStream(sock.getInputStream().buffered())
            // The live phone says hello (its name) first.
            val hello = readPacket(input)
            LogBook.add("IVO LIVE conectou: ${hello?.second ?: "?"} (${sock.inetAddress.hostAddress})")
            clients += c
            watchers = clients.size
            main.post { onChange?.invoke() }
            c.start()
            c.send(infoPacket(), true)
            config?.let { c.send(packet(P_CONFIG, 0, 0, it), true) }
            sendRecent(c)
            transcoder?.keyFrame()
            while (running) {
                val (type, _) = readPacket(input) ?: break
                if (type == P_KEY) {
                    // A still screen makes no new frames: the last ones go again.
                    if (System.currentTimeMillis() - lastOutAt > 300) sendRecent(c) else transcoder?.keyFrame()
                }
            }
        } catch (_: Exception) {
        } finally {
            c.close()
            if (clients.remove(c)) LogBook.add("IVO LIVE desconectou (${sock.inetAddress.hostAddress})")
            watchers = clients.size
            main.post { onChange?.invoke() }
        }
    }

    /** The frames since the last whole picture (it first), to one phone. */
    private fun sendRecent(c: Client) {
        val list = synchronized(recent) { recent.toList() }
        list.forEachIndexed { i, p -> c.send(p, i == 0) }
    }

    /** One packet's type and, for a hello, its name. */
    private fun readPacket(input: DataInputStream): Pair<Int, String>? {
        val type = try { input.readUnsignedByte() } catch (_: IOException) { return null }
        input.readUnsignedByte()
        input.readLong()
        val len = input.readInt()
        if (len < 0 || len > (8 shl 20)) throw IOException("pacote estranho")
        val data = ByteArray(len)
        input.readFully(data)
        return type to String(data).substringBefore('\n').take(60)
    }

    private inner class Client(val sock: Socket) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var queued = 0L
        @Volatile private var closed = false
        /** Fell behind: frames wait for the next whole picture. */
        @Volatile private var waitKey = true
        private val out: OutputStream = sock.getOutputStream()

        fun start() {
            Thread({
                try {
                    while (!closed) {
                        val p = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        queued -= p.size
                        out.write(p)
                        out.flush()
                    }
                } catch (_: Exception) { close() }
            }, "IvoAirPlay-send").start()
        }

        /** [key]: a whole picture, or not a picture at all (setup, sound) — those always go. */
        fun send(p: ByteArray, key: Boolean) {
            if (closed) return
            val video = p[0].toInt() == P_VIDEO
            if (video && waitKey) {
                if (!key) return
                waitKey = false
            }
            if (queued > 6_000_000L) {
                // Too far behind (slow Wi-Fi): drop what's waiting and start again from a whole picture.
                queue.clear(); queued = 0; waitKey = true
                LogBook.addRare("Aviso: o IVO LIVE ficou para trás (Wi-Fi lento): recomeçando do próximo quadro inteiro")
                transcoder?.keyFrame()
                if (video) return
            }
            queued += p.size
            queue.offer(p)
        }

        fun close() {
            closed = true
            try { sock.close() } catch (_: IOException) {}
        }
    }

    private val START = byteArrayOf(0, 0, 0, 1)
}

/** The receiver's situation, for the screen. */
enum class Phase { OFF, READY, RECEIVING, FAILED }
