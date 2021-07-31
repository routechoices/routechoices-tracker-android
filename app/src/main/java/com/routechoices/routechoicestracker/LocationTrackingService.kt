package com.routechoices.routechoicestracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.*
// import android.util.Log
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject


class LocationTrackingService : Service() {
    inner class MyBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    private val binder by lazy { MyBinder() }
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private var deviceId = ""
    private var bufferLat = ""
    private var bufferLon = ""
    private var bufferTs = ""
    var lastLocationTS = -1
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private val locationRequest: LocationRequest = LocationRequest()
    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult?) {
            locationResult ?: return
            for (location in locationResult.locations){
                // Log.d("DEBUG", "Location received")
                if(location.accuracy <= 50) {
                    val timestamp = (location.time / 1e3).toInt()
                    lastLocationTS = timestamp
                    val latitude = location.latitude;
                    val longitude = location.longitude
                    storeToBuffer(timestamp, latitude, longitude)
                } else {
                    // Log.d("DEBUG", "Inaccurate location skip")
                }
            }
        }
    }
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            super.onStartCommand(intent, flags, startId)
            deviceId = intent?.getExtras()?.get("devId").toString()
            val action = intent?.getExtras()?.get("action").toString()
            if(action == "stop") {
                stopService()
            } else {
                startService()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val notification = createNotification()
        startForeground(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, LocationTrackingService::class.java).also {
            it.setPackage(packageName)
        };
        restartServiceIntent.putExtra("devId", deviceId)
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT);
        applicationContext.getSystemService(Context.ALARM_SERVICE);
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager;
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent);
    }

    private fun startService() {
        if (isServiceStarted) return
        isServiceStarted = true
        setServiceState(this, ServiceState.STARTED)
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoutechoicesTracker::lock").apply {
                    acquire()
                }
            }

        if (fusedLocationClient == null)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this) as FusedLocationProviderClient

        locationRequest.interval = 1000
        locationRequest.fastestInterval = 500
        locationRequest.smallestDisplacement = 0f
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        try {
            fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())
        } catch (e: SecurityException) {
            // Log.e(TAG, "Fail to request location update", e)
        } catch (e: IllegalArgumentException) {
            // Log.e(TAG, "Network provider does not exist", e)
        }

        GlobalScope.launch(Dispatchers.IO) {
            while (isServiceStarted) {
                launch(Dispatchers.IO) {
                    flushBuffer()
                }
                delay(10 * 1000)
            }
        }

    }

    fun stopService() {
        super.onDestroy()
        if (fusedLocationClient != null)
            try {
                fusedLocationClient?.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                // Log.w(TAG, "Failed to remove location listeners")
            }
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            // Log.w(TAG, "Service stopped without being started: ${e.message}")
        }
        isServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
        flushBuffer()
    }
    private fun storeToBuffer(timestamp: Int, lat: Double, lng: Double) {
        bufferTs += "$timestamp,"
        bufferLat += "$lat,"
        bufferLon += "$lng,"
    }
    private fun flushBuffer() {
        if (bufferTs == "") return
        val url =
            "https://api.routechoices.com/locations"

        val queue = Volley.newRequestQueue(this)
        val params: JSONObject = JSONObject()
        params.put("device_id", deviceId)
        params.put("latitudes", bufferLat)
        params.put("longitudes", bufferLon)
        params.put("timestamps", bufferTs)

        val stringRequest = JsonObjectRequest(
            Request.Method.POST,
            url,
            params,
            Listener<JSONObject> { response ->
                onLocationSentResponse(response)
            },
            Response.ErrorListener { error ->
                val _error = error
                // Log.d("DEBUG", String(error.networkResponse.data))
            })

        queue.add(stringRequest)
    }

    private fun onLocationSentResponse(response: JSONObject){
        bufferTs = ""
        bufferLat = ""
        bufferLon = ""
    }

    companion object {
        val TAG = "LocationTrackingService"
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "Routechoices Tracker"

        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
            val channel = NotificationChannel(
                notificationChannelId,
                "Endless Service notifications channel",
                NotificationManager.IMPORTANCE_HIGH
            ).let {
                it.description = "Routechoices Tracker"
                it.enableLights(true)
                it.lightColor = Color.RED
                it.enableVibration(true)
                it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            notificationChannelId
        ) else Notification.Builder(this)

        return builder
            .setContentTitle("Routechoices Tracker")
            .setContentText("Live GPS Tracking is on")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher_transparent)
            .setTicker("Ticker text")
            .setPriority(Notification.PRIORITY_HIGH) // for under android 26 compatibility
            .build()
    }

}