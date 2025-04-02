package com.chriskinda.qs // Verify your package name

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color // For debug background color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent // Needed for OnTouchListener
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.lang.Exception

class TriggerService : Service() {

    companion object {
        private const val TAG = "TriggerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "TriggerServiceChannel"
        // No logcat trigger message needed anymore
    }

    private lateinit var windowManager: WindowManager
    private var triggerView: View? = null
    private var triggerParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TriggerService Creating (Click Trigger Version)...")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "TriggerService Started in Foreground.")

        // Setup the transparent trigger view overlay - happens only once here
        setupTriggerView()
    }

    // --- Corrected onStartCommand ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "TriggerService onStartCommand received.")
        // Setup is handled in onCreate. No need to call setupTriggerView() again here.
        return START_STICKY // Keep service running
    }
    // --- End Corrected onStartCommand ---


    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "TriggerService Destroying...")
        removeTriggerView()
        stopForeground(true)
        Log.i(TAG, "TriggerService Destroyed.")
    }

    // --- Transparent Trigger View Setup ---
    @SuppressLint("InflateParams", "ClickableViewAccessibility") // Added SuppressLint for setOnTouchListener
    private fun setupTriggerView() {
        // Remove any existing view first (safety check)
        removeTriggerView()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Cannot setup trigger view: Overlay permission missing!")
            showErrorToast("Overlay permission required by TriggerService.")
            stopSelf()
            return
        }

        val statusBarHeight = getStatusBarHeight()
        if (statusBarHeight <= 0) {
            Log.e(TAG, "Failed to get valid status bar height, cannot create trigger view.")
            showErrorToast("Failed to get status bar height.")
            stopSelf()
            return
        }
        Log.d(TAG, "Status bar height detected: $statusBarHeight")

        triggerView = FrameLayout(this).apply {
            isFocusable = false // Explicitly false
            // isClickable = true // Not needed for OnTouchListener

            // Debug background color - REMOVE later
            setBackgroundColor(Color.parseColor("#55FF0000"))

            // --- Use OnTouchListener instead of OnClickListener ---
            setOnTouchListener { _, event ->
                Log.d(TAG, "Trigger View onTouchEvent: ${MotionEvent.actionToString(event.action)}")
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.e(TAG, ">>> TRIGGER VIEW ACTION_DOWN DETECTED! <<<") // Log press
                    startOverlay() // Attempt to show main overlay
                    true // Consume the DOWN event
                } else {
                    false // Don't consume other events (UP, MOVE etc.)
                }
            }
            // ----------------------------------------------------
        }

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // --- Corrected LayoutParams Flags ---
        triggerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, // Full width
            statusBarHeight, // Status bar height
            layoutParamsType,
            // Flags: REMOVED FLAG_NOT_FOCUSABLE, kept LAYOUT_IN_SCREEN
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,

            PixelFormat.TRANSPARENT // Use TRANSPARENT for click overlay
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }
        // --- End Corrected LayoutParams Flags ---


        try {
            Log.d(TAG, "Adding trigger view to WindowManager...")
            windowManager.addView(triggerView, triggerParams)
            Log.i(TAG, "Trigger view added successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding trigger view to WindowManager", e)
            showErrorToast("Error adding trigger overlay: ${e.message}")
            triggerView = null
            stopSelf()
        }
    }

    private fun removeTriggerView() {
        triggerView?.let { view ->
            if (view.isAttachedToWindow) {
                try {
                    windowManager.removeView(view)
                    Log.i(TAG, "Trigger view removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing trigger view", e)
                }
            }
        }
        triggerView = null
        triggerParams = null
    }

    // --- Helper Function to Get Status Bar Height (Keep as before) ---
    @SuppressLint("InternalApi")
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        if (result <= 0) {
            Log.w(TAG, "Status bar height resource not found, using fallback estimate (24dp)")
            result = (24 * resources.displayMetrics.density).toInt()
        }
        return result
    }

    // --- Function to Start the Main Overlay (Keep as before) ---
    private fun startOverlay() {
        if (!OverlayService.isRunning) {
            Log.d(TAG, "OverlayService not running, attempting to start...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.e(TAG, "Cannot start OverlayService - SYSTEM_ALERT_WINDOW permission missing!")
                showErrorToast("Overlay permission required to show panel!")
                return
            }
            val intent = Intent(this, OverlayService::class.java)
            try {
                startService(intent)
                Log.i(TAG, "OverlayService start command sent.")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting OverlayService", e)
                showErrorToast("Failed to display overlay panel.")
            }
        } else {
            Log.d(TAG, "OverlayService is already running, not starting again.")
        }
    }

    // --- Foreground Service Notification (Keep as before, text updated) ---
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QS Overlay Trigger Active")
            .setContentText("Click status bar to trigger overlay.") // Updated text
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        // ... (keep createNotificationChannel as before) ...
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Trigger Service Background Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for the persistent notification indicating the QS overlay trigger is running."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    // --- Error Toast Helper (Keep as before) ---
    private fun showErrorToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@TriggerService, message, Toast.LENGTH_LONG).show()
        }
    }

} // End of TriggerService class