package com.guilnunes.herald.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val error by vm.setupError.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Herald", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Reads your Notion pages aloud. One-time setup:", style = MaterialTheme.typography.bodyLarge)
            Step("1", "Create a free Notion connection: tap Create a new connection, name it Herald, choose your workspace. If asked, pick Internal / API token, not OAuth. Capabilities: check only Read content, and choose No user information. Open it and copy its secret, labeled Internal Integration Secret or Installation access token.")
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.notion.so/profile/integrations")))
            }) { Text("Open Notion connections") }
            Step("2", "In Notion, on each top-level page you want to hear: ••• › Connections › Herald. Every page inside it is included.")
            Step("3", "Paste the secret here and tap Connect.")
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Connection secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { vm.saveToken(token) }, enabled = !checking, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (checking) CircularProgressIndicator(Modifier.padding(4.dp)) else Text("Connect")
            }
            if (vm.repo.prefs.token != null) {
                OutlinedButton(onClick = { vm.go(Screen.HOME) }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Text("$n.  $text", style = MaterialTheme.typography.bodyLarge)
}
