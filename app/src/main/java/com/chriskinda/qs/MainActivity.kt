package com.chriskinda.qs // Verify Package Name

import android.content.ActivityNotFoundException
import android.content.ComponentName // Needed for specific activity launch
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast // Use standard Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivityQOL"
        // Package name for your 3rd party manager
        private const val ACCESSIBILITY_MANAGER_PACKAGE = "com.wolf.apm"
        // Specific Activity found via Activity Launcher
        private const val ACCESSIBILITY_MANAGER_ACTIVITY = "com.wolf.apm.ManagerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        // Check permissions and decide which UI to show or action to take
        checkOverlayPermissionAndProceed()
    }

    private fun checkOverlayPermissionAndProceed() {
        // Check for SYSTEM_ALERT_WINDOW permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Permission NOT granted - Show instructions layout
            Log.w(TAG, "Overlay permission NOT granted. Showing instructions layout.")
            setContentView(R.layout.activity_main_perms) // Ensure this layout exists

            val btnCheck = findViewById<Button>(R.id.btnCheckPermission)
            btnCheck?.setOnClickListener {
                Log.d(TAG, "'Check Permission Granted' button clicked.")
                // Re-run the check when the button is clicked
                checkOverlayPermissionAndProceed()
            }
        } else {
            // Permission IS granted (or not needed pre-M) - Proceed to launch Accessibility Manager
            Log.i(TAG, "Overlay permission is granted. Proceeding to launch Accessibility Manager.")

            // Launch the specific manager activity
            launchAccessibilityManager()

            // Finish MainActivity after attempting to launch the manager
            // User will enable service in the manager and then use the app normally
            finish()
            Log.d(TAG, "MainActivity finished.")
        }
    }

    private fun launchAccessibilityManager() {
        Log.d(TAG, "Attempting to launch specific Accessibility Manager Activity...")
        val intent = Intent().apply {
            // Set ComponentName to launch specific activity
            component = ComponentName(ACCESSIBILITY_MANAGER_PACKAGE, ACCESSIBILITY_MANAGER_ACTIVITY)
            // Add flags to start it as a new task
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        try {
            startActivity(intent)
            // Use standard Toast correctly
            Toast.makeText(this, "Please find and enable the 'Flip Quick Settings' service in the manager app.", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            // This happens if com.wolf.apm or com.wolf.apm.ManagerActivity doesn't exist
            Log.e(TAG, "Could not launch $ACCESSIBILITY_MANAGER_PACKAGE / $ACCESSIBILITY_MANAGER_ACTIVITY.", e)
            // Use standard Toast correctly
            Toast.makeText(this, "Required manager app ($ACCESSIBILITY_MANAGER_PACKAGE) not found. Please install it.", Toast.LENGTH_LONG).show()
            // Keep MainActivity open in this error case so user sees the message
            // Optionally set content view back to instructions or an error message?
            setContentView(R.layout.activity_main) // Show blank main layout for now
        } catch (e: Exception) {
            // Catch any other unexpected errors during launch
            Log.e(TAG, "Error launching Accessibility Manager", e)
            // Use standard Toast correctly
            Toast.makeText(this, "Error opening Accessibility Manager.", Toast.LENGTH_SHORT).show()
            setContentView(R.layout.activity_main) // Show blank main layout
        }
    } // End launchAccessibilityManager

} // End MainActivity