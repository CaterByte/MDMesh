package com.mdmesh.agent.brand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * MeinConnect fork: loads the kiosk logo / background image from an https URL with a small disk cache,
 * so a kiosk that boots offline still shows its branding. Never throws: any failure yields null and the
 * launcher keeps its built-in look.
 */
object RemoteImages {

    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val TIMEOUT_MS = 10_000
    private const val FRESH_MS = 6L * 60 * 60 * 1000
    private const val BUFFER = 16 * 1024

    /** Bitmap for [url], downsampled so its longer side is at most about [maxPx]; null if unavailable. */
    suspend fun load(context: Context, url: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "brand").apply { mkdirs() }
        val file = File(dir, sha256(url))
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > FRESH_MS
        if (stale) download(url, file)
        if (file.exists()) decode(file, maxPx) else null
    }

    private fun download(url: String, target: File) {
        val part = File(target.parentFile, target.name + ".part")
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            try {
                val ok = conn.responseCode == HttpURLConnection.HTTP_OK && conn.contentLengthLong <= MAX_BYTES
                if (ok) {
                    conn.inputStream.use { input -> part.outputStream().use { out -> copyLimited(input, out) } }
                    if (isImage(part)) part.renameTo(target)
                }
            } finally {
                conn.disconnect()
            }
        }
        part.delete()
    }

    private fun copyLimited(input: InputStream, out: OutputStream) {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("image larger than $MAX_BYTES bytes")
            out.write(buf, 0, n)
        }
    }

    private fun isImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun decode(file: File, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
