package local.watchremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal class CoverCache(context: Context) {
    private val folder = File(context.cacheDir, "covers").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Synchronized fun cached(url: String): Bitmap? = memory.get(url)
    fun get(url: String): Bitmap? {
        if (url.isBlank()) return null
        cached(url)?.let { return it }
        val parsed = URL(url)
        if (parsed.protocol != "https") return null
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(folder, name)
        val bytes = if (file.isFile) file.readBytes() else {
            val c = parsed.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4000; c.readTimeout = 4000
                if (c.responseCode != 200) return null
                val data = c.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 512 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (data.size > 512 * 1024) return null
                file.writeBytes(data)
                folder.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
                    var size = files.sumOf { it.length() }
                    for (f in files) if (size > 8 * 1024 * 1024) { val length = f.length(); if (f.delete()) size -= length }
                }
                data
            } finally { c.disconnect() }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) { file.delete(); return null }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 256 || options.outHeight / options.inSampleSize > 256) options.inSampleSize *= 2
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        synchronized(this) { memory.put(url, image) }
        return image
    }
    @Synchronized fun clear() { memory.evictAll(); folder.listFiles()?.forEach { it.delete() } }
}
