package com.tarteelcompanion

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tarteelcompanion.ui.TarteelCompanionApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            TarteelCompanionApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** ACTION_SEND(_MULTIPLE) image shares queue for the Import screen (R1/I4). */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.type?.startsWith("image/") != true) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableList(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            val app = application as TarteelApp
            app.pendingShares.value = app.pendingShares.value + uris
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelable(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
private fun Intent.parcelableList(key: String): List<Uri>? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(key, Uri::class.java)
    } else {
        getParcelableArrayListExtra(key)
    }
