package com.example.albumar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.File
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Режим «СМОТРЕТЬ».
 * В базе ARCore: титулы ВСЕХ альбомов + фото ТЕКУЩЕГО альбома (с назначенным видео).
 * Имена: "c|<альбом>" и "p|<альбом>|<файл фото>".
 * База кэшируется на диск (.imgdb), пересобирается только при изменении файлов альбома.
 * Ширина 0 см = ARCore сам оценивает физический размер фото.
 */
class ArActivity : ComponentActivity(), GLSurfaceView.Renderer {
    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var pauseBtn: Button
    private lateinit var muteBtn: Button
    private var session: Session? = null
    private var installRequested = false
    private var resumed = false

    private val bg = BackgroundRenderer()
    private val video = VideoQuad()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var viewportChanged = false
    private var vpW = 0
    private var vpH = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val m2 = FloatArray(16)

    @Volatile private var pendingDb: AugmentedImageDatabase? = null
    @Volatile private var pendingAlbum: String? = null
    private var loadingAlbum: String? = null
    private var activeAlbum: String? = null

    private var playing: String? = null
    private val disarmed = HashSet<String>()
    private val lastFull = HashMap<String, Long>()
    private val START_FRACTION = 0.7f
    private val CLOSE_FRACTION = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(12), dp(10), dp(12), dp(10)); textSize = 15f; gravity = Gravity.CENTER
            text = "Наведите камеру на титул альбома"
        }
        pauseBtn = smallButton("⏸ Пауза") {
            glView.queueEvent { val p = video.togglePause(); runOnUiThread { pauseBtn.text = if (p) "▶ Играть" else "⏸ Пауза" } }
        }
        muteBtn = smallButton("🔊 Звук вкл") {
            glView.queueEvent { val m = video.toggleMute(); runOnUiThread { muteBtn.text = if (m) "🔇 Звук выкл" else "🔊 Звук вкл" } }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            addView(pauseBtn); addView(muteBtn)
        }
        setContentView(FrameLayout(this).apply {
            addView(glView)
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
    }

    override fun onResume() { super.onResume(); startSession() }

    private fun startSession() {
        if (resumed) return
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true; return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
                }
                val s = Session(this)
                s.configure(Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                })
                session = s
                rebuildDb(null)
            } catch (e: Exception) {
                Toast.makeText(this, "ARCore недоступен: ${e.message}", Toast.LENGTH_LONG).show()
                finish(); return
            }
        }
        try { session?.resume() } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Камера недоступна", Toast.LENGTH_LONG).show(); session = null; return
        }
        glView.onResume()
        resumed = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val res = grantResults
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startSession()
        else { Toast.makeText(this, "Нужен доступ к камере", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onPause() {
        super.onPause()
        glView.queueEvent { video.stop(); playing = null }
        glView.onPause()
        session?.pause()
        resumed = false
    }

    override fun onDestroy() {
        session?.close(); session = null
        executor.shutdown()
        super.onDestroy()
    }

    // ---------- база изображений (с кэшем) ----------
    private fun rebuildDb(album: String?) {
        val s = session ?: return
        loadingAlbum = album
        setStatus("Подготовка альбома…")
        executor.execute {
            val src = ArrayList<Triple<String, File, Float>>()
            for (a in AlbumStore.listAlbums(this)) if (a.coverFile.exists()) src += Triple("c|${a.name}", a.coverFile, a.coverWidthCm)
            if (album != null) {
                val a = AlbumStore.load(this, album)
                for ((p, v) in a.links) {
                    val pf = File(a.photosDir, p)
                    if (pf.exists() && File(a.videosDir, v).exists()) src += Triple("p|$album|$p", pf, a.photoWidthCm)
                }
            }
            val sig = src.joinToString(";") { "${it.first}|${it.second.lastModified()}|${it.second.length()}|${it.third}" }.hashCode().toString()
            val key = (album ?: "_covers").hashCode()
            val dbF = File(cacheDir, "db_$key.imgdb"); val sigF = File(cacheDir, "db_$key.sig")

            var db: AugmentedImageDatabase? = null
            var skipped = 0
            if (dbF.exists() && sigF.exists()) {
                val l = sigF.readLines()
                if (l.firstOrNull() == sig) try {
                    db = dbF.inputStream().use { AugmentedImageDatabase.deserialize(s, it) }
                    skipped = l.getOrNull(1)?.toIntOrNull() ?: 0
                } catch (_: Exception) { db = null }
            }
            if (db == null) {
                val nd = AugmentedImageDatabase(s)
                for ((name, f, w) in src) {
                    try {
                        val bmp = BitmapUtil.load(f, 1024)
                        if (bmp == null) {
                            skipped++
                        } else if (w <= 0f) {
                            nd.addImage(name, bmp)
                        } else {
                            nd.addImage(name, bmp, w / 100f)
                        }
                    } catch (e: Exception) { skipped++ }
                }
                try { dbF.outputStream().use { nd.serialize(it) }; sigF.writeText("$sig\n$skipped") } catch (_: Exception) {}
                db = nd
            }
            pendingAlbum = album
            pendingDb = db
            if (skipped > 0) runOnUiThread { Toast.makeText(this, "Не распознаны (мало деталей): $skipped", Toast.LENGTH_LONG).show() }
        }
    }

    private fun setStatus(t: String) = runOnUiThread { status.text = t }
    private fun albumStatus() = if (activeAlbum == null) "Наведите камеру на титул альбома" else "Альбом: $activeAlbum. Наведите камеру на фото"

    // ---------- GL ----------
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.init(); video.init()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        vpW = width; vpH = height; viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        pendingDb?.let { db ->
            pendingDb = null
            try {
                if (db.numImages > 0) s.configure(Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    augmentedImageDatabase = db
                })
            } catch (e: Exception) { setStatus("Ошибка базы: ${e.message}") }
            video.stop(); playing = null; disarmed.clear(); lastFull.clear()
            activeAlbum = pendingAlbum; loadingAlbum = activeAlbum
            setStatus(if (db.numImages == 0) "Нет альбомов. Создайте их в режиме «ГЕНЕРИРОВАТЬ»" else albumStatus())
        }

        if (viewportChanged) {
            @Suppress("DEPRECATION") val rot = windowManager.defaultDisplay.rotation
            s.setDisplayGeometry(rot, vpW, vpH); viewportChanged = false
        }
        s.setCameraTextureName(bg.texId)
        val frame = try { s.update() } catch (e: Exception) { return }
        bg.draw(frame)

        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return
        cam.getProjectionMatrix(proj, 0, 0.05f, 100f)
        cam.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val now = System.currentTimeMillis()
        val full = HashMap<String, AugmentedImage>()
        val fr = HashMap<String, Float>()
        for (img in s.getAllTrackables(AugmentedImage::class.java)) {
            if (img.trackingState == TrackingState.TRACKING && img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                full[img.name] = img; fr[img.name] = visibleFraction(img); lastFull[img.name] = now
            }
        }
        disarmed.removeAll { (fr[it] ?: 0f) < 0.3f }

        // 1) титул нового альбома -> переключаемся
        for ((name, f) in fr) if (name.startsWith("c|") && f >= START_FRACTION) {
            val album = name.substring(2)
            if (album != activeAlbum && album != loadingAlbum) { rebuildDb(album); return }
        }

        // 2) закрытие видео при смещении > 50% (с плавным затуханием)
        playing?.let { p ->
            val f = fr[p]
            val lost = f == null && now - (lastFull[p] ?: 0L) > 1000
            if (lost || (f != null && f < CLOSE_FRACTION)) {
                video.fadeOut(); disarmed.add(p); playing = null; setStatus(albumStatus())
            }
        }

        // 3) запуск видео
        if (playing == null && !video.isActive) {
            val best = fr.filter { it.key.startsWith("p|") && it.key !in disarmed && it.value >= START_FRACTION }
                .maxByOrNull { it.value }?.key
            if (best != null) {
                val parts = best.split("|", limit = 3)
                val a = AlbumStore.load(this, parts[1])
                val v = a.links[parts[2]]
                if (v != null) {
                    video.play(File(a.videosDir, v).absolutePath); playing = best
                    setStatus("▶ Загрузка видео…")
                    runOnUiThread { pauseBtn.text = "⏸ Пауза" }
                }
            }
        }

        // 4) рисуем
        val p = playing
        val img = if (p != null) full[p] else null
        if (img != null) {
            img.centerPose.toMatrix(m2, 0)
            video.draw(vp, m2, img.extentX, img.extentZ)
        } else if (video.isActive) {
            video.draw(vp, null, 0f, 0f)   // затухание по последней известной позе
        }
    }

    private fun visibleFraction(img: AugmentedImage): Float {
        img.centerPose.toMatrix(model, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        val hx = img.extentX / 2; val hz = img.extentZ / 2
        val pts = arrayOf(floatArrayOf(-hx, 0f, -hz, 1f), floatArrayOf(hx, 0f, -hz, 1f),
            floatArrayOf(-hx, 0f, hz, 1f), floatArrayOf(hx, 0f, hz, 1f))
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val o = FloatArray(4)
        for (p in pts) {
            Matrix.multiplyMV(o, 0, mvp, 0, p, 0)
            if (o[3] <= 0.001f) return 0f
            val x = o[0] / o[3]; val y = o[1] / o[3]
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val area = (maxX - minX) * (maxY - minY)
        if (area <= 0f) return 0f
        val ix = maxOf(0f, minOf(maxX, 1f) - maxOf(minX, -1f))
        val iy = maxOf(0f, minOf(maxY, 1f) - maxOf(minY, -1f))
        return ix * iy / area
    }
}
