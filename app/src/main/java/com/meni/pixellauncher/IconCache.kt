package com.meni.pixellauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * מטמון אייקונים: כל אייקון מצויר פעם אחת ונשמר בזיכרון ובדיסק.
 * החתימה כוללת את גרסת האפליקציה, הצורה, הצבעים וחבילת האייקונים,
 * כך שכל שינוי באחד מהם מצייר מחדש רק את מה שצריך.
 */
object IconCache {
    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    private fun dir(context: Context) = File(context.cacheDir, "icons").apply { mkdirs() }

    private fun fileName(signature: String): String =
        MessageDigest.getInstance("MD5").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".png"

    fun get(context: Context, signature: String, render: () -> Bitmap): ImageBitmap {
        memory[signature]?.let { return it }
        val file = File(dir(context), fileName(signature))
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { bitmap ->
                return bitmap.asImageBitmap().also { memory[signature] = it }
            }
        }
        val bitmap = render()
        runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bitmap.asImageBitmap().also { memory[signature] = it }
    }

    /** מוחק אייקונים ישנים שכבר לא בשימוש */
    fun prune(context: Context, usedSignatures: Collection<String>) {
        val keep = usedSignatures.map { fileName(it) }.toSet()
        dir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
        memory.keys.retainAll(usedSignatures.toSet())
    }

    fun clear(context: Context) {
        memory.clear()
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
