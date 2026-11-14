package com.example.albumar

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import java.io.File

class GenerateActivity : ComponentActivity() {
    private lateinit var nameEt: EditText
    private lateinit var photoWEt: EditText
    private lateinit var coverWEt: EditText
    private lateinit var compressCb: CheckBox
    private lateinit var albumSp: Spinner
    private lateinit var info: TextView
    private lateinit var linksBox: LinearLayout
    private var albumNames = listOf<String>()
    private var exportName: String? = null

    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::onCover) }
    private val pickPhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { onFiles(it, true) }
    private val pickVideos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { onFiles(it, false) }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val n = exportName
        if (uri != null && n != null) Thread {
            val ok = try { AlbumStore.exportZip(this, AlbumStore.load(this, n), uri); true } catch (e: Exception) { false }
            runOnUiThread { toast(if (ok) "Альбом экспортирован" else "Ошибка экспорта") }
        }.start()
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread {
            val n = try { AlbumStore.importZip(this, uri) } catch (e: Exception) { null }
            runOnUiThread {
                if (n == null) toast("Не удалось импортировать") else {
                    toast("Импортирован: $n"); reloadAlbums(); nameEt.setText(n); refreshAll()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F5F5F5")); addView(col) })

        col.addView(label("Генерация альбома", 24f, true))

        // ---- управление альбомами
        col.addView(label("Мои альбомы", 18f, true))
        albumSp = Spinner(this)
        albumSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) { nameEt.setText(albumNames[pos - 1]); refreshAll() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(albumSp)
        col.addView(row(
            smallButton("Переименовать") { renameAlbum() },
            smallButton("Удалить") { deleteAlbum() }))
        col.addView(row(
            smallButton("Экспорт (zip)") { exportAlbum() },
            smallButton("Импорт (zip)") { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }))

        col.addView(label("Название альбома (новый или существующий):"))
        nameEt = EditText(this).apply { setSingleLine(); hint = "например: Свадьба2024" }
        col.addView(nameEt)
        col.addView(smallButton("Открыть / создать альбом") { refreshAll() })

        col.addView(label("Ширина фото страниц, см (0 = определять автоматически):"))
        photoWEt = EditText(this).apply { setText("10"); inputType = 8194 }
        col.addView(photoWEt)
        col.addView(label("Ширина фото титула, см (0 = автоматически):"))
        coverWEt = EditText(this).apply { setText("15"); inputType = 8194 }
        col.addView(coverWEt)
        col.addView(smallButton("Сохранить размеры") { if (saveSettings() != null) { toast("Сохранено"); refreshLinks() } })

        col.addView(label("1. Фото титула альбома", 18f, true))
        col.addView(smallButton("Загрузить фото титула") { if (albumName() != null) pickCover.launch("image/*") })

        col.addView(label("2. Фото и видео в подпапки альбома", 18f, true))
        col.addView(smallButton("Добавить фото (папка photos)") { if (albumName() != null) pickPhotos.launch("image/*") })
        compressCb = CheckBox(this).apply { text = "Сжимать видео больше 20 МБ до 720p"; isChecked = true }
        col.addView(compressCb)
        col.addView(smallButton("Добавить видео (папка videos)") { if (albumName() != null) pickVideos.launch("video/*") })

        col.addView(label("3. Связать фото с видео", 18f, true))
        col.addView(smallButton("Связать по порядку (1-е фото → 1-е видео…)") { autoLink() })
        linksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(linksBox)
        info = label("")
        col.addView(info)
        reloadAlbums()
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun albumName(): String? {
        val n = AlbumStore.safeName(nameEt.text.toString())
        if (n.isEmpty()) { toast("Введите название альбома"); return null }
        return n
    }

    private fun reloadAlbums() {
        albumNames = AlbumStore.listAlbums(this).map { it.name }
        albumSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("— выберите альбом —") + albumNames)
    }

    private fun existingAlbum(): String? {
        val n = albumName() ?: return null
        if (n !in albumNames) { toast("Такого альбома ещё нет"); return null }
        return n
    }

    // ---- пункт 1: управление альбомами
    private fun renameAlbum() {
        val old = existingAlbum() ?: return
        val et = EditText(this).apply { setText(old) }
        AlertDialog.Builder(this).setTitle("Новое название").setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (AlbumStore.rename(this, old, et.text.toString())) {
                    nameEt.setText(AlbumStore.safeName(et.text.toString())); reloadAlbums(); refreshAll()
                } else toast("Не удалось (имя занято или пустое)")
            }.setNegativeButton("Отмена", null).show()
    }

    private fun deleteAlbum() {
        val n = existingAlbum() ?: return
        AlertDialog.Builder(this).setTitle("Удалить альбом «$n»?")
            .setMessage("Будут удалены все фото, видео и связи. Это необратимо.")
            .setPositiveButton("Удалить") { _, _ ->
                AlbumStore.delete(this, n); nameEt.setText(""); reloadAlbums(); linksBox.removeAllViews(); info.text = ""
            }.setNegativeButton("Отмена", null).show()
    }

    private fun exportAlbum() {
        val n = existingAlbum() ?: return
        exportName = n
        exportLauncher.launch("$n.zip")
    }

    private fun saveSettings(): Album? {
        val n = albumName() ?: return null
        val a = AlbumStore.load(this, n)
        a.photoWidthCm = photoWEt.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        a.coverWidthCm = coverWEt.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        AlbumStore.save(a)
        return a
    }

    private fun refreshAll() {
        val n = albumName() ?: return
        val a = AlbumStore.load(this, n)
        photoWEt.setText(a.photoWidthCm.toString()); coverWEt.setText(a.coverWidthCm.toString())
        refreshLinks()
    }

    private fun onCover(uri: Uri) {
        val a = saveSettings() ?: return
        Thread {
            try { AlbumStore.copyUri(contentResolver, uri, a.dir, "cover.jpg") } catch (e: Exception) { runOnUiThread { toast("Ошибка: ${e.message}") }; return@Thread }
            val q = Quality.analyze(a.coverFile)
            runOnUiThread {
                toast(if (q != null && q.score < 40) "Титул плохо подходит: ${q.hint}" else "Титул сохранён")
                reloadAlbums(); refreshLinks()
            }
        }.start()
    }

    // ---- пункт 3: сжатие видео, пункт 4: проверка фото
    private fun onFiles(uris: List<Uri>, photos: Boolean) {
        if (uris.isEmpty()) return
        val a = saveSettings() ?: return
        toast("Копирование…")
        Thread {
            val saved = ArrayList<File>()
            try { uris.forEach { saved += AlbumStore.copyUri(contentResolver, it, if (photos) a.photosDir else a.videosDir) } }
            catch (e: Exception) { runOnUiThread { toast("Ошибка: ${e.message}") } }
            val bad = if (photos) saved.count { (Quality.analyze(it)?.score ?: 0) < 40 } else 0
            runOnUiThread {
                toast("Добавлено: ${saved.size}" + if (bad > 0) ". Плохо распознаются: $bad (см. список ниже)" else "")
                reloadAlbums(); refreshLinks()
                if (!photos && compressCb.isChecked) compressNext(saved.filter { it.length() > 20L * 1024 * 1024 }.toMutableList())
            }
        }.start()
    }

    private fun compressNext(queue: MutableList<File>) {
        if (queue.isEmpty()) { refreshLinks(); return }
        val src = queue.removeAt(0)
        toast("Сжатие видео: ${src.name}…")
        val tmp = File(src.parentFile, "tmp_${System.currentTimeMillis()}.mp4")
        val dest = File(src.parentFile, src.nameWithoutExtension + ".mp4")
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(720)))).build()
        try {
            Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (tmp.exists() && tmp.length() in 1 until src.length()) { src.delete(); tmp.renameTo(dest); toast("Сжато: ${dest.name}") }
                        else tmp.delete()
                        compressNext(queue)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, e: ExportException) {
                        tmp.delete(); toast("Не удалось сжать ${src.name}, оставлен оригинал"); compressNext(queue)
                    }
                }).build().start(item, tmp.absolutePath)
        } catch (e: Exception) { tmp.delete(); compressNext(queue) }
    }

    private fun autoLink() {
        val a = saveSettings() ?: return
        val v = a.videos()
        a.photos().forEachIndexed { i, p -> if (i < v.size) a.links[p] = v[i] }
        AlbumStore.save(a); refreshLinks()
    }

    private fun refreshLinks() {
        linksBox.removeAllViews()
        val n = AlbumStore.safeName(nameEt.text.toString()); if (n.isEmpty()) return
        val a = AlbumStore.load(this, n)
        val cq = if (a.coverFile.exists()) Quality.analyze(a.coverFile) else null
        info.text = "Титул: ${cq?.text ?: "НЕТ"}\nФото: ${a.photos().size} • видео: ${a.videos().size}"
        val videos = a.videos()
        val items = listOf("— нет —") + videos
        for (p in a.photos()) {
            val pf = File(a.photosDir, p)
            val q = Quality.analyze(pf)
            linksBox.addView(label(p, 12f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(80))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapUtil.load(pf, 160))
            })
            val sp = Spinner(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                adapter = ArrayAdapter(this@GenerateActivity, android.R.layout.simple_spinner_dropdown_item, items)
                setSelection(items.indexOf(a.links[p]).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(par: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val vid = if (pos == 0) null else videos[pos - 1]
                        if (a.links[p] != vid) { if (vid == null) a.links.remove(p) else a.links[p] = vid; AlbumStore.save(a) }
                    }
                    override fun onNothingSelected(par: AdapterView<*>?) {}
                }
            }
            row.addView(sp)
            row.addView(Button(this).apply {
                text = "✕"
                setOnClickListener {
                    AlertDialog.Builder(this@GenerateActivity).setTitle("Удалить фото «$p»?")
                        .setPositiveButton("Удалить") { _, _ -> pf.delete(); a.links.remove(p); AlbumStore.save(a); refreshLinks() }
                        .setNegativeButton("Отмена", null).show()
                }
            })
            linksBox.addView(row)
            if (q != null) linksBox.addView(TextView(this).apply { text = q.text; textSize = 12f; setTextColor(q.color) })
        }
    }
}
