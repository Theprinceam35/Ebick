package com.ebike.dash

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Pt(val lat: Double, val lon: Double, val kmh: Double)
class Fix(val lat: Double, val lon: Double, val t: Long)
class Bms(
    val v: Double, val a: Double, val t1: Double, val t2: Double, val soc: Int,
    val rem: Double, val cap: Double, val cells: List<Double>, val diff: Double, val cyc: Long?
)
class Trip(
    val id: Long, val t: Long, val dist: Double, val secs: Double, val max: Double,
    val wh: Double, val s0: Int, val s1: Int, val p: List<Pt>
)

private fun hav(a: Fix, b: Fix): Double {
    val r = Math.PI / 180
    val dl = (b.lat - a.lat) * r
    val dn = (b.lon - a.lon) * r
    val x = sin(dl / 2) * sin(dl / 2) + cos(a.lat * r) * cos(b.lat * r) * sin(dn / 2) * sin(dn / 2)
    return 2 * 6371000.0 * asin(sqrt(x))
}

/** Reads the JK BMS 300-byte cell-info frame. Finds the voltage field by matching it with the sum of cells. */
fun parseBms(b: ByteArray): Bms? {
    if (b.size < 300) return null
    fun u16(o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    fun u32(o: Int): Long = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
    val cells = ArrayList<Double>()
    var sum = 0.0
    for (i in 0 until 32) {
        val c = u16(6 + 2 * i) / 1000.0
        if (c < 1.5 || c > 5) break
        cells.add(c)
        sum += c
    }
    if (cells.isEmpty()) return null
    var p = -1
    var q = 100
    while (q < b.size - 40 && p < 0) {
        val t = u32(q) / 1000.0
        if (abs(t - sum) < sum * 0.03) p = q
        q++
    }
    if (p < 0) return null
    val soc = b[p + 23].toInt() and 255
    if (soc > 100) return null
    val cy = u32(p + 32)
    return Bms(
        v = u32(p) / 1000.0,
        a = u32(p + 8).toInt() / 1000.0,
        t1 = u16(p + 12).toShort() / 10.0,
        t2 = u16(p + 14).toShort() / 10.0,
        soc = soc,
        rem = u32(p + 24) / 1000.0,
        cap = u32(p + 28) / 1000.0,
        cells = cells,
        diff = cells.max() - cells.min(),
        cyc = if (cy < 100000) cy else null
    )
}

/** All app state. Touched only on the main thread. */
object Store {
    private lateinit var sp: SharedPreferences
    private var inited = false
    private val mh = Handler(Looper.getMainLooper())
    private var pend = false
    var listener: (() -> Unit)? = null

    var bms: Bms? = null
    var btMsg = ""
    var gpsMsg = "الـGPS متوقف"
    var speed = 0.0
    var hasFix = false
    var lat = 0.0
    var lon = 0.0

    var rec = false
    var dist = 0.0
    var secs = 0.0
    var maxKmh = 0.0
    var t0 = 0L
    var tripToken = 0
    var tripsVersion = 0
    var path = ArrayList<Pt>()
    private val hist = ArrayList<DoubleArray>()
    private var startAh: Double? = null
    private var startSoc = -1
    private var last: Fix? = null
    private var prev: Fix? = null

    var alerts: List<String> = emptyList()
    private var alertKey = ""
    var trips = ArrayList<Trip>()

    var est = 18.0
    var socMin = 20.0
    var tempMax = 45.0
    var deltaMv = 100.0
    var ampMax = 50.0

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        sp = ctx.getSharedPreferences("ebike", Context.MODE_PRIVATE)
        est = sp.getFloat("est", 18f).toDouble()
        socMin = sp.getFloat("socMin", 20f).toDouble()
        tempMax = sp.getFloat("tempMax", 45f).toDouble()
        deltaMv = sp.getFloat("deltaMv", 100f).toDouble()
        ampMax = sp.getFloat("ampMax", 50f).toDouble()
        loadTrips()
    }

    fun saveCfg() {
        sp.edit().putFloat("est", est.toFloat()).putFloat("socMin", socMin.toFloat())
            .putFloat("tempMax", tempMax.toFloat()).putFloat("deltaMv", deltaMv.toFloat())
            .putFloat("ampMax", ampMax.toFloat()).apply()
    }

    fun changed() {
        if (pend) return
        pend = true
        mh.postDelayed({ pend = false; listener?.invoke() }, 300)
    }

    /* ---- BMS ---- */
    fun onFrame(b: ByteArray): Boolean {
        val x = parseBms(b)
        if (x == null) {
            btMsg = "تعذّر فهم إطار البيانات من هذا الموديل"
            changed()
            return false
        }
        bms = x
        btMsg = ""
        if (startAh == null) { startAh = x.rem; startSoc = x.soc }
        if (rec) hist.add(doubleArrayOf(dist, x.rem))
        val fresh = checkAlerts(x)
        changed()
        return fresh
    }

    private fun checkAlerts(b: Bms): Boolean {
        val a = ArrayList<String>()
        if (b.soc <= socMin) a.add("البطارية منخفضة ${b.soc}%")
        if (max(b.t1, b.t2) >= tempMax) a.add("حرارة مرتفعة")
        if (b.diff * 1000 >= deltaMv) a.add("اختلال الخلايا")
        if (abs(b.a) >= ampMax) a.add("تيار مرتفع")
        alerts = a
        val k = a.joinToString("|")
        val fresh = k.isNotEmpty() && k != alertKey
        alertKey = k
        return fresh
    }

    /* ---- GPS ---- */
    fun onLocation(la: Double, lo: Double, acc: Float, spd: Float?, t: Long) {
        gpsMsg = "دقة الـGPS: " + Math.round(acc) + " م"
        if (acc > 40) { changed(); return }
        val cur = Fix(la, lo, t)
        var v: Double? = spd?.toDouble()
        val pv = prev
        if (v == null && pv != null) {
            val dt = (t - pv.t) / 1000.0
            if (dt > 0) v = hav(pv, cur) / dt
        }
        prev = cur
        if (rec) {
            val l = last
            if (l == null) {
                last = cur
                path.add(Pt(la, lo, 0.0))
            } else {
                val d = hav(l, cur)
                if (d > max(3.0, acc * 0.25) && (v ?: 0.0) > 0.7) {
                    val k = (v ?: 0.0) * 3.6
                    dist += d / 1000
                    secs += (t - l.t) / 1000.0
                    maxKmh = max(maxKmh, k)
                    path.add(Pt(la, lo, k))
                    last = cur
                }
            }
        }
        val s = (v ?: 0.0) * 3.6
        speed = if (s < 2) 0.0 else s
        lat = la; lon = lo; hasFix = true
        changed()
    }

    /* ---- trips ---- */
    fun startTrip() {
        rec = true; dist = 0.0; secs = 0.0; maxKmh = 0.0; last = null; t0 = System.currentTimeMillis()
        path = ArrayList(); hist.clear()
        startAh = bms?.rem; startSoc = bms?.soc ?: -1
        tripToken++
        changed()
    }

    fun endTrip() {
        rec = false
        if (dist >= 0.05) {
            val b = bms
            val sa = startAh
            val wh = if (b != null && sa != null) max(0.0, (sa - b.rem) * b.v) else 0.0
            trips.add(0, Trip(System.currentTimeMillis(), t0, dist, secs, maxKmh, wh, startSoc, b?.soc ?: -1, path.toList()))
            while (trips.size > 50) trips.removeAt(trips.size - 1)
            saveTrips()
            tripsVersion++
        }
        changed()
    }

    fun deleteTrip(t: Trip) { trips.remove(t); saveTrips(); tripsVersion++; changed() }

    fun whkm(): Double {
        val b = bms
        if (b != null && rec && dist >= 0.5 && hist.isNotEmpty()) {
            var h = hist[0]
            for (e in hist) if (e[0] >= dist - 5) { h = e; break }
            val dd = dist - h[0]
            val u = (h[1] - b.rem) * b.v
            if (dd >= 0.5 && u > 0) return u / dd
        }
        return if (est > 0) est else 18.0
    }

    fun remKm(): Double { val b = bms ?: return 0.0; return b.rem * b.v / whkm() }

    private fun saveTrips() {
        val arr = JSONArray()
        for (t in trips) {
            val o = JSONObject()
            o.put("id", t.id); o.put("t", t.t); o.put("dist", t.dist); o.put("secs", t.secs)
            o.put("max", t.max); o.put("wh", t.wh); o.put("s0", t.s0); o.put("s1", t.s1)
            val pa = JSONArray()
            for (q in t.p) {
                pa.put(Math.round(q.lat * 1e5) / 1e5); pa.put(Math.round(q.lon * 1e5) / 1e5); pa.put(Math.round(q.kmh).toDouble())
            }
            o.put("p", pa)
            arr.put(o)
        }
        sp.edit().putString("trips", arr.toString()).apply()
    }

    private fun loadTrips() {
        try {
            val arr = JSONArray(sp.getString("trips", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pa = o.getJSONArray("p")
                val pts = ArrayList<Pt>()
                var j = 0
                while (j + 2 < pa.length()) { pts.add(Pt(pa.getDouble(j), pa.getDouble(j + 1), pa.getDouble(j + 2))); j += 3 }
                trips.add(Trip(o.getLong("id"), o.getLong("t"), o.getDouble("dist"), o.getDouble("secs"),
                    o.getDouble("max"), o.getDouble("wh"), o.getInt("s0"), o.getInt("s1"), pts))
            }
        } catch (e: Exception) { }
    }
}
