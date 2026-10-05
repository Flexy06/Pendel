package de.flexy.pendel.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

object Perms {
    fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackgroundLocation(context: Context) = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun hasActivityRecognition(context: Context) = granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    /** Everything needed for a manual recording (foreground). */
    fun recordingPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}

/**
 * Returns a function that ensures foreground location (+ notifications) and then runs [onReady].
 */
@Composable
fun rememberRecordingPermission(context: Context, onReady: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || Perms.hasFineLocation(context)) onReady() else onDenied()
    }
    return {
        if (Perms.hasFineLocation(context)) onReady() else launcher.launch(Perms.recordingPermissions())
    }
}

/**
 * Auto-detection needs: activity recognition, foreground location, then *background* location
 * (Android requires asking for it separately; the system shows "Allow all the time" in settings).
 */
@Composable
fun rememberAutoDetectPermission(context: Context, onResult: (Boolean) -> Unit): () -> Unit {
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onResult(ok && Perms.hasActivityRecognition(context))
    }
    val first = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Perms.hasFineLocation(context) && Perms.hasActivityRecognition(context)) {
            if (Perms.hasBackgroundLocation(context)) onResult(true)
            else background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else onResult(false)
    }
    return {
        when {
            Perms.hasFineLocation(context) && Perms.hasActivityRecognition(context) && Perms.hasBackgroundLocation(context) -> onResult(true)
            Perms.hasFineLocation(context) && Perms.hasActivityRecognition(context) -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> first.launch(
                (Perms.recordingPermissions() + Manifest.permission.ACTIVITY_RECOGNITION),
            )
        }
    }
}
