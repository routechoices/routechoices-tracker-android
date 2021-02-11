package com.routechoices.routechoicestracker

import android.app.*
import android.content.Intent
import android.content.Context
import android.util.Log
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.android.volley.Response.Listener
import com.google.android.gms.location.*
import org.json.JSONObject
import android.os.PowerManager
import android.os.Build
import android.graphics.Color

class LocationTrackingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceId = ""
    var fusedLocationClient: FusedLocationProviderClient? = null
    private val locationRequest: LocationRequest = LocationRequest()
    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult?) {
            locationResult ?: return
            for (location in locationResult.locations){
                Log.d("DEBUG", "Location received")
                if(location.accuracy < 25) {
                    val timestamp = (location.time / 1e3).toInt()
                    val latitude = location.latitude;
                    val longitude = location.longitude
                    sendLocation(timestamp, latitude, longitude)
                } else {
                    Log.d("DEBUG", "Inaccurate location skip")
                }
            }
        }
    }
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        deviceId = intent?.getExtras()?.get("devId").toString()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val _wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Routechoices:MyWakelockTag"
        )
        _wakeLock.acquire()
        wakeLock = _wakeLock
        return START_STICKY
    }

    override fun onCreate() {
        if (fusedLocationClient == null)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this) as FusedLocationProviderClient

        locationRequest.interval = 1000
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        try {
            fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Fail to request location update", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Network provider does not exist", e)
        }
        val notification = createNotification()
        startForeground(1, notification)
        setServiceState(this, ServiceState.STARTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (fusedLocationClient != null)
            try {
                fusedLocationClient?.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove location listeners")
            }
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        stopForeground(true)
        setServiceState(this, ServiceState.STOPPED)
    }

    private fun sendLocation(timestamp: Int, lat: Double, lng: Double) {
        val url = "https://www.routechoices.com/api/traccar?id=" + deviceId + "&timestamp=" + timestamp.toString() + "&lat=" + lat.toString() + "&lon=" + lng.toString()
        val queue = Volley.newRequestQueue(this)

        val stringRequest = JsonObjectRequest(
            Request.Method.POST, url, null,
            Listener<JSONObject> { response ->
                onLocationSentResponse(response)
            },
            Response.ErrorListener { error ->
                Log.d("DEBUG", String(error.networkResponse.data))
            })

        queue.add(stringRequest)
    }

    private fun onLocationSentResponse(response: JSONObject){
        Log.d("DEBUG", "Location Sent")
    }

    companion object {
        val TAG = "LocationTrackingService"
    }


    private fun createNotification(): Notification {
        val notificationChannelId = "RouteChoices Tracker"

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
            .setSmallIcon(R.mipmap.logo512x512_trans)
            .setTicker("Ticker text")
            .setPriority(Notification.PRIORITY_HIGH) // for under android 26 compatibility
            .build()
    }

}