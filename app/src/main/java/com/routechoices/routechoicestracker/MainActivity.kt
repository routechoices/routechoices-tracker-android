package com.routechoices.routechoicestracker

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import android.content.Intent
import androidx.appcompat.app.AlertDialog;
import android.os.Build
import android.widget.Toast
import android.graphics.Color
import android.view.View;

const val LOCATION_PERMISSION: Int = 0

class MainActivity : AppCompatActivity() {

    private var deviceId = ""
    private var clipboard: ClipboardManager? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceIdTextView.setText("Fetching...")
        if (getServiceState(this) == ServiceState.STARTED) {
            startStopButton.setText("Stop live gps")
            startStopButton.setBackgroundColor(Color.parseColor("#ff0800"));
        }
        fetchDeviceId()

        copyBtn.setOnClickListener {
            copyDeviceId()
        }

        startStopButton.setOnClickListener {
            toggleStartStop()
        }
        val alertDialogBuilder = AlertDialog.Builder(this)
        alertDialogBuilder.setTitle("Location access")
        alertDialogBuilder.setMessage("Please allow location service all the time to be able to share your location while you run your events")
        alertDialogBuilder.setPositiveButton("OK") { _, _ ->
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ), LOCATION_PERMISSION
            )
        }
        val alertDialog = alertDialogBuilder.create()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) alertDialog.show()
    }

    private fun toggleStartStop() {
        val action = if (startStopButton.getText() == "Start live gps") {
            startStopButton.setText("Stop live gps")
            startStopButton.setBackgroundColor(Color.parseColor("#ff0800"));
            "start"
        } else {
            startStopButton.setText("Start live gps");
            startStopButton.setBackgroundColor(Color.parseColor("#007AFF"));
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

    private fun copyDeviceId() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textToCopy = deviceId
        val clip = ClipData.newPlainText("Device ID", textToCopy)
        clipboard.setPrimaryClip(clip);
        Toast.makeText(applicationContext,"Device ID copied",Toast.LENGTH_SHORT).show()
    }

    private fun fetchDeviceId() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        deviceId = sharedPref.getString("deviceId", "").toString()

        if (deviceId == "") {
            startStopButton.visibility = View.INVISIBLE
            copyBtn.visibility = View.INVISIBLE
            requestDeviceId()
        } else {
            deviceIdTextView.text = deviceId
        }
    }

    private fun requestDeviceId() {
        val url = "https://www.routechoices.com/api/device_id"
        val queue = Volley.newRequestQueue(this)

        val stringRequest = JsonObjectRequest(Request.Method.POST, url, null,
            Listener<JSONObject> { response ->
                onDeviceIdResponse(response)
            },
            Response.ErrorListener { _ ->
                fetchDeviceId()
            })
        queue.add(stringRequest)
    }

    private fun onDeviceIdResponse(response: JSONObject) {
        this.setDeviceId(response.getString("device_id"))
    }

    private fun setDeviceId(deviceId: String) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putString("deviceId", deviceId)
            commit()
        }
        this.deviceId = deviceId
        this.deviceIdTextView.text = this.deviceId
        startStopButton.visibility = View.VISIBLE
        copyBtn.visibility = View.VISIBLE
    }
}
