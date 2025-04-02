package com.chriskinda.qs // Verify package name

// --- Imports ---
import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.lang.Exception
import android.view.InflateException
import java.util.concurrent.TimeUnit
// --- End Imports ---

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val ROOT_TAG = "RootExecutor_OS"
        @Volatile var isRunning = false
        private const val LOCATION_MODE_OFF = 0
        private const val LOCATION_MODE_HIGH_ACCURACY = 3
        private const val ROOT_COMMAND_TIMEOUT_SEC = 5L
        // Increased delay for non-Wifi toggles
        private const val UPDATE_DELAY_MS = 1500L
    }

    // --- Member Variables ---
    private val windowManager: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val inflater: LayoutInflater by lazy { getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater }
    private lateinit var params: WindowManager.LayoutParams
    private var overlayView: View? = null
    // Handler needed for delayed updates and ensuring UI updates on main thread
    private val uiHandler = Handler(Looper.getMainLooper())

    // --- onCreate (Requires Manual ADB Grant) ---
    override fun onCreate() {
        super.onCreate()
        isRunning = false
        Log.d(TAG, "1. OverlayService onCreate called.")
        var permissionGranted = false
        // Standard Permission Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { if (!Settings.canDrawOverlays(this)) { Log.e(TAG, "Overlay permission SYSTEM_ALERT_WINDOW NOT granted."); Log.e(TAG, "Please grant manually via ADB shell:"); Log.e(TAG, "adb shell pm grant $packageName android.permission.SYSTEM_ALERT_WINDOW"); showToast("Overlay Permission Missing! Grant via ADB."); permissionGranted = false } else { Log.d(TAG, "Overlay permission already granted."); permissionGranted = true } } else { Log.d(TAG, "Android version < M, overlay permission granted by default."); permissionGranted = true }
        if (!permissionGranted) { stopSelf(); return }

        isRunning = true
        Log.d(TAG, "2. Overlay permission OK. isRunning = $isRunning")
        try {
            Log.d(TAG, "3. Got WindowManager and LayoutInflater (lazy).")
            try {
                overlayView = inflater.inflate(R.layout.overlay_layout, null)
                Log.d(TAG, "4. Overlay layout inflated. overlayView is null: ${overlayView == null}")
                if (overlayView == null) throw InflateException("Inflated view is null")
            } catch (e: Exception) { Log.e(TAG, "ERROR during layout inflation", e); showToast("Error creating overlay view"); overlayView = null; stopSelf(); return }

            // Define LayoutParams
            val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY } else { WindowManager.LayoutParams.TYPE_PHONE }; params = WindowManager.LayoutParams( WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutParamsType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT ); params.gravity = Gravity.TOP or Gravity.START; params.x = 0; params.y = 0; Log.d(TAG, "5. LayoutParams defined. Type: $layoutParamsType")
            // Set outside touch listener
            overlayView?.setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_OUTSIDE) { Log.d(TAG, "Outside touch detected, stopping service."); stopSelf(); true } else { false } }; Log.d(TAG, "Outside touch listener set.")
            // Setup button listeners
            setupButtonClickListeners(); Log.d(TAG, "6. Button listeners set up.")

            // Add view to window
            try {
                Log.d(TAG, "7. Attempting to add view to WindowManager...")
                windowManager.addView(overlayView, params)
                Log.d(TAG, "8. Overlay view ADDED successfully.")
                // Update button states AFTER view is added
                updateButtonStates() // <-- Initial icon state update
            }
            catch (e: Exception) { Log.e(TAG, "9. ERROR adding overlay view to WindowManager", e); showToast("Error displaying overlay: ${e.message}"); overlayView = null; stopSelf(); return }
        } catch (e: Exception) { Log.e(TAG, "ERROR during service init", e); stopSelf(); return }
        Log.d(TAG, "10. onCreate finished successfully.")
    } // End onCreate

    // --- onDestroy ---
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "OverlayService onDestroy. isRunning = $isRunning")
        uiHandler.removeCallbacksAndMessages(null) // Remove pending updates
        overlayView?.let { view -> if (view.isAttachedToWindow) { try { windowManager.removeView(view); Log.d(TAG,"Overlay view removed.") } catch (e: Exception) { Log.e(TAG,"Error removing view", e) } } }
        overlayView = null
    } // End onDestroy

    // --- Other Lifecycle Methods ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { Log.d(TAG, "OverlayService onStartCommand Received"); return START_NOT_STICKY }
    override fun onBind(intent: Intent): IBinder? { return null }

    // --- Button Setup ---
    private fun setupButtonClickListeners() { overlayView?.apply { findViewById<ImageButton>(R.id.btn_wifi)?.setOnClickListener { toggleWifi() }; findViewById<ImageButton>(R.id.btn_data)?.setOnClickListener { toggleMobileData() }; findViewById<ImageButton>(R.id.btn_location)?.setOnClickListener { toggleLocation() }; findViewById<ImageButton>(R.id.btn_bluetooth)?.setOnClickListener { toggleBluetooth() }; findViewById<View>(R.id.drag_handle)?.setOnClickListener { stopSelf() } } ?: Log.e(TAG, "Cannot setup listeners, overlayView is null") }


    // --- Toggle Methods (Wi-Fi Changed, others call delayed update) ---

    private fun toggleWifi() {
        Log.d(TAG, "Wi-Fi button clicked")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) { showToast("Cannot access Wi-Fi service"); return }
        try {
            val wasEnabled = wm.isWifiEnabled // Check state BEFORE toggle
            Log.d(TAG, "Wi-Fi state before toggle: $wasEnabled")
            val cmd = if (wasEnabled) "svc wifi disable" else "svc wifi enable"
            if (executeRootCommand(cmd)) {
                showToast("Wi-Fi " + (if (wasEnabled) "Disabling..." else "Enabling..."))
                // Assume Success: Update UI Immediately to the INTENDED state
                val intendedState = !wasEnabled
                updateSpecificButtonState(R.id.btn_wifi, intendedState, R.drawable.ic_wifi_on, R.drawable.ic_wifi_off)
            } else {
                showToast("Failed to toggle Wi-Fi")
                // Optional: Revert UI if command failed? Schedule full update.
                uiHandler.postDelayed({ updateButtonStates() }, UPDATE_DELAY_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling Wi-Fi", e)
            showToast("Error toggling Wi-Fi")
        }
    } // END toggleWifi

    private fun toggleMobileData() {
        Log.d(TAG, "Mobile Data button clicked"); val out = executeRootCommandWithOutput("settings get global mobile_data"); var on = false; if (out != null) { try { on = (out.trim() == "1"); Log.d(TAG, "Data state before toggle: $on") } catch (e: Exception) { Log.e(TAG, "Err parsing data state", e) } } else { Log.w(TAG, "Failed get data state") }; val cmd = if (on) "svc data disable" else "svc data enable"; Log.d(TAG, "Attempting: $cmd"); if (executeRootCommand(cmd)) { showToast("Mobile Data " + (if (on) "Disabling..." else "Enabling...")); uiHandler.postDelayed({ updateButtonStates() }, UPDATE_DELAY_MS) /* Delayed update */ } else { showToast("Failed to toggle Mobile Data") }
    } // End toggleMobileData

    private fun toggleLocation() {
        Log.d(TAG, "Location button clicked"); val out = executeRootCommandWithOutput("settings get secure location_mode"); if (out == null) { showToast("Failed get location status"); return }; var mode = LOCATION_MODE_OFF; try { mode = out.trim().toInt(); Log.d(TAG, "Loc Mode before toggle: $mode") } catch (e: Exception) { Log.e(TAG, "Err parsing loc mode", e) }; val newMode = if (mode == LOCATION_MODE_OFF) LOCATION_MODE_HIGH_ACCURACY else LOCATION_MODE_OFF; Log.d(TAG, "Setting Loc Mode: $newMode"); val cmd = "settings put secure location_mode $newMode"; if (executeRootCommand(cmd)) { showToast("Location set to $newMode"); uiHandler.postDelayed({ updateButtonStates() }, UPDATE_DELAY_MS) /* Delayed update */ } else { showToast("Failed toggle location") }
    } // End toggleLocation

    private fun toggleBluetooth() {
        Log.d(TAG, "Bluetooth button clicked"); val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager; val ba = bm?.adapter; if (ba == null) { showToast("Bluetooth not available"); return }; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { showToast("Bluetooth permission missing"); return } }; try { val on = ba.isEnabled; Log.d(TAG, "BT state before toggle: $on"); val cmd = if (on) "svc bluetooth disable" else "svc bluetooth enable"; if (executeRootCommand(cmd)) { showToast("Bluetooth " + (if (on) "Disabling..." else "Enabling...")); uiHandler.postDelayed({ updateButtonStates() }, UPDATE_DELAY_MS) /* Delayed update */ } else { showToast("Failed toggle Bluetooth") } } catch (e: Exception) { Log.e(TAG, "Err toggle Bluetooth", e); showToast("Err toggle Bluetooth") }
    } // End toggleBluetooth
    // --- End Toggle Methods ---


    // --- **** Function to Update Button Icons **** ---
    private fun updateButtonStates() {
        Log.d(TAG, "Updating button states...")
        val safeOverlayView = overlayView ?: return
        uiHandler.post { // Ensure UI work on main thread
            Log.d(TAG, "Running updateButtonStates on UI thread.")
            try {
                // --- Update Wi-Fi Icon ---
                Log.d(TAG, "UI Update - Checking Wifi state...")
                val btnWifi = safeOverlayView.findViewById<ImageButton>(R.id.btn_wifi)
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                var isWifiEnabled = false; var wifiCheckError: String? = null
                try { if (wifiManager == null) { wifiCheckError = "WifiManager NULL" } else { isWifiEnabled = wifiManager.isWifiEnabled; Log.i(TAG, "*** WifiManager.isWifiEnabled returned: $isWifiEnabled ***") } }
                catch (e: SecurityException) { wifiCheckError = "SecEx"; Log.e(TAG, "Perm error checking wifi UI: $wifiCheckError", e) }
                catch (e: Exception) { wifiCheckError = "Ex"; Log.e(TAG, "Error checking wifi UI", e) }
                btnWifi?.setImageResource(if (isWifiEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off)
                btnWifi?.imageAlpha = if (isWifiEnabled) 255 else 130
                Log.d(TAG, "UI Update - Wifi: $isWifiEnabled (Err: $wifiCheckError)")

                // --- Update Mobile Data Icon ---
                val btnData = safeOverlayView.findViewById<ImageButton>(R.id.btn_data); var isDataEnabled = false; val dataOut = executeRootCommandWithOutput("settings get global mobile_data"); if (dataOut != null) { try { isDataEnabled = (dataOut.trim() == "1") } catch (e: Exception) {} } else { Log.w(TAG, "Failed get data state UI") };
                btnData?.setImageResource(if (isDataEnabled) R.drawable.ic_mobile_data_on else R.drawable.ic_mobile_data_off); // Using your specified name
                btnData?.imageAlpha = if (isDataEnabled) 255 else 130; Log.d(TAG, "UI Update - Data: $isDataEnabled")

                // --- Update Location Icon ---
                val btnLocation = safeOverlayView.findViewById<ImageButton>(R.id.btn_location); var isLocationEnabled = false; val locOut = executeRootCommandWithOutput("settings get secure location_mode"); if (locOut != null) { try { isLocationEnabled = (locOut.trim().toInt() != LOCATION_MODE_OFF) } catch (e: Exception) {} } else { Log.w(TAG, "Failed get location mode UI") }; btnLocation?.setImageResource(if (isLocationEnabled) R.drawable.ic_location_on else R.drawable.ic_location_off); btnLocation?.imageAlpha = if (isLocationEnabled) 255 else 130; Log.d(TAG, "UI Update - Location: $isLocationEnabled")

                // --- Update Bluetooth Icon ---
                val btnBluetooth = safeOverlayView.findViewById<ImageButton>(R.id.btn_bluetooth); val blueMan = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager; val blueAdapter = blueMan?.adapter; var isBluetoothEnabled = false; if (blueAdapter != null) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { try { isBluetoothEnabled = blueAdapter.isEnabled } catch (e: Exception) { Log.e(TAG, "Error checking BT UI", e) } } else { Log.w(TAG, "No BT permission for UI") } } else { try { isBluetoothEnabled = blueAdapter.isEnabled } catch (e: Exception) { Log.e(TAG, "Error checking BT UI (pre-31)", e) } } }; btnBluetooth?.setImageResource(if (isBluetoothEnabled) R.drawable.ic_bluetooth_on else R.drawable.ic_bluetooth_off); btnBluetooth?.imageAlpha = if (isBluetoothEnabled) 255 else 130; Log.d(TAG, "UI Update - Bluetooth: $isBluetoothEnabled")

                Log.i(TAG, "Button states update finished.")
            } catch (e: Exception) { Log.e(TAG, "Error during updateButtonStates on UI thread", e) }
        } // End uiHandler.post
    } // End updateButtonStates

    // --- Helper to Update Specific Button (Used by toggleWifi) ---
    private fun updateSpecificButtonState(buttonId: Int, isEnabled: Boolean, iconOnRes: Int, iconOffRes: Int) {
        Log.d(TAG,"Updating specific button state for ID: $buttonId -> Setting Enabled Icon: $isEnabled")
        uiHandler.post { // Ensure UI update on main thread
            overlayView?.findViewById<ImageButton>(buttonId)?.apply {
                setImageResource(if (isEnabled) iconOnRes else iconOffRes)
                imageAlpha = if (isEnabled) 255 else 130 // Also update alpha immediately
            } ?: Log.e(TAG, "Button with ID $buttonId not found for specific update.")
        }
    } // End updateSpecificButtonState


    // --- Root Command Helpers (Keep BOTH - Syntax Checked Version) ---
    private fun executeRootCommand(command: String): Boolean { /* ... Keep implementation from "... 11:10:04 PM PDT" ... */ Log.d(ROOT_TAG, "Executing command (Bool): $command"); var process: Process? = null; var os: DataOutputStream? = null; var success = false; try { process = Runtime.getRuntime().exec("su"); os = DataOutputStream(process.outputStream); os.writeBytes("$command\n"); os.writeBytes("exit\n"); os.flush(); val exited = process.waitFor(ROOT_COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS); if (exited) { val exitCode = process.exitValue(); Log.d(ROOT_TAG, "Command '$command' exit code: $exitCode"); success = exitCode == 0 } else { Log.e(ROOT_TAG, "Command '$command' timed out."); success = false } } catch (e: IOException) { Log.e(ROOT_TAG, "IOException (Bool): $command", e); success = false } catch (e: InterruptedException) { Log.e(ROOT_TAG, "InterruptedException (Bool): $command", e); Thread.currentThread().interrupt(); success = false } catch (e: Exception) { Log.e(ROOT_TAG, "Exception (Bool): $command", e); success = false } finally { try { os?.close() } catch (e: IOException) { Log.w(ROOT_TAG, "IOException closing OS stream (Bool): ${e.message}") }; try { process?.destroy(); Log.d(ROOT_TAG, "Process destroyed for (Bool): $command") } catch(e:Exception){ Log.e(ROOT_TAG, "Exception destroying proc (Bool): $command", e)}}; return success }
    private fun executeRootCommandWithOutput(command: String): String? { /* ... Keep implementation from "... 11:10:04 PM PDT" ... */ Log.d(ROOT_TAG, "Executing command (Output): $command"); var process: Process? = null; var os: DataOutputStream? = null; var reader: BufferedReader? = null; var errorReader: BufferedReader? = null; val output = StringBuilder(); var errorOccurred = false; var exitCode = -1; var result: String? = null; try { process = Runtime.getRuntime().exec("su"); os = DataOutputStream(process.outputStream); reader = process.inputStream?.bufferedReader(); errorReader = process.errorStream?.bufferedReader(); os.writeBytes("$command\n"); os.writeBytes("exit\n"); os.flush(); val exited = process.waitFor(ROOT_COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS); if (exited) { exitCode = process.exitValue(); Log.d(ROOT_TAG, "Command '$command' exit code: $exitCode"); try { errorReader?.forEachLine { line -> Log.e(ROOT_TAG, "STDERR: $line"); errorOccurred = true } } catch (e: Exception) { Log.e(ROOT_TAG, "Exception reading stderr", e) }; if (exitCode == 0 && !errorOccurred) { try { reader?.forEachLine { line -> output.append(line).append("\n") }; result = output.toString().trim() } catch (e: Exception) { Log.e(ROOT_TAG, "Exception reading stdout", e); result = null; errorOccurred = true } } else { Log.e(ROOT_TAG, "Command failed or produced errors. ExitCode: $exitCode, stderr: $errorOccurred"); result = null } } else { Log.e(ROOT_TAG, "Command '$command' timed out."); errorOccurred = true } } catch (e: IOException) { Log.e(ROOT_TAG, "IOException (Output): $command", e); errorOccurred = true } catch (e: InterruptedException) { Log.e(ROOT_TAG, "InterruptedException (Output): $command", e); Thread.currentThread().interrupt(); errorOccurred = true } catch (e: Exception) { Log.e(ROOT_TAG, "Exception (Output): $command", e); errorOccurred = true } finally { try { reader?.close() } catch (e: IOException) {} ; try { errorReader?.close() } catch (e: IOException) {} ; try { os?.close() } catch (e: IOException) {} ; try { process?.destroy(); Log.d(ROOT_TAG, "Process destroyed for (Output): $command") } catch (e: Exception) {} }; return if (result != null && exitCode == 0 && !errorOccurred) result else null }

    // --- Utility Functions ---
    private fun showToast(message: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() } }

} // End of OverlayService class