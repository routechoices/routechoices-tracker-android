package com.routechoices.routechoicestracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.util.Log
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.*
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject

const val LOCATION_PERMISSION: Int = 0

class MainActivity : AppCompatActivity() {

    private var deviceId = ""
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationRequest: LocationRequest = LocationRequest()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceIdTextView.setText("Fetching device id")
        this.fetchDeviceId()


        while (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), LOCATION_PERMISSION
            )

            Log.d("DEBUG", "No access to location")
        }

        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        this.locationRequest.interval = 1000
        this.locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        val locationCallback: LocationCallback = object : LocationCallback() {
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
                        Log.d("DEBUG", "Inacurate location skip")
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest,
            locationCallback,
            null /* Looper */)
    }

    private fun sendLocation(timestamp: Int, lat: Double, lng: Double) {
        val url = "https://www.routechoices.com/api/traccar?id=" + this.deviceId + "&timestamp=" + timestamp.toString() + "&lat=" + lat.toString() + "&lon=" + lng.toString()
        val queue = Volley.newRequestQueue(this)

        val stringRequest = JsonObjectRequest(Request.Method.POST, url, null,
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

    private fun fetchDeviceId() {

        Log.d("DEBUG", "Started to fetch device id")

        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        this.deviceId = sharedPref.getString("deviceId", "")

        if (this.deviceId == "") {
            this.requestDeviceId()
        } else {
            this.deviceIdTextView.text = this.deviceId
        }

    }

    private fun requestDeviceId() {
        Log.d("DEBUG", "Requesting device id")
        val queue = Volley.newRequestQueue(this)
        val url = "https://www.routechoices.com/api/device_id"

        val stringRequest = JsonObjectRequest(Request.Method.POST, url, null,
            Listener<JSONObject> { response ->
                // Display the first 500 characters of the response string.
                onDeviceIdResponse(response)
            },
            Response.ErrorListener { error ->
                Log.d("DEBUG", error.toString())
                this.deviceIdTextView.text = "Oh fuck"
            })

        queue.add(stringRequest)
    }

    private fun onDeviceIdResponse(response: JSONObject) {
        this.setDeviceId(response.getString("device_id"))
    }

    private fun setDeviceId(deviceId: String) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putString("deviceId", deviceId)
            commit()
        }

        this.deviceId = deviceId
        this.deviceIdTextView.text = this.deviceId
    }

}
