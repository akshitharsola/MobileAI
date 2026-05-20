package ai.mlc.mobileai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@ExperimentalMaterial3Api
@Composable
fun HomeScreen(navController: NavController, appViewModel: AppViewModel, activity: MainActivity) {
    val modelName = appViewModel.chatState.modelName.value
    val isLoaded = appViewModel.chatState.chatable()
    val ramUsed = appViewModel.ramUsageMB.value
    val ramTotal = appViewModel.totalRamMB.value

    LaunchedEffect(Unit) { appViewModel.updateRamUsage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MobileAI", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, "settings", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("System Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            StatusCard(
                icon = Icons.Outlined.Memory,
                title = "Model",
                value = if (isLoaded) modelName.substringBefore("-q4f") else "Tap 'Load Model' to start",
                ok = isLoaded
            )

            StatusCard(
                icon = Icons.Outlined.Storage,
                title = "RAM",
                value = "${ramUsed}MB / ${ramTotal}MB used",
                ok = ramUsed < ramTotal * 0.85
            )

            val service = activity.getInferenceService()
            StatusCard(
                icon = Icons.Outlined.Cloud,
                title = "API Server",
                value = if (service?.apiServer != null) "Running on :8080" else "Stopped",
                ok = service?.apiServer != null
            )

            StatusCard(
                icon = Icons.Outlined.Send,
                title = "Telegram Bot",
                value = if (service?.telegramPoller != null) "Active" else "Inactive",
                ok = service?.telegramPoller != null
            )

            Divider()
            Text("Quick Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { navController.navigate("models") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.List, null, modifier = Modifier.padding(end = 4.dp))
                    Text("Models")
                }
                Button(
                    onClick = {
                        if (isLoaded) navController.navigate("chat")
                        else navController.navigate("models")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Chat, null, modifier = Modifier.padding(end = 4.dp))
                    Text(if (isLoaded) "Chat" else "Load Model")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val apiRunning = service?.apiServer != null
                OutlinedButton(
                    onClick = {
                        if (apiRunning) {
                            service?.apiServer?.stop()
                            service?.apiServer = null
                        } else {
                            val prefs = activity.getSharedPreferences("mobileai", android.content.Context.MODE_PRIVATE)
                            service?.startApiServer(prefs.getInt("api_port", 8080))
                        }
                        appViewModel.updateRamUsage()
                    },
                    enabled = service != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (apiRunning) "Stop API" else "Start API")
                }
                val botRunning = service?.telegramPoller != null
                OutlinedButton(
                    onClick = {
                        if (botRunning) {
                            service?.stopTelegramPoller()
                        } else {
                            val prefs = activity.getSharedPreferences("mobileai", android.content.Context.MODE_PRIVATE)
                            val token = prefs.getString("bot_token", "") ?: ""
                            if (token.isNotBlank()) service?.startTelegramPoller(token)
                        }
                        appViewModel.updateRamUsage()
                    },
                    enabled = service != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (botRunning) "Stop Bot" else "Start Bot")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "MobileAI — Distributed Edge AI Node\nBased on MLC-LLM (Apache 2.0)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(icon: ImageVector, title: String, value: String, ok: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
