package com.routechoices.routechoicestracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.util.Log
import com.android.volley.RequestQueue
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.os.BatteryManager

import android.content.IntentFilter


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
    var lastGpsDataTs = -1
    private var requestQueue: RequestQueue? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private val locationRequest: LocationRequest = LocationRequest.create()
    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            Log.d("DEBUG", "location callback")
            for (location in locationResult.locations){
                Log.d("DEBUG", "Location received")
                val timestamp = (location.time / 1e3).toInt()
                lastGpsDataTs = timestamp
                if(location.accuracy <= 50) {
                    Log.d("DEBUG", "Location accuracy correct")
                    lastLocationTS = timestamp
                    val latitude = location.latitude;
                    val longitude = location.longitude
                    storeToBuffer(timestamp, latitude, longitude)
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
            deviceId = intent.getExtras()?.get("devId").toString()
            val action = intent.getExtras()?.get("action").toString()
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
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_ONE_SHOT
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, flag);
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
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest.interval = 1000
        locationRequest.fastestInterval = 500
        locationRequest.smallestDisplacement = 0f
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.myLooper()
            )
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
                delay(5 * 1000)
            }
        }

    }

    fun stopService() {
        if (fusedLocationClient != null)
            try {
                fusedLocationClient?.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove location listeners")
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

    private fun getBatteryPercentage(): Int {
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, iFilter)
        val level = batteryStatus!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus!!.getIntExtra(BatteryManager.EXTRA_SCALE, 1)
        val batteryPct = level / scale.toFloat()
        return (batteryPct * 100).toInt()
    }

    private fun flushBuffer() {
        Log.d("DEBUG", "Flush")
        if (bufferTs == "") return
        val url =
            "https://api.routechoices.com/locations/"

        val params: JSONObject = JSONObject()
        params.put("device_id", deviceId)
        params.put("latitudes", bufferLat)
        params.put("longitudes", bufferLon)
        params.put("timestamps", bufferTs)
        val batteryPctInt = getBatteryPercentage()
        if (batteryPctInt in 0..100) {
            val batteryPct: String = getBatteryPercentage().toString()
            params.put("battery", batteryPct)
        }
        val stringRequest = object: JsonObjectRequest(
            Request.Method.POST,
            url,
            params,
            Response.Listener<JSONObject> { response ->
                onLocationSentResponse(response)
            },
            Response.ErrorListener { error ->
                Log.e(TAG, "Error flushing to API => $error")
            }) {
            override fun getHeaders(): Map<String, String> {
                val headers = HashMap<String, String>()
                headers["Content-Type"] = "application/json"
                val auth = "Bearer " + BuildConfig.POST_LOCATION_SECRET
                headers["Authorization"] = auth
                return headers
            }
        }
        if(requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this)
        }
        requestQueue!!.add(stringRequest)
    }

    private fun onLocationSentResponse(response: JSONObject){
        bufferTs = ""
        bufferLat = ""
        bufferLon = ""
        Log.d("DEBUG", "Buffer flushed")
    }

    companion object {
        val TAG = "LocationTrackingService"
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "Routechoices Tracker"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        val channel = NotificationChannel(
            notificationChannelId,
            "Routechoices Tracker Service notifications channel",
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


        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        }

        val builder: Notification.Builder = Notification.Builder(
            this,
            notificationChannelId
        )

        return builder
            .setContentTitle("Routechoices Tracker")
            .setContentText(resources.getText(R.string.liveIsOn))
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher_transparent)
            .setTicker("Ticker text")
            .build()
    }

}