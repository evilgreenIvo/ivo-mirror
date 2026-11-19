/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Hands the report .txt to the app it's shared with (WhatsApp, Telegram, e-mail), read-only, only for
 * the files in cache/report/. A tiny stand-in for AndroidX's FileProvider (this app has no libraries).
 */
class ReportProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.evilgreen.airplay.report"

        fun dir(context: Context) = File(context.cacheDir, "report").apply { mkdirs() }

        fun uriFor(file: File): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(file.name)}")
    }

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(dir(ctx), name).takeIf { it.isFile }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
