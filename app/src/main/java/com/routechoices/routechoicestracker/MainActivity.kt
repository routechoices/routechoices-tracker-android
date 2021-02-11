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
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import android.content.Intent

import android.os.Build

const val LOCATION_PERMISSION: Int = 0

class MainActivity : AppCompatActivity() {

    private var deviceId = ""
    private var serviceIntent: Intent? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceIdTextView.setText("Fetching device id")
        if (getServiceState(this) == ServiceState.STARTED && serviceIntent != null) {
          startStopButton.setText("Stop")
        }
        this.fetchDeviceId()
        startStopButton.setOnClickListener {
            toggleStartStop()
        }

        while (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), LOCATION_PERMISSION
            )
            Log.d("DEBUG", "No access to location")
        }
    }

    private fun toggleStartStop() {
        if (startStopButton.text.equals("Start")) {
            startStopButton.setText("Stop");
            val _serviceIntent = Intent(this, LocationTrackingService::class.java)
            _serviceIntent.putExtra("devId", deviceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(_serviceIntent)
                serviceIntent = _serviceIntent
                return
            }
            startService(_serviceIntent)
            serviceIntent = _serviceIntent
        } else {
            startStopButton.setText("Start");
            stopService(serviceIntent)
        }
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
        val url = "https://www.routechoices.com/api/device_id"
        val queue = Volley.newRequestQueue(this)

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
