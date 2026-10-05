package dev.nkmason.smsserverandroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SmsServerTheme {
                SmsServerScreen()
            }
        }
    }
}

@Composable
fun SmsServerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { content() }
}

fun openSmsApp(context: Context) {
    val mainIntent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_APP_MESSAGING)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(mainIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsServerScreen() {
    val context = LocalContext.current
    val state by ServerManager.state.collectAsState()

    var portText by remember { mutableStateOf(ServerManager.savedPort(context).toString()) }
    var tokenText by remember { mutableStateOf(ServerManager.savedToken(context)) }
    var portError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> ServerManager.updateSmsGranted(granted) }

    LaunchedEffect(Unit) {
        if (ServerManager.smsGranted(context)) {
            ServerManager.updateSmsGranted(true)
        } else {
            permissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    fun applyAndStart() {
        val port = portText.toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            portError = "Use a port between 1024 and 65535"
            return
        }
        portError = null
        ServerManager.savePort(context, port)
        ServerManager.saveToken(context, tokenText.trim())
        SmsService.startServer(context, port)
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("SMS Server") }) }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(state)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { raw ->
                            portText = raw.filter { it.isDigit() }.take(5)
                            portError = null
                        },
                        label = { Text("Port") },
                        isError = portError != null,
                        supportingText = {
                            Text(portError ?: "Default: $DEFAULT_PORT (1024–65535)")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = { tokenText = it },
                        label = { Text("API token (optional)") },
                        supportingText = { Text("If set, requests need Authorization or X-API-Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { applyAndStart() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (state.running) "Restart server" else "Start server")
                        }
                        if (state.running) {
                            OutlinedButton(
                                onClick = { SmsService.stopServer(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Stop")
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { openSmsApp(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open SMS app")
            }

            if (!state.smsGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "SEND_SMS permission is needed before the API can send.",
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.SEND_SMS)
                        }) {
                            Text("Grant")
                        }
                    }
                }
            }

            LogCard(state)
        }
    }
}

@Composable
private fun StatusCard(state: ServerState) {
    val ip = remember(state.running) { ServerManager.localIpAddress() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (state.running) Color(0xFF4CAF50) else Color(0xFFB0B0B0))
                )
                Text(
                    if (state.running) "RUNNING" else "STOPPED",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "Sent ${state.sentOk} · Failed ${state.sentFailed}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }
            if (state.running) {
                Text(
                    "http://$ip:${state.port}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "POST /send · GET /status · GET /log",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun LogCard(state: ServerState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recent SMS", style = MaterialTheme.typography.titleSmall)
            if (state.log.isEmpty()) {
                Text(
                    "Nothing sent yet",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                state.log.takeLast(30).reversed().forEach { entry ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${entry.timeText}  ${entry.to}${if (entry.ok) "" else "  ✗ ${entry.detail}"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (entry.ok) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}
