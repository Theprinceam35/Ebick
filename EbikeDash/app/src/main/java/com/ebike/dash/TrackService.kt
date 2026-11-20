package com.ebike.dash

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.UUID

/** Foreground service: keeps GPS and the JK BMS Bluetooth link alive while the screen is off. */
class TrackService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var mac: String? = null
    private var lm: LocationManager? = null
    private var gpsOn = false
    private var alive = true
    private var lastRx = 0L
    private val fr = ArrayList<Int>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); Store.init(applicationContext) }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startFg()
        when (i?.action) {
            "stop" -> { shutdown(); stopSelf(); return START_NOT_STICKY }
            "gps" -> startGps()
            "ble" -> i.getStringExtra("mac")?.let { connect(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun msg(m: String) { h.post { Store.btMsg = m; Store.changed() } }

    private fun alertNow() {
        try {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 500)
        } catch (e: Exception) { }
    }
    private fun u(n: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", n))

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ebike", "Ebike", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "ebike")
            .setContentTitle("عدّاد البسكليت")
            .setContentText("يسجّل الموقع ويقرأ البطارية")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "إيقاف", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (e: SecurityException) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            }
        } else startForeground(1, n)
    }

    private fun shutdown() {
        alive = false
        h.removeCallbacksAndMessages(null)
        try { gatt?.close() } catch (e: Exception) { }
        gatt = null
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        gpsOn = false
        Store.gpsMsg = "الـGPS متوقف"
        Store.btMsg = "الخدمة متوقفة"
        Store.changed()
    }

    /* ---------- GPS ---------- */
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            Store.onLocation(l.latitude, l.longitude, l.accuracy, if (l.hasSpeed()) l.speed else null, l.time)
        }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) { Store.gpsMsg = "الـGPS مغلق من إعدادات الهاتف"; Store.changed() }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn) return
        try {
            lm = getSystemService(LocationManager::class.java)
            lm!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            gpsOn = true
        } catch (e: Exception) { msg("تعذّر تشغيل الـGPS") }
    }

    /* ---------- JK BMS over BLE ---------- */
    @SuppressLint("MissingPermission")
    private fun connect(m: String) {
        mac = m
        alive = true
        try {
            gatt?.close()
            val ad = getSystemService(BluetoothManager::class.java).adapter
            gatt = ad.getRemoteDevice(m).connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
            msg("جارٍ الاتصال بالـBMS…")
        } catch (e: Exception) { msg("تعذّر الاتصال بالبلوتوث") }
    }

    @SuppressLint("MissingPermission")
    private fun send(c: Int) {
        val g = gatt ?: return
        val x = ch ?: return
        val f = ByteArray(20)
        f[0] = 0xAA.toByte(); f[1] = 0x55; f[2] = 0x90.toByte(); f[3] = 0xEB.toByte(); f[4] = c.toByte()
        var s = 0
        for (k in 0 until 19) s += f[k].toInt() and 255
        f[19] = (s and 255).toByte()
        x.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        x.value = f
        @Suppress("DEPRECATION")
        g.writeCharacteristic(x)
    }

    private val wd = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastRx > 4000) { lastRx = System.currentTimeMillis(); send(0x96) }
            h.postDelayed(this, 1000)
        }
    }

    private fun startRead() {
        lastRx = System.currentTimeMillis()
        send(0x97)
        h.postDelayed({ send(0x96) }, 500)
        h.removeCallbacks(wd)
        h.postDelayed(wd, 1000)
    }

    private fun onChunk(d: ByteArray) {
        lastRx = System.currentTimeMillis()
        if (d.size >= 4 && d[0] == 0x55.toByte() && d[1] == 0xAA.toByte() && d[2] == 0xEB.toByte() && d[3] == 0x90.toByte()) fr.clear()
        for (x in d) fr.add(x.toInt() and 255)
        if (fr.size >= 300) {
            if (fr[4] == 2) {
                val a = ByteArray(300) { fr[it].toByte() }
                h.post { if (Store.onFrame(a)) alertNow() }
            }
            fr.clear()
        }
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                msg("متصل، جارٍ تجهيز القراءة…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                msg("انقطع الاتصال، إعادة المحاولة…")
                g.close()
                if (gatt === g) gatt = null
                val m = mac
                if (m != null && alive) h.postDelayed({ if (alive) connect(m) }, 3000)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(u(0xFFE0))?.getCharacteristic(u(0xFFE1))
            if (c == null) { msg("لم أجد خدمة JK على هذا الجهاز"); return }
            ch = c
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(u(0x2902))
            if (d == null) { startRead(); return }
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { startRead() }

        @Deprecated("pre-33 signature, still called on all versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { onChunk(it) }
        }
    }
}
