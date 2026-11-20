package com.ebike.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var pages: List<View>
    private lateinit var navs: List<TextView>
    private lateinit var banner: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvPct: TextView
    private lateinit var tvVolt: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvMsg: TextView
    private lateinit var seg: SegBar
    private lateinit var btnTrip: Button
    private lateinit var cellsView: CellsView
    private lateinit var tvCi: TextView
    private lateinit var tripsBox: LinearLayout
    private val stVals = ArrayList<TextView>()
    private var pending: (() -> Unit)? = null
    private var tripsShown = -1

    // map state
    private var me: Marker? = null
    private var range: Polygon? = null
    private var curLine: Polyline? = null
    private var curBucket = -1
    private val routeOv = ArrayList<Overlay>()
    private var liveDrawn = 0
    private var liveToken = -1
    private var viewing = false
    private var follow = true
    private var centered = false

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun tv(t: String, sp: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(col)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun bg(col: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(col); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun btn(t: String, filled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        setTextColor(if (filled) BG else INK)
        background = if (filled) bg(INK, 12) else bg(Color.TRANSPARENT, 12, INK)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(16))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(CARD, 14, OFF)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(applicationContext)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osm", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osm")
            osmdroidTileCache = File(cacheDir, "osm/tiles")
        }
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = BG

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        banner = tv("", 16f, Color.WHITE, true).apply {
            setBackgroundColor(Color.parseColor("#C0392B")); gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10)); visibility = View.GONE
        }
        val frame = FrameLayout(this)
        pages = listOf(buildDash(), buildMap(), buildCells(), buildTrips())
        pages.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(CARD) }
        val names = listOf("اللوحة", "الخريطة", "الخلايا", "الرحلات")
        navs = names.mapIndexed { i, n ->
            tv(n, 15f, MUTE).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { show(i) }
            }
        }
        navs.forEach { nav.addView(it, lp(0, WRAP, 1f)) }

        root.addView(banner, lp(MATCH, WRAP))
        root.addView(frame, lp(MATCH, 0, 1f))
        root.addView(nav, lp(MATCH, WRAP))
        setContentView(root)
        show(0)
        refresh()
    }

    private fun show(i: Int) {
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        navs.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) OK else MUTE)
            t.typeface = if (k == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /* ---------------- Dashboard ---------------- */
    private fun statCell(label: String, unit: String): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        c.addView(tv(label, 12f, MUTE))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val v = tv("--", 26f, INK, true)
        stVals.add(v)
        r.addView(v)
        r.addView(tv(" $unit", 12f, MUTE))
        c.addView(r)
        return c
    }

    private fun buildDash(): View {
        val sv = ScrollView(this)
        val c = col()
        tvGps = tv("الـGPS متوقف", 12f, MUTE).apply { gravity = Gravity.CENTER }
        tvSpeed = tv("0", 96f, INK, true).apply { gravity = Gravity.CENTER }
        c.addView(tvGps, lp(MATCH, WRAP))
        c.addView(tvSpeed, lp(MATCH, WRAP))
        c.addView(tv("كم/س", 16f, MUTE).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))

        val bat = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvPct = tv("--%", 40f, INK, true)
        tvVolt = tv("-- فولت\n-- أمبير", 14f, MUTE)
        row.addView(tvPct, lp(0, WRAP, 1f))
        row.addView(tvVolt, lp(WRAP, WRAP))
        seg = SegBar(this)
        tvSub = tv("غير متصل بالـBMS", 13f, MUTE)
        bat.addView(row, lp(MATCH, WRAP))
        bat.addView(seg, lp(MATCH, dp(24)).apply { topMargin = dp(10) })
        bat.addView(tvSub, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        c.addView(bat, lp(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(10) })

        val items = listOf(
            "المتبقي تقريباً" to "كم", "المسافة" to "كم",
            "الاستهلاك" to "واط/كم", "الاستطاعة" to "واط",
            "متوسط السرعة" to "كم/س", "أعلى سرعة" to "كم/س"
        )
        for (r in 0 until 3) {
            val rr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 2) {
                val it = items[r * 2 + k]
                rr.addView(statCell(it.first, it.second), lp(0, WRAP, 1f))
            }
            c.addView(rr, lp(MATCH, WRAP))
        }

        val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        br.addView(btn("اتصال بالـBMS") { ensure { scanAndPick() } }, lp(0, WRAP, 1f).apply { marginEnd = dp(8) })
        btnTrip = btn("ابدأ الرحلة") { toggleTrip() }
        br.addView(btnTrip, lp(0, WRAP, 1f))
        c.addView(br, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        tvMsg = tv("", 13f, MUTE).apply { gravity = Gravity.CENTER }
        c.addView(tvMsg, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        sv.addView(c)
        return sv
    }

    private fun toggleTrip() {
        if (Store.rec) {
            Store.endTrip()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            toast("تم حفظ الرحلة في تبويب الرحلات")
        } else {
            ensure {
                svc("gps", null)
                Store.startTrip()
                viewing = false
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                refresh()
            }
        }
    }

    /* ---------------- Map ---------------- */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildMap(): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 30.0))
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_MOVE) follow = false; false }
        }
        c.addView(map, lp(MATCH, 0, 1f))
        val b = btn("موقعي المباشر") {
            viewing = false; follow = true; liveToken = -1
            ensure { svc("gps", null) }
            syncMap()
        }
        c.addView(b, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })
        return c
    }

    private fun bucketColor(bk: Int) = Color.HSVToColor(floatArrayOf(140f - bk * 28f, 0.75f, 0.85f))

    private fun addRoutePoint(p: Pt, prev: Pt?) {
        val bk = (Math.min(p.kmh, 39.9) / 8).toInt()
        if (curLine == null || bk != curBucket) {
            val pl = Polyline()
            pl.outlinePaint.color = bucketColor(bk)
            pl.outlinePaint.strokeWidth = 12f
            if (prev != null) pl.addPoint(GeoPoint(prev.lat, prev.lon))
            curLine = pl
            curBucket = bk
            routeOv.add(pl)
            map.overlays.add(pl)
        }
        curLine!!.addPoint(GeoPoint(p.lat, p.lon))
    }

    private fun clearRoute() {
        routeOv.forEach { map.overlays.remove(it) }
        routeOv.clear()
        curLine = null
        curBucket = -1
    }

    private fun showTrip(t: Trip) {
        viewing = true; follow = false
        clearRoute()
        for (i in t.p.indices) addRoutePoint(t.p[i], if (i > 0) t.p[i - 1] else null)
        show(1)
        map.invalidate()
        if (t.p.size > 1) {
            val pts = t.p.map { GeoPoint(it.lat, it.lon) }
            map.post { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 80) }
        }
    }

    private fun syncMap() {
        val s = Store
        if (!viewing) {
            if (liveToken != s.tripToken) { clearRoute(); liveToken = s.tripToken; liveDrawn = 0 }
            val path = s.path
            while (liveDrawn < path.size) {
                addRoutePoint(path[liveDrawn], if (liveDrawn > 0) path[liveDrawn - 1] else null)
                liveDrawn++
            }
        }
        if (s.hasFix) {
            val gp = GeoPoint(s.lat, s.lon)
            if (me == null) {
                me = Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) }
            }
            me!!.position = gp
            range?.let { map.overlays.remove(it) }
            if (s.bms != null && s.remKm() > 0) {
                val pg = Polygon()
                pg.setPoints(Polygon.pointsAsCircle(gp, s.remKm() * 1000.0))
                pg.fillPaint.color = Color.argb(30, 91, 214, 160)
                pg.outlinePaint.color = OK
                pg.outlinePaint.strokeWidth = 3f
                range = pg
                map.overlays.add(0, pg)
            }
            map.overlays.remove(me)
            map.overlays.add(me)
            if (!centered) { map.controller.setZoom(16.0); map.controller.setCenter(gp); centered = true }
            else if (follow && !viewing) map.controller.animateTo(gp)
        }
        map.invalidate()
    }

    /* ---------------- Cells ---------------- */
    private fun field(label: String, get: () -> Double, set: (Double) -> Unit): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(tv(label, 13f, MUTE).apply { setPadding(0, dp(10), 0, dp(4)) })
        val e = EditText(this).apply {
            setText(get().toString().removeSuffix(".0"))
            setTextColor(INK); textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = bg(CARD, 10, OFF)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            doAfterTextChanged { t -> t.toString().toDoubleOrNull()?.let { set(it); Store.saveCfg() } }
        }
        c.addView(e, lp(MATCH, WRAP))
        return c
    }

    private fun buildCells(): View {
        val sv = ScrollView(this)
        val c = col()
        cellsView = CellsView(this)
        c.addView(cellsView, lp(MATCH, WRAP))
        tvCi = tv("اتصل بالـBMS لعرض الخلايا", 13f, MUTE)
        c.addView(tvCi, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        c.addView(tv("حدود التنبيه والإعدادات", 16f, INK, true).apply { setPadding(0, dp(20), 0, 0) })
        c.addView(field("تنبيه عند نسبة أقل من (%)", { Store.socMin }, { Store.socMin = it }))
        c.addView(field("تنبيه عند حرارة أعلى من (°)", { Store.tempMax }, { Store.tempMax = it }))
        c.addView(field("تنبيه عند فرق الخلايا (مللي فولت)", { Store.deltaMv }, { Store.deltaMv = it }))
        c.addView(field("تنبيه عند تيار أعلى من (أمبير)", { Store.ampMax }, { Store.ampMax = it }))
        c.addView(field("استهلاك تقديري قبل أول 0.5 كم (واط/كم)", { Store.est }, { Store.est = it }))
        sv.addView(c)
        return sv
    }

    /* ---------------- Trips ---------------- */
    private fun buildTrips(): View {
        val sv = ScrollView(this)
        tripsBox = col()
        sv.addView(tripsBox)
        return sv
    }

    private fun renderTrips() {
        tripsBox.removeAllViews()
        if (Store.trips.isEmpty()) {
            tripsBox.addView(tv("لا توجد رحلات محفوظة بعد.", 14f, MUTE))
            return
        }
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        for (t in Store.trips) {
            val avg = if (t.secs > 0) Math.round(t.dist / (t.secs / 3600)) else 0
            val wk = if (t.dist > 0 && t.wh > 0) Math.round(t.wh / t.dist).toString() else "--"
            val bat = if (t.s0 >= 0 && t.s1 >= 0) "\nالبطارية ${t.s0}% ← ${t.s1}%" else ""
            val c = card()
            c.addView(tv(fmt.format(Date(t.t)), 15f, INK, true))
            c.addView(tv(
                String.format(Locale.US, "%.2f", t.dist) + " كم · " + Math.round(t.secs / 60) + " دقيقة حركة\n" +
                    "متوسط $avg · أعلى ${Math.round(t.max)} كم/س\nاستهلاك $wk واط/كم$bat", 13f, MUTE
            ).apply { setPadding(0, dp(4), 0, dp(8)) })
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(btn("عرض على الخريطة") { showTrip(t) }, lp(0, WRAP, 2f).apply { marginEnd = dp(6) })
            r.addView(btn("GPX", false) { exportGpx(t) }, lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
            r.addView(btn("حذف", false) {
                AlertDialog.Builder(this).setMessage("حذف هذه الرحلة؟")
                    .setPositiveButton("حذف") { _, _ -> Store.deleteTrip(t) }
                    .setNegativeButton("إلغاء", null).show()
            }, lp(0, WRAP, 1f))
            c.addView(r, lp(MATCH, WRAP))
            tripsBox.addView(c, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }
    }

    private fun exportGpx(t: Trip) {
        val sb = StringBuilder("<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"ebike\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>")
        t.p.forEach { sb.append("<trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"/>") }
        sb.append("</trkseg></trk></gpx>")
        val name = "trip-${t.id}.gpx"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(u)!!.use { it.write(sb.toString().toByteArray()) }
            } else {
                File(getExternalFilesDir(null), name).writeText(sb.toString())
            }
            toast("تم الحفظ في مجلد التنزيلات: $name")
        } catch (e: Exception) { toast("تعذّر التصدير") }
    }

    /* ---------------- Refresh ---------------- */
    private fun refresh() {
        val s = Store
        val b = s.bms
        tvSpeed.text = Math.round(s.speed).toString()
        tvGps.text = s.gpsMsg
        tvMsg.text = s.btMsg
        btnTrip.text = if (s.rec) "إنهاء وحفظ" else "ابدأ الرحلة"
        seg.n = b?.cells?.size ?: 16
        stVals[1].text = String.format(Locale.US, "%.2f", s.dist)
        stVals[4].text = (if (s.secs > 0) Math.round(s.dist / (s.secs / 3600)) else 0).toString()
        stVals[5].text = Math.round(s.maxKmh).toString()
        if (b != null) {
            val c = if (b.soc <= 15) LOW else if (b.soc <= 35) WARN else OK
            seg.on = Math.ceil(b.soc / 100.0 * seg.n).toInt()
            seg.color = c
            tvPct.text = "${b.soc}%"; tvPct.setTextColor(c)
            tvVolt.text = String.format(Locale.US, "%.2f فولت\n%.1f أمبير", b.v, b.a)
            tvSub.text = String.format(Locale.US, "المتبقي %.1f من %.0f أمبير ساعة", b.rem, b.cap)
            stVals[0].text = String.format(Locale.US, "%.1f", s.remKm())
            stVals[2].text = Math.round(s.whkm()).toString()
            stVals[3].text = Math.round(Math.abs(b.v * b.a)).toString()
            cellsView.cells = b.cells
            cellsView.requestLayout()
            cellsView.invalidate()
            tvCi.text = String.format(Locale.US, "فرق الخلايا %.0f مللي فولت · حرارة %.0f° و%.0f°", b.diff * 1000, b.t1, b.t2) +
                (if (b.cyc != null) " · دورات الشحن ${b.cyc}" else "") +
                "\n(الأزرق أعلى خلية، والبرتقالي أدنى خلية)"
        }
        seg.invalidate()
        if (s.alerts.isEmpty()) banner.visibility = View.GONE
        else { banner.text = s.alerts.joinToString(" · "); banner.visibility = View.VISIBLE }
        if (tripsShown != s.tripsVersion) { tripsShown = s.tripsVersion; renderTrips() }
        syncMap()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        Store.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Store.listener = null
        map.onPause()
    }

    @Deprecated("keep the service alive")
    override fun onBackPressed() { moveTaskToBack(true) }

    /* ---------------- Permissions, service, BLE scan ---------------- */
    private fun svc(action: String, mac: String?) {
        val i = Intent(this, TrackService::class.java).setAction(action)
        if (mac != null) i.putExtra("mac", mac)
        startForegroundService(i)
    }

    private fun ensure(then: () -> Unit) {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN)
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val miss = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isEmpty()) then() else {
            pending = then
            requestPermissions(miss.toTypedArray(), 7)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val p = pending
        pending = null
        if (loc && p != null) p() else toast("الأذونات مطلوبة لعمل التطبيق")
    }

    @SuppressLint("MissingPermission")
    private fun scanAndPick() {
        try {
            val ad = getSystemService(BluetoothManager::class.java).adapter
            if (ad == null || !ad.isEnabled) { toast("فعّل البلوتوث أولاً"); return }
            val found = LinkedHashMap<String, String>()
            val sc = ad.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val n = r.device.name ?: return
                    found[r.device.address] = n
                }
            }
            sc.startScan(cb)
            toast("جارٍ البحث عن الأجهزة…")
            Handler(Looper.getMainLooper()).postDelayed({
                try { sc.stopScan(cb) } catch (e: Exception) { }
                if (found.isEmpty()) {
                    toast("لم يُعثر على أجهزة. أغلق تطبيق JK BMS وأعد المحاولة")
                    return@postDelayed
                }
                val macs = found.keys.sortedBy { if (found[it]!!.startsWith("JK", true)) 0 else 1 }
                val items = macs.map { found[it] + "\n" + it }.toTypedArray<CharSequence>()
                AlertDialog.Builder(this).setTitle("اختر الـBMS").setItems(items) { _, i -> svc("ble", macs[i]) }.show()
            }, 6000)
        } catch (e: Exception) { toast("تعذّر البحث: " + e.message) }
    }
}
