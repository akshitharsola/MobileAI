package ai.mlc.mobileai

import androidx.compose.foundation.BorderStroke
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
import kotlinx.coroutines.delay

@ExperimentalMaterial3Api
@Composable
fun HomeScreen(navController: NavController, appViewModel: AppViewModel, activity: MainActivity) {
    val modelName = appViewModel.chatState.modelName.value
    val isLoaded = appViewModel.chatState.chatable()
    val ramUsed = appViewModel.ramUsageMB.value
    val ramTotal = appViewModel.totalRamMB.value
    val modelList = appViewModel.modelList
    val service = activity.getInferenceService()
    var offloading by remember { mutableStateOf(false) }
    val systemStats by appViewModel.systemMonitor.stats.collectAsState()
    val inferenceStats = appViewModel.inferenceStats.value
    var showShutdownDialog by remember { mutableStateOf(false) }

    // Poll RAM every 3s
    LaunchedEffect(Unit) {
        while (true) {
            appViewModel.updateRamUsage()
            delay(3000)
        }
    }

    // Clear offloading spinner once model is unloaded
    LaunchedEffect(isLoaded) {
        if (!isLoaded) offloading = false
    }

    if (showShutdownDialog) {
        AlertDialog(
            onDismissRequest = { showShutdownDialog = false },
            title = { Text("Shut Down Localis?") },
            text = { Text("This will stop the model, API server, and Telegram bot. The service will not restart until you open the app again.") },
            confirmButton = {
                TextButton(onClick = { activity.shutdownAndFinish() }) {
                    Text("Shut Down", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Localis", color = MaterialTheme.colorScheme.onPrimary) },
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
            // ── System Status ──────────────────────────────────────────────
            Text("System Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            StatusCard(
                icon = Icons.Outlined.Memory,
                title = "Model",
                value = when {
                    offloading -> "Offloading…"
                    isLoaded -> modelName.substringBefore("-q4f")
                    else -> "No model loaded"
                },
                ok = isLoaded && !offloading
            )

            StatusCard(
                icon = Icons.Outlined.Storage,
                title = "RAM",
                value = "${ramUsed}MB / ${ramTotal}MB used",
                ok = ramUsed < ramTotal * 0.85
            )

            val cpuPct = systemStats.cpuPercent
            StatusCard(
                icon = Icons.Outlined.Speed,
                title = "CPU",
                value = if (cpuPct < 0f) "Measuring…" else "%.0f%% used".format(cpuPct),
                ok = cpuPct < 90f
            )

            if (systemStats.thermalAvailable) {
                val headroom = systemStats.thermalHeadroom
                val thermalLabel = when {
                    headroom > 0.5f -> "Normal (%.0f%% headroom)".format(headroom * 100)
                    headroom > 0.15f -> "Warm (%.0f%% headroom)".format(headroom * 100)
                    else -> "Throttling!"
                }
                StatusCard(
                    icon = Icons.Outlined.Thermostat,
                    title = "Thermal",
                    value = thermalLabel,
                    ok = headroom > 0.15f
                )
            }

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

            // ── Last Inference Stats ───────────────────────────────────────
            if (inferenceStats.lastRequestMs > 0L) {
                HorizontalDivider()
                Text("Last Inference", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val secsAgo = (System.currentTimeMillis() - inferenceStats.lastRequestMs) / 1000
                        Text(
                            "%.1f tokens/sec".format(inferenceStats.tokensPerSecond),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Prompt: ${inferenceStats.promptTokens} tok  •  Completion: ${inferenceStats.completionTokens} tok",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "First token: ${inferenceStats.timeToFirstTokenMs}ms  •  ${secsAgo}s ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Model Selector ─────────────────────────────────────────────
            val downloadedModels = modelList.filter { it.modelInitState.value == ModelInitState.Finished }
            if (downloadedModels.isNotEmpty()) {
                HorizontalDivider()
                Text("Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        downloadedModels.forEach { m ->
                            val shortName = m.modelConfig.modelId.substringBefore("-q4f")
                            val vram = m.estimatedVramGB()
                            val isActive = isLoaded && modelName == m.modelConfig.modelId
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    if (isActive) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                    null,
                                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(shortName, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                                    Text("~${vram}GB VRAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isActive) {
                                    OutlinedButton(
                                        onClick = {
                                            offloading = true
                                            service?.offloadModel()
                                        },
                                        enabled = !offloading,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        if (offloading) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Offloading…", style = MaterialTheme.typography.labelMedium)
                                        } else {
                                            Text("Offload", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { m.startChat() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Load", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Quick Actions ──────────────────────────────────────────────
            HorizontalDivider()
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
                    },
                    enabled = service != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (botRunning) "Stop Bot" else "Start Bot")
                }
            }

            OutlinedButton(
                onClick = { showShutdownDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.PowerSettingsNew, null, modifier = Modifier.padding(end = 4.dp))
                Text("Shut Down")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Localis — Distributed Edge AI Node\nBased on MLC-LLM (Apache 2.0)",
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
