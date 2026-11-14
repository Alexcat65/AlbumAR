package com.example.albumar

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Структура на смартфоне (папка приложения, разрешения на память не нужны):
 *  Android/data/com.example.albumar/files/Albums/<Альбом>/
 *      cover.jpg   - фото титула
 *      photos/     - фото страниц
 *      videos/     - видео
 *      album.json  - ширины в см и связи фото -> видео
 */
class Album(val dir: File, val name: String) {
    var photoWidthCm = 10f
    var coverWidthCm = 15f
    val links = LinkedHashMap<String, String>()
    val coverFile get() = File(dir, "cover.jpg")
    val photosDir get() = File(dir, "photos")
    val videosDir get() = File(dir, "videos")
    fun photos(): List<String> =
        photosDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
    fun videos(): List<String> =
        videosDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
}

object AlbumStore {
    fun root(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Albums").also { it.mkdirs() }

    fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim()

    fun listAlbums(ctx: Context): List<Album> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.map { load(ctx, it.name) } ?: emptyList()

    fun load(ctx: Context, name: String): Album {
        val a = Album(File(root(ctx), name), name)
        a.photosDir.mkdirs(); a.videosDir.mkdirs()
        val f = File(a.dir, "album.json")
        if (f.exists()) try {
            val j = JSONObject(f.readText())
            a.photoWidthCm = j.optDouble("photoWidthCm", 10.0).toFloat()
            a.coverWidthCm = j.optDouble("coverWidthCm", 15.0).toFloat()
            j.optJSONObject("links")?.let { l -> l.keys().forEach { k -> a.links[k] = l.getString(k) } }
        } catch (_: Exception) { }
        return a
    }

    fun save(a: Album) {
        val links = JSONObject(); a.links.forEach { (k, v) -> links.put(k, v) }
        val j = JSONObject().put("photoWidthCm", a.photoWidthCm.toDouble())
            .put("coverWidthCm", a.coverWidthCm.toDouble()).put("links", links)
        File(a.dir, "album.json").writeText(j.toString(2))
    }


    fun rename(ctx: Context, old: String, new: String): Boolean {
        val n = safeName(new); if (n.isEmpty()) return false
        val t = File(root(ctx), n); if (t.exists()) return false
        return File(root(ctx), old).renameTo(t)
    }

    fun delete(ctx: Context, name: String) = File(root(ctx), name).deleteRecursively()

    /** Экспорт альбома в zip (папка альбома целиком). */
    fun exportZip(ctx: Context, a: Album, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { z ->
                a.dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry(a.name + "/" + f.relativeTo(a.dir).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
    }

    /** Импорт альбома из zip. Возвращает имя созданного альбома. */
    fun importZip(ctx: Context, uri: Uri): String? {
        val rootDir = root(ctx)
        var target: File? = null
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val parts = e.name.replace('\\', '/').split('/', limit = 2)
                    if (parts.size == 2 && !e.isDirectory) {
                        if (target == null) {
                            val base = safeName(parts[0]).ifEmpty { "Album" }
                            var n = base; var i = 2
                            while (File(rootDir, n).exists()) { n = "$base ($i)"; i++ }
                            target = File(rootDir, n)
                        }
                        val t = target!!
                        val out = File(t, parts[1])
                        if (out.canonicalPath.startsWith(t.canonicalPath + File.separator)) {   // защита от zip-slip
                            out.parentFile?.mkdirs()
                            out.outputStream().use { z.copyTo(it) }
                        }
                    }
                    e = z.nextEntry
                }
            }
        }
        return target?.name
    }

    fun copyUri(cr: ContentResolver, uri: Uri, dir: File, forcedName: String? = null): File {
        dir.mkdirs()
        var name = forcedName ?: queryName(cr, uri) ?: "file_${System.currentTimeMillis()}"
        name = safeName(name).ifEmpty { "file_${System.currentTimeMillis()}" }
        var out = File(dir, name)
        if (forcedName == null && out.exists()) out = File(dir, "${System.currentTimeMillis()}_$name")
        cr.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }

    private fun queryName(cr: ContentResolver, uri: Uri): String? =
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}

object BitmapUtil {
    /** Загрузка с уменьшением и учётом EXIF-поворота. */
    fun load(file: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val deg = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        return if (deg == 0f) bmp else
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }
}
