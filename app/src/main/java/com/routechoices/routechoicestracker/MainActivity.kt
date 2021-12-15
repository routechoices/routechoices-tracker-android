package com.routechoices.routechoicestracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import kotlin.concurrent.fixedRateTimer
import android.util.Log

class MainActivity : AppCompatActivity() {
    private var mService: LocationTrackingService? = null
    private var mBound: Boolean = false
    private var deviceId = ""
    private val isBackgroundLocationPermissionAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
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
            if (startStopButton.getText() == resources.getText(R.string.stopGps)) {
                startStopButton.setText(R.string.startGps)
                toggleStartStop()
            }
        }
    }

    private fun displayGpsStatusColor() {
        var color = "#ff0800"
        if (mBound && mService != null && startStopButton.getText() == resources.getText(R.string.stopGps)) {
            if (System.currentTimeMillis()/1e3 - (mService?.lastLocationTS!!) < 10) {
                color = "#4cd964"
            }
        }
        if ((!mBound || mService == null) && startStopButton.getText() == resources.getText(R.string.stopGps)) {
            startStopButton.setText(R.string.startGps)
            toggleStartStop()
        }
        deviceIdTextView.setTextColor(Color.parseColor(color))
    }

    private fun checkPermissionStatus(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBackgroundLocationPermission() {
        val permissions: MutableSet<String> = HashSet()
        val hasLocation = checkPermissionStatus(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasLocation) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        var askBg = false
        if (isBackgroundLocationPermissionAvailable) {
            val hasBgLocation = checkPermissionStatus(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            if (!hasBgLocation) {
                askBg = true
            }
        }
        if (permissions.isNotEmpty()) {
            showLocationDisclosure(permissions.toTypedArray(), askBg)
        } else if (askBg) {
            showBGLocationDisclosure()
        }
    }

    private fun showLocationDisclosure(permissions: Array<String>, askBg: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(R.string.allowLocation)
            .setMessage(R.string.allowLocationDesc)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
                if (askBg) showBGLocationDisclosure()
            }.setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun showBGLocationDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.allowBgLocation)
            .setMessage(R.string.allowBgLocationDesc)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), PERMISSIONS_REQUEST_CODE)
            }.setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pkgManager = applicationContext.packageManager
        requestQueue = Volley.newRequestQueue(this)
        deviceIdTextView.setText(R.string.fetching)
        if (getServiceState(this) == ServiceState.STARTED) {
            startStopButton.setText(R.string.stopGps)
            startStopButton.setBackgroundColor(Color.parseColor("#ff0800"))
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

        requestBackgroundLocationPermission()

        fixedRateTimer("timer", false, 0L, 1000) {
            this@MainActivity.runOnUiThread {
                displayGpsStatusColor()
            }
        }

        // checkBattery()
    }

    private fun toggleStartStop() {
        val action = if (startStopButton.getText() == resources.getText(R.string.startGps)) {
            startStopButton.setText(R.string.stopGps)
            startStopButton.setBackgroundColor(Color.parseColor("#ff0800"))
            "start"
        } else {
            startStopButton.setText(R.string.startGps)
            startStopButton.setBackgroundColor(Color.parseColor("#007AFF"))
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
        startForegroundService(it)
    }

    private fun copyDeviceId() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textToCopy = deviceId
        val clip = ClipData.newPlainText(resources.getText(R.string.devId), textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(applicationContext, R.string.devIdCopied, Toast.LENGTH_SHORT).show()
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
                val url = "https://api.routechoices.com/device/$deviceId/registrations"


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
                    Response.ErrorListener {
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

        val params = JSONObject()
        params.put("secret", BuildConfig.POST_LOCATION_SECRET)

        val stringRequest = JsonObjectRequest(Request.Method.POST, url, params,
            Listener<JSONObject> { response ->
                onDeviceIdResponse(response)
            },
            Response.ErrorListener {
                fetchDeviceId()
            })
        requestQueue?.add(stringRequest)
    }

    private fun onDeviceIdResponse(response: JSONObject) {
        this.setDeviceId(response.getString("device_id"))
    }

    private fun onClickRegister() {
        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://registration.routechoices.com/#device_id=$deviceId")
        )
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
