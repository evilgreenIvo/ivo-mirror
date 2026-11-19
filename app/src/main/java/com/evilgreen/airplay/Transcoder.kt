/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The iPhone's H.264 (a whole picture only when mirroring starts) made into a stream IVO LIVE can join at
 * any time (a whole picture every second, or on request).
 *
 * decoder (the phone's processor) → SurfaceTexture → drawn with OpenGL → encoder's Surface.
 *
 * The decoder runs on the processor on purpose: Android can take the video chip away from this app when
 * another app needs it (IVO LIVE on this same phone), and a decoder started again in the middle never
 * gets a whole picture from the iPhone. With the drawing in between, the encoder can be swapped alone
 * (chip → processor, smaller) while the decoder goes on: the picture never stops for good.
 */
class Transcoder(sps: ByteArray, pps: ByteArray, size: Pair<Int, Int>?, private val fixed: Boolean,
                 private val high: Boolean, private val out: Output) {

    interface Output {
        /** The encoder's setup (SPS + PPS), first thing after every new encoder. */
        fun config(bytes: ByteArray)
        fun frame(bytes: ByteArray, key: Boolean, ptsUs: Long)
        /** A new encoder with another size: IVO LIVE is told before its setup comes. */
        fun size(width: Int, height: Int)
    }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)

        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
        /** The quad scaled around the centre (the picture fitted in the frame, or filling it). */
        const val VERTEX_SCALED = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            uniform vec2 uScale;
            varying vec2 vTex;
            void main() { gl_Position = vec4(aPos.xy * uScale, 0.0, 1.0); vTex = (uTex * aTex).xy; }
        """
        /** The tiny copy stretched over the frame: soft, and a bit darker so the picture stands out. */
        const val VERTEX_PLAIN = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = aTex.xy; }
        """
        const val FRAGMENT_BLUR = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = vec4(texture2D(sTex, vTex).rgb * 0.45, 1.0); }
        """
        /** The size of the blurred copy: this small, stretched with smoothing, it looks blurred. */
        const val BLUR_W = 32
        const val BLUR_H = 18

        /** The first H.264 codec on the phone's processor (not the video chip), or null. */
        fun softwareCodec(encoder: Boolean): String? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                it.isEncoder == encoder && it.isSoftwareOnly && !it.isAlias && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes
            }?.name
        } catch (_: Exception) { null }
    }

    /** The iPhone's picture size now (changes when it turns, in the fixed frame). */
    @Volatile private var srcW: Int
    @Volatile private var srcH: Int
    /** SPS + PPS to put in front of the next frame (the iPhone turned). */
    @Volatile private var pendingSetup: ByteArray? = null
    private var programScaled = 0
    private var programBlur = 0
    private var blurTex = 0
    private var blurFbo = 0
    private val thread = HandlerThread("IvoAirPlay-gl").apply { start() }
    private val gl = Handler(thread.looper)
    @Volatile private var alive = true

    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var config: EGLConfig
    private lateinit var idle: EGLSurface
    private var program = 0
    private var tex = 0
    private lateinit var texture: SurfaceTexture
    private lateinit var decoderSurface: Surface
    private lateinit var dec: MediaCodec
    private val matrix = FloatArray(16)
    private val quad: ByteBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).apply {
        // x, y, s, t for the 4 corners (a strip).
        asFloatBuffer().put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
    }

    /** Pictures drawn into the encoder (counted on the GL thread). */
    @Volatile private var drawn = 0
    private var decoderBroke = false
    private var enc: Encoder? = null
    private var lastKeyAsk = 0L

    /** The size IVO LIVE gets now. */
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set

    init {
        val (w, h) = size ?: (1920 to 1080)
        srcW = if (w > 0) w else 1920
        srcH = if (h > 0) h else 1080
        var error: Exception? = null
        val ready = java.util.concurrent.CountDownLatch(1)
        gl.post {
            try { setUp(sps, pps) } catch (e: Exception) { error = e }
            ready.countDown()
        }
        ready.await()
        error?.let { release(); throw it }
    }

    private fun setUp(sps: ByteArray, pps: ByteArray) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, idle, idle, context)
        program = makeProgram(VERTEX, FRAGMENT)
        if (fixed) {
            programScaled = makeProgram(VERTEX_SCALED, FRAGMENT)
            programBlur = makeProgram(VERTEX_PLAIN, FRAGMENT_BLUR)
            val bt = IntArray(1)
            GLES20.glGenTextures(1, bt, 0)
            blurTex = bt[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BLUR_W, BLUR_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            blurFbo = fb[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurTex, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(tex).apply {
            setDefaultBufferSize(srcW, srcH)
            setOnFrameAvailableListener({ draw() }, gl)
        }
        decoderSurface = Surface(texture)

        // The encoder first (the chip, at the iPhone's size), then the decoder.
        startEncoder(0)
        val df = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, srcW, srcH).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val soft = softwareCodec(encoder = false)
        dec = if (soft != null) MediaCodec.createByCodecName(soft) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        dec.configure(df, decoderSurface, null, 0)
        dec.start()
        LogBook.add("Vídeo: ${srcW}x$srcH do iPhone · decodificador ${dec.name}" +
            (if (soft == null) " (chip: o Android pode tirar)" else " (processador)"))
        Thread({ drainDecoder() }, "IvoAirPlay-dec").start()
        gl.postDelayed({
            if (alive && drawn == 0) LogBook.add("Vídeo: em 3 s o decodificador não soltou nenhuma imagem " +
                "(se continuar, pare o espelhamento no iPhone e espelhe de novo)")
        }, 3000)
    }

    private fun makeProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(it) }
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    // --- the encoder, swappable ---

    /**
     * One encoder with its own Surface for the drawing. Level 0 = the video chip at the iPhone's size;
     * level 1 = the processor, at most 1280x720 and 30 frames a second (lighter, and nothing to take away).
     */
    private inner class Encoder(val level: Int) {
        val w: Int
        val h: Int
        val codec: MediaCodec
        private val input: Surface
        val egl: EGLSurface
        @Volatile var alive = true
        @Volatile var failed = false
        @Volatile var produced = 0

        init {
            if (fixed) {
                // Always 16:9, whatever the iPhone's shape: IVO LIVE's source never changes size.
                w = if (level >= 1) 1280 else if (high) 2560 else 1920
                h = if (level >= 1) 720 else if (high) 1440 else 1080
            } else {
                val k = if (level >= 1) minOf(1f, 1280f / maxOf(srcW, srcH), 720f / minOf(srcW, srcH)) else 1f
                w = maxOf(16, ((srcW * k).toInt() / 16) * 16)
                h = maxOf(16, ((srcH * k).toInt() / 16) * 16)
            }
            val fps = if (level >= 1) 30 else 60
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (level >= 1) 6_000_000 else 10_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // A still screen sends no frames: the last one is repeated, so whole pictures keep coming.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            val soft = if (level >= 1) softwareCodec(encoder = true) else null
            codec = if (soft != null) MediaCodec.createByCodecName(soft) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                input = codec.createInputSurface()
                codec.start()
                egl = EGL14.eglCreateWindowSurface(display, config, input, intArrayOf(EGL14.EGL_NONE), 0)
                check(egl != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            } catch (e: Exception) {
                try { codec.release() } catch (_: Exception) {}
                throw e
            }
            Thread({ drain() }, "IvoAirPlay-enc").start()
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            while (alive) {
                val o = try { codec.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) { lost(e); break }
                if (o < 0) continue
                try {
                    val buf = codec.getOutputBuffer(o)
                    if (buf != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) out.config(bytes)
                        else {
                            out.frame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs)
                            produced++
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                } catch (e: Exception) { lost(e); break }
            }
        }

        private fun lost(e: Exception) {
            if (!alive) return
            val m = e.message.orEmpty()
            val taken = (e is MediaCodec.CodecException && (e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED ||
                e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE)) || m.contains("release", ignoreCase = true)
            fail(if (taken) "o Android tirou o codificador deste app (outro app precisou, como o IVO LIVE neste mesmo celular)"
                else "o codificador parou (${e.javaClass.simpleName}: ${m.lineSequence().firstOrNull()})")
        }

        /** Gives up this encoder; the GL thread puts a lighter one in its place (the decoder goes on). */
        fun fail(why: String) {
            if (failed) return
            failed = true
            gl.post { if (this@Transcoder.alive && enc === this) replaceEncoder(why) }
        }

        fun release() {
            alive = false
            try { EGL14.eglDestroySurface(display, egl) } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { input.release() } catch (_: Exception) {}
        }
    }

    /** On the GL thread: an encoder at [level], or the next lighter one if Android refuses it. */
    private fun startEncoder(level: Int) {
        var l = level
        while (true) {
            try {
                val e = Encoder(l)
                enc = e
                width = e.w
                height = e.h
                out.size(e.w, e.h)
                LogBook.add("Vídeo: codificador ${e.codec.name} · ${e.w}x${e.h} · " +
                    if (l == 0) "chip de vídeo" else "processador do celular, 30 fps")
                val drawnAtStart = drawn
                gl.postDelayed({
                    if (!alive || enc !== e) return@postDelayed
                    val passed = drawn - drawnAtStart
                    LogBook.add("Vídeo: em 3 s passaram $passed imagens e o codificador soltou ${e.produced}")
                    if (passed > 0 && e.produced == 0) e.fail("o codificador não soltou nada")
                }, 3000)
                return
            } catch (e: Exception) {
                LogBook.add("ERRO ao abrir o codificador (nível $l): ${e.javaClass.simpleName}: ${e.message}")
                if (l >= 1) { enc = null; return }
                l++
            }
        }
    }

    private fun replaceEncoder(why: String) {
        val old = enc ?: return
        if (old.level >= 1) { LogBook.add("Vídeo: $why, e já está no modo mais leve"); return }
        LogBook.add("Vídeo: $why; trocando só o codificador (a imagem continua)")
        EGL14.eglMakeCurrent(display, idle, idle, context)
        old.release()
        enc = null
        startEncoder(old.level + 1)
    }

    // --- drawing ---

    /** On the GL thread, for each picture the decoder hands over: drawn into the encoder's Surface. */
    private fun draw() {
        if (!alive) return
        val e = enc
        try {
            EGL14.eglMakeCurrent(display, e?.egl ?: idle, e?.egl ?: idle, context)
            texture.updateTexImage()
            if (e == null || e.failed) return
            texture.getTransformMatrix(matrix)
            if (fixed) drawFramed(e.w, e.h) else {
                GLES20.glViewport(0, 0, e.w, e.h)
                drawQuad(program, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            }
            EGLExt.eglPresentationTimeANDROID(display, e.egl, texture.timestamp)
            if (EGL14.eglSwapBuffers(display, e.egl)) drawn++
            else e.fail("o codificador não aceitou a imagem (EGL 0x${Integer.toHexString(EGL14.eglGetError())})")
        } catch (x: Exception) {
            if (e != null) e.fail("o desenho para o codificador falhou (${x.javaClass.simpleName}: ${x.message})")
        }
    }

    /** One textured quad with [p]; the scaled program also gets [sx], [sy]. */
    private fun drawQuad(p: Int, target: Int, texture: Int, sx: Float = 1f, sy: Float = 1f) {
        GLES20.glUseProgram(p)
        val pos = GLES20.glGetAttribLocation(p, "aPos")
        val tc = GLES20.glGetAttribLocation(p, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(8)
        GLES20.glVertexAttribPointer(tc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tc)
        GLES20.glGetUniformLocation(p, "uTex").let { if (it >= 0) GLES20.glUniformMatrix4fv(it, 1, false, matrix, 0) }
        GLES20.glGetUniformLocation(p, "uScale").let { if (it >= 0) GLES20.glUniform2f(it, sx, sy) }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * The fixed 16:9 frame: a tiny copy of the picture filling it (stretched, so it's soft and darker)
     * behind, and the whole picture fitted in the middle.
     */
    private fun drawFramed(w: Int, h: Int) {
        val src = srcW.toFloat() / srcH
        val dst = w.toFloat() / h
        // Fitted: the whole picture inside. Filling: covers the frame, the rest cut off.
        val fitX = if (src > dst) 1f else src / dst
        val fitY = if (src > dst) dst / src else 1f
        val fillX = if (src > dst) src / dst else 1f
        val fillY = if (src > dst) 1f else dst / src
        val bars = fitX < 0.995f || fitY < 0.995f
        if (bars) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo)
            GLES20.glViewport(0, 0, BLUR_W, BLUR_H)
            drawQuad(programScaled, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex, fillX, fillY)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        GLES20.glViewport(0, 0, w, h)
        if (bars) drawQuad(programBlur, GLES20.GL_TEXTURE_2D, blurTex)
        drawQuad(programScaled, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex, fitX, fitY)
    }

    /** The iPhone turned: the decoder gets the new setup in front of the next frame; the frame stays. */
    fun newSetup(sps: ByteArray, pps: ByteArray, size: Pair<Int, Int>?) {
        val (w, h) = size ?: return
        if (w <= 0 || h <= 0) return
        pendingSetup = START + sps + START + pps
        srcW = w
        srcH = h
        LogBook.add("Vídeo: o iPhone girou: ${w}x$h (moldura fixa, a imagem segue sem reiniciar)")
    }

    // --- the iPhone's side ---

    fun feed(frame: ByteArray) {
        if (!alive || decoderBroke) return
        try {
            // Waits a little rather than dropping: a dropped picture spoils the ones after it.
            val i = dec.dequeueInputBuffer(200_000)
            if (i < 0) { LogBook.addRare("Aviso: o decodificador está atrasado (quadro do iPhone descartado)"); return }
            val setup = pendingSetup
            pendingSetup = null
            val data = if (setup != null) setup + frame else frame
            dec.getInputBuffer(i)!!.apply { clear(); put(data) }
            dec.queueInputBuffer(i, 0, data.size, System.nanoTime() / 1000, 0)
        } catch (e: Exception) { decoderLost(e) }
    }

    private fun drainDecoder() {
        val info = MediaCodec.BufferInfo()
        while (alive) {
            val o = try { dec.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) { decoderLost(e); break }
            if (o >= 0) {
                val picture = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                try { dec.releaseOutputBuffer(o, picture) } catch (e: Exception) { decoderLost(e); break }
            }
        }
    }

    private fun decoderLost(e: Exception) {
        if (!alive || decoderBroke) return
        decoderBroke = true
        LogBook.add("Vídeo: o decodificador parou (${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}); " +
            "pare o espelhamento no iPhone e espelhe de novo")
    }

    /** A whole picture now (a phone joined or fell behind); at most twice a second. */
    fun keyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyAsk < 500) return
        lastKeyAsk = now
        try { enc?.codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    /** Test only: as if Android took the encoder away now. */
    fun testLoseEncoder() {
        gl.post { enc?.let { e -> try { e.codec.release() } catch (_: Exception) {} } }
    }

    fun release() {
        alive = false
        gl.post {
            try { dec.stop() } catch (_: Exception) {}
            try { dec.release() } catch (_: Exception) {}
            try { EGL14.eglMakeCurrent(display, idle, idle, context) } catch (_: Exception) {}
            enc?.release()
            enc = null
            try { decoderSurface.release() } catch (_: Exception) {}
            try { texture.release() } catch (_: Exception) {}
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, idle)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (_: Exception) {}
            thread.quitSafely()
        }
    }
}
