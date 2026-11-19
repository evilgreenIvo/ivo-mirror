package com.evilgreen.airplay

/**
 * The AirPlay server (UxPlay, native code): start/stop, and everything it hands up from its own
 * threads. [listener] gets it all (the receiving service).
 */
object Native {
    interface Listener {
        fun announce(type: String, name: String, port: Int, txt: ByteArray?)
        fun video(data: ByteArray, ntp: Long)
        fun audio(data: ByteArray, ct: Int, ntp: Long)
        fun audioFormat(ct: Int, spf: Int)
        fun size(width: Int, height: Int)
        fun client(name: String, model: String)
        fun connections(count: Int)
        fun log(level: Int, msg: String)
        /** The iPhone asks for the PIN: the code to type. */
        fun pin(code: String)
    }

    @Volatile var listener: Listener? = null

    init { System.loadLibrary("ivoairplay") }

    /** Starts announcing and serving; returns the port, or a negative error. */
    /** [pin] = the fixed 4-digit code the iPhone must type the first time, or 0 for none. */
    @JvmStatic external fun start(name: String, mac: String, keyFile: String, width: Int, height: Int, fps: Int, pin: Int): Int
    @JvmStatic external fun stop()

    @JvmStatic fun announce(type: String, name: String, port: Int, txt: ByteArray?) { listener?.announce(type, name, port, txt) }
    @JvmStatic fun video(data: ByteArray, ntp: Long) { listener?.video(data, ntp) }
    @JvmStatic fun audio(data: ByteArray, ct: Int, ntp: Long) { listener?.audio(data, ct, ntp) }
    @JvmStatic fun audioFormat(ct: Int, spf: Int) { listener?.audioFormat(ct, spf) }
    @JvmStatic fun size(width: Int, height: Int) { listener?.size(width, height) }
    @JvmStatic fun client(name: String, model: String) { listener?.client(name, model) }
    @JvmStatic fun connections(count: Int) { listener?.connections(count) }
    @JvmStatic fun log(level: Int, msg: String) { listener?.log(level, msg) }
    @JvmStatic fun pin(code: String) { listener?.pin(code) }
}
