package com.guilnunes.herald.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import digital.brander.theme.herald.ArmatureTheme
import digital.brander.theme.herald.armatureTypography

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            HeraldTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                when (screen) {
                    Screen.SETUP -> SetupScreen(vm)
                    Screen.HOME -> HomeScreen(vm)
                    Screen.READER -> {
                        BackHandler { vm.go(Screen.HOME) }
                        ReaderScreen(vm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        if (!vm.handleShared(text)) {
            android.widget.Toast.makeText(this, "That doesn't look like a Notion page link.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Herald uses the Scriptorium family theme (Brander, built with Armature), with Azure as its primary.
 * Colors and type come from the generated ArmatureTheme.kt; refresh it with tools/sync-armature-theme.sh.
 * The type scale keeps Herald's large text: body 20sp (bodyLarge) and 18sp (bodyMedium).
 * Scriptorium does not use Android wallpaper colors; dark theme and system font size are always honored.
 */
@Composable
fun HeraldTheme(content: @Composable () -> Unit) {
    ArmatureTheme(
        typography = armatureTypography(display = ScriptoriumFonts.display, ui = ScriptoriumFonts.ui),
        content = content,
    )
}
