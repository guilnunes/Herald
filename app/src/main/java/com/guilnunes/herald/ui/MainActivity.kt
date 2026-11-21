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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

private val BigType = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 20.sp, lineHeight = 30.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
        titleLarge = t.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp),
        titleMedium = t.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp),
        labelLarge = t.labelLarge.copy(fontSize = 17.sp),
    )
}

@Composable
fun HeraldTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFFFC857), secondary = Color(0xFF9AD1D4))
        else -> lightColorScheme(primary = Color(0xFF7A4E00), secondary = Color(0xFF1D5C63))
    }
    MaterialTheme(colorScheme = scheme, typography = BigType, content = content)
}
