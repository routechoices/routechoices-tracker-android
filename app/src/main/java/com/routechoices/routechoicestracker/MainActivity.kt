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
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import android.net.Uri
import android.os.*
import kotlin.concurrent.fixedRateTimer
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat

const val LOCATION_PERMISSION: Int = 0

class MainActivity : AppCompatActivity() {
    private var mService: LocationTrackingService? = null
    private var mBound: Boolean = false
    private var deviceId = ""
    private var shouldRequestBackgroundPermission = false
    private val isBackgroundLocationPermissionAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val isBackgroundPermissionLabelAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private var pkgManager: PackageManager? = null
    private var requestQueue: RequestQueue? = null
    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 736
    }

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
            if (startStopButton.getText() == "Stop live gps") {
                startStopButton.setText("Start live gps")
                toggleStartStop()
            }
        }
    }

    fun displayGpsStatusColor() {
        var color = "#ff0800"
        if (mBound && mService != null && startStopButton.getText() == "Stop live gps") {
            if (System.currentTimeMillis()/1e3 - (mService?.lastLocationTS!!) < 10) {
                color = "#4cd964"
            }
        }
        if ((!mBound || mService == null) && startStopButton.getText() == "Stop live gps") {
            startStopButton.setText("Start live gps")
            toggleStartStop()
        }
        deviceIdTextView.setTextColor(Color.parseColor(color))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pwrm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val name = applicationContext.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return pwrm.isIgnoringBatteryOptimizations(name)
        }
        return true
    }

    private fun checkBattery() {
        if (!isIgnoringBatteryOptimizations() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val name = resources.getString(R.string.app_name)
            val alertDialogBuilder = AlertDialog.Builder(this)
            alertDialogBuilder.setTitle("Battery Optimization")
            alertDialogBuilder.setMessage("$name is not excluded from Battery optimization.\nThis mean data streaming will stop while the screen is locked.\nTo change that go to:\nBattery optimization > All apps > $name > Don't optimize")
            alertDialogBuilder.setPositiveButton(android.R.string.ok) { _, _ ->
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
            alertDialogBuilder.setNegativeButton(android.R.string.cancel) { dialog,_ ->
                dialog.dismiss()
            }
            val alertDialog = alertDialogBuilder.create()
            alertDialog.show()
        }
    }

    private fun checkPermissionStatus(permission: String): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun requestBackgroundLocationPermission(view: View?) {
        if (isBackgroundLocationPermissionAvailable) {
            val hasLocation = checkPermissionStatus(Manifest.permission.ACCESS_FINE_LOCATION)
            val hasBgLocation = checkPermissionStatus(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            if (!hasBgLocation) {
                requestPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            if (!hasLocation) {
                shouldRequestBackgroundPermission = true
                showLocationDisclosure(true)
            }
        }
    }
    private fun rationaleTitleFromPermission(permission: String): String {
        return if (!isBackgroundLocationPermissionAvailable) {
            "Location access"
        } else when (permission) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background location access"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Fine location access"
            else -> "Location access"
        }
    }

    private fun rationaleMessageFromPermission(permission: String): String {
        return if (!isBackgroundLocationPermissionAvailable) {
            "Allow location access for this app to work"
        } else when (permission) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Allow background location access for this app to work"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Allow fine location access for this app to work"
            else -> "Allow location access for this app to work"
        }
    }

    private fun showPermissionRationale(permission: String, titleId: String, messageId: String) {
        AlertDialog.Builder(this)
            .setTitle(titleId)
            .setMessage(messageId)
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission),
                    PERMISSIONS_REQUEST_CODE)
            }
            .show()
    }

    private fun showLocationDisclosure(requestBackground: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Allow location access")
            .setMessage("Allow location access for this app to work properly")
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                shouldRequestBackgroundPermission = requestBackground
                requestPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            }.setNegativeButton(android.R.string.cancel) { dialog, _ ->
                shouldRequestBackgroundPermission = false
                dialog.cancel()
            }
            .show()
    }

    private fun requestPermission(permission: String) {
        if (checkPermissionStatus(permission)) {
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            showPermissionRationale(permission, rationaleTitleFromPermission(permission), rationaleMessageFromPermission(permission))
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission),
                PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pkgManager = applicationContext.packageManager
        requestQueue = Volley.newRequestQueue(this)
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

        requestBackgroundLocationPermission(null)

        fixedRateTimer("timer", false, 0L, 1000) {
            this@MainActivity.runOnUiThread {
                displayGpsStatusColor()
            }
        }

        // checkBattery()
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
            val isOld = Regex("[^0-9]").containsMatchIn(deviceId)
            if (isOld) {
                startStopButton.visibility = View.INVISIBLE
                copyBtn.visibility = View.INVISIBLE
                val url = "https://api.routechoices.com/device/" + deviceId + "/registrations"


                val stringRequest = JsonObjectRequest(Request.Method.GET, url, null,
                    Listener<JSONObject> { response ->
                        val count = response.getInt("count")
                        if (count == 0) {
                            requestDeviceId()
                        } else {
                            startStopButton.visibility = View.VISIBLE
                            copyBtn.visibility = View.VISIBLE
                        }
                    },
                    Response.ErrorListener { _ ->
                        requestDeviceId()
                    })
                requestQueue?.add(stringRequest)
                return
            }
            deviceIdTextView.text = deviceId
        }
    }

    private fun requestDeviceId() {
        val url = "https://api.routechoices.com/device_id"

        val params: JSONObject = JSONObject()
        params.put("secret", BuildConfig.POST_LOCATION_SECRET)

        val stringRequest = JsonObjectRequest(Request.Method.POST, url, params,
            Listener<JSONObject> { response ->
                onDeviceIdResponse(response)
            },
            Response.ErrorListener { _ ->
                fetchDeviceId()
            })
        requestQueue?.add(stringRequest)
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
