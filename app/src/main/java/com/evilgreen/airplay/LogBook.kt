/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What happened, in plain words, for the "Enviar log" button: the last lines, with the time. */
object LogBook {
    private const val MAX = 1500
    private val lines = ArrayDeque<String>()
    private val rare = HashMap<String, Long>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(text: String) {
        Log.i("IvoAirPlay", text)
        lines.addLast("${clock.format(Date())}  $text")
        while (lines.size > MAX) lines.removeFirst()
    }

    /** The same warning at most once every 10 s (it can repeat many times a second). */
    @Synchronized
    fun addRare(text: String) {
        val now = System.currentTimeMillis()
        if (now - (rare[text] ?: 0L) < 10_000) return
        rare[text] = now
        add(text)
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    /** Lines that say something went wrong. */
    private val PROBLEM = Regex("ERRO|Aviso|tirou o|parou|não soltou|não passou|não aceitou|falhou|refazendo|mais leve")
    /** Lines that tell the story of the picture and the connections (the rest is noise for the report). */
    private val STORY = listOf("Receptor ligado", "Receptor desligado", "iPhone conectando", "Conexões do iPhone",
        "Tamanho da imagem", "Servidor AirPlay", "Vídeo:", "IVO LIVE conectou",
        "IVO LIVE desconectou", "Som: decodificador", "Teste:")

    private class Problem(val first: String, var last: String, var count: Int, val text: String)

    /**
     * The report sent by "Enviar log": only what helps find the problem. Each problem once (how many
     * times, first and last time), then the story of the picture and the connections, without the noise.
     */
    @Synchronized
    fun problemReport(header: String): String {
        val all = lines.map { it.lineSequence().first() }
        val problems = LinkedHashMap<String, Problem>()
        for (l in all) {
            val time = l.take(12)
            val text = l.drop(14).trim()
            if (text.startsWith("[uxplay]") || !PROBLEM.containsMatchIn(text)) continue
            // "chegam 63 quadros" and "chegam 87 quadros" are the same problem.
            val key = text.replace(Regex("\\d+"), "#")
            val p = problems[key]
            if (p == null) problems[key] = Problem(time, time, 1, text) else { p.last = time; p.count++ }
        }
        val story = all.filter { l ->
            val text = l.drop(14)
            !text.startsWith("[uxplay]") && (STORY.any { text.startsWith(it) } || PROBLEM.containsMatchIn(text))
        }.takeLast(150)
        return buildString {
            append(header).append("\n\n")
            append("==== PROBLEMAS ====\n")
            if (problems.isEmpty()) append("Nenhum problema registrado.\n")
            for (p in problems.values) {
                append(p.first).append("  ").append(p.text)
                if (p.count > 1) append("  (").append(p.count).append("x, a última às ").append(p.last).append(')')
                append('\n')
            }
            append("\n==== IMAGEM E CONEXÕES (linha do tempo) ====\n")
            if (story.isEmpty()) append("(nada ainda: ligue o receptor e conecte o iPhone)\n")
            story.forEach { append(it).append('\n') }
        }
    }
}
