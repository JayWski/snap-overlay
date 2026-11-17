package app.snapoverlay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Tapping the app icon lands here. It only turns on the overlay (asking once for the
 * "Display over other apps" permission) and then gets out of the way. Everything else
 * happens in the overlay.
 */
class MainActivity : Activity() {

    private var sentToSettings = false
    private var asking = false

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            OverlayService.open(this)
            finish()
            return
        }
        if (sentToSettings) {
            // Came back from settings without turning it on.
            finish()
            return
        }
        if (asking) return
        asking = true
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("One-time permission")
            .setMessage("Snap Overlay floats over Marvel Snap, so Android needs you to allow " +
                "\"Display over other apps\".\n\nOn the next screen, turn the switch on for Snap Overlay, " +
                "then press back.")
            .setPositiveButton("Open settings") { _, _ ->
                sentToSettings = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
