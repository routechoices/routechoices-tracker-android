package com.routechoices.routechoicestracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import android.net.Uri
import android.os.*
import kotlin.concurrent.fixedRateTimer


const val LOCATION_PERMISSION: Int = 0

class MainActivity : AppCompatActivity() {
    private var mService: LocationTrackingService? = null
    private var mBound: Boolean = false
    private var deviceId = ""

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as LocationTrackingService.MyBinder
            mService = binder.getService()
            mBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mService = null
            mBound = false
        }
    }

    fun displayGpsStatusColor() {
        var color = "#ff0800"
        if (mBound && mService != null && startStopButton.getText() == "Stop live gps") {
            if (System.currentTimeMillis()/1e3 - (mService?.lastLocationTS!!) < 10) {
                color = "#4cd964"
            }
        }
        deviceIdTextView.setTextColor(Color.parseColor(color))
    }

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

        registerButton.setOnClickListener {
            onClickRegister()
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
        fixedRateTimer("timer", false, 0L, 1000) {
            this@MainActivity.runOnUiThread {
                displayGpsStatusColor()
            }
        }
    }

    private fun toggleStartStop() {
        val action = if (startStopButton.getText() == "Start live gps") {
            startStopButton.setText("Stop live gps")
            startStopButton.setBackgroundColor(Color.parseColor("#ff0800"));
            "start"
        } else {
            startStopButton.setText("Start live gps");
            startStopButton.setBackgroundColor(Color.parseColor("#007AFF"));
            if (mBound) {
                mService?.stopService()
                unbindService(connection)
            }
            "stop"
        }
        if (action == "stop") return
        val it = Intent(this, LocationTrackingService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
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
        val url = "https://api.routechoices.com/device_id"
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

    private fun onClickRegister() {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://registration.routechoices.com/#device_id=$deviceId"))
        startActivity(browserIntent)
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
