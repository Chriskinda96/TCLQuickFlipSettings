package com.chriskinda.qs // Verify package name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.DataOutputStream
import java.io.IOException
import java.lang.Exception
import java.io.BufferedReader
import java.io.InputStreamReader
// Import Coroutine components
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class StatusBarClickService : AccessibilityService() {

    companion object {
        private const val TAG = "StatusBarClickService"
        const val NOTIFICATION_PANEL_RES_ID = "com.android.systemui:id/notification_panel"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onServiceConnected() {
        Log.e(TAG, ">>> OnServiceConnected method ENTERED! <<<")
        super.onServiceConnected()

        // --- ADDED: Initial Root Request ---
        // Run a harmless command simply to trigger the Magisk prompt early.
        // Do this in a coroutine so it doesn't block onServiceConnected.
        serviceScope.launch {
            Log.d(TAG, "Coroutine: Performing initial root check...")
            val rootCheckSuccess = executeRootCommand("echo Initial Root Check OK")
            Log.i(TAG, "Coroutine: Initial root check command finished. Success: $rootCheckSuccess")
            // We don't strictly need the result, just the prompt trigger.
        }
        // --- END ADDED ---


        // Attempt to set flags programmatically
        val info: AccessibilityServiceInfo? = serviceInfo
        if (info == null) {
            Log.e(TAG, "Could not get serviceInfo to set flags.")
        } else {
            Log.d(TAG, "Original flags: ${info.flags}")
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            this.serviceInfo = info
            Log.i(TAG, "Attempted to update Service flags programmatically. New flags: ${this.serviceInfo?.flags}")
        }
        Log.i(TAG, "onServiceConnected finished.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventTypeString = AccessibilityEvent.eventTypeToString(event.eventType) ?: "UNKNOWN (${event.eventType})"

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            var resourceId = "NO SOURCE"
            var className = "NO SOURCE"
            var packageName = event.packageName ?: "NO PKG"

            try {
                val sourceNode = event.source
                if (sourceNode != null) {
                    resourceId = sourceNode.viewIdResourceName ?: "NO ID"
                    className = sourceNode.className?.toString() ?: "NO CLASS"

                    Log.d(TAG, "Window Event: Pkg:[${packageName}], Type:[${eventTypeString}], ID:[$resourceId], Class:[$className]")

                    if (resourceId == NOTIFICATION_PANEL_RES_ID) {
                        Log.i(TAG, ">>> NOTIFICATION_PANEL State Change Detected! <<< ID: $resourceId")

                        serviceScope.launch {
                            // Try alternative collapse command
                            val commandToTry = "service call statusbar 2"
                            Log.d(TAG, "Coroutine: Attempting to collapse status bar via root using: '$commandToTry'")
                            val collapsed = executeRootCommand(commandToTry)
                            Log.d(TAG, "Coroutine: Root command executed. Success: $collapsed")

                            // Start overlay AFTER attempting collapse
                            startOverlay()
                        }
                    }
                    sourceNode.recycle()
                } else {
                    Log.w(TAG, "Window Event: Pkg:[${packageName}], Type:[${eventTypeString}], SourceNode was NULL")
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Window Event: Pkg:[${packageName}], Type:[${eventTypeString}], Error accessing source node: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Window Event: Pkg:[${packageName}], Type:[${eventTypeString}], Unexpected error accessing source node: ${e.message}")
            }
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Accessibility Service Interrupted") }

    override fun onDestroy() { super.onDestroy(); Log.i(TAG, "StatusBarClickService Destroying..."); serviceScope.cancel(); Log.i(TAG, "Service scope cancelled.") }

    // --- Function to Start the Main Overlay ---
    private fun startOverlay() {
        if (!OverlayService.isRunning) {
            Log.d(TAG, "OverlayService not running, attempting to start...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.e(TAG, "Cannot start OverlayService - SYSTEM_ALERT_WINDOW permission missing!")
                Log.e(TAG, "Overlay permission required by main panel - grant via ADB or root grant failed.")
                // Displaying a toast from AccService can be tricky, log is safer
                return
            }
            val intent = Intent(this, OverlayService::class.java)
            try {
                startService(intent)
                Log.i(TAG, "OverlayService start command sent.")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting OverlayService from AccessibilityService", e)
            }
        } else {
            Log.d(TAG, "OverlayService is already running, not starting again.")
        }
    }

    // --- Root Command Helper Function ---
    private fun executeRootCommand(command: String): Boolean {
        val ROOT_TAG = "RootExecutor_AS"
        Log.d(ROOT_TAG, "Executing command: $command")
        var process: Process? = null
        var os: DataOutputStream? = null
        var success = false
        try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            val exitCode = process.waitFor()
            Log.d(ROOT_TAG, "Command '$command' exit code: $exitCode")
            success = exitCode == 0
        } catch (e: IOException) { Log.e(ROOT_TAG, "IOException executing root command: $command", e); success=false }
        catch (e: InterruptedException) { Log.e(ROOT_TAG, "InterruptedException executing root command: $command", e); Thread.currentThread().interrupt(); success=false }
        catch (e: Exception) { Log.e(ROOT_TAG, "Unexpected error executing root command: $command", e); success=false }
        finally {
            try { os?.close() } catch (e: IOException) { /* Ignore */ }
            process?.destroy(); Log.d(ROOT_TAG, "Process destroyed for: $command")
        }
        return success
    }
    // --- End Root Command Helper ---

} // End of StatusBarClickService class