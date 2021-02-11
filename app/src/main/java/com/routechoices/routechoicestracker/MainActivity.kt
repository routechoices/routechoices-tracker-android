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
        if (getServiceState(this) == ServiceState.STARTED) {
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
        }
    }

    private fun toggleStartStop() {
        val action = if (startStopButton.getText() == "Start") {
            startStopButton.setText("Stop");
            "start"
        } else {
            startStopButton.setText("Start");
            "stop"
        }
        if (getServiceState(this) == ServiceState.STOPPED && action == "stop") return
        val it = Intent(this, LocationTrackingService::class.java)
        it.putExtra("devId", deviceId)
        it.putExtra("action", action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(it)
            return
        }
        startService(it)
    }

    private fun fetchDeviceId() {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        this.deviceId = sharedPref.getString("deviceId", "")

        if (this.deviceId == "") {
            this.requestDeviceId()
        } else {
            this.deviceIdTextView.text = this.deviceId
        }
    }

    private fun requestDeviceId() {
        val url = "https://www.routechoices.com/api/device_id"
        val queue = Volley.newRequestQueue(this)

        val stringRequest = JsonObjectRequest(Request.Method.POST, url, null,
            Listener<JSONObject> { response ->
                onDeviceIdResponse(response)
            },
            Response.ErrorListener { error ->
                this.deviceIdTextView.text = "Could not fetch Device ID"
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
