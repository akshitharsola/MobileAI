package ai.mlc.mobileai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ApiCardState { STOPPED, STARTING, RUNNING_NO_MODEL, RUNNING_READY }

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
    val inferenceStats = appViewModel.inferenceStats.value
    val inferenceHistory = appViewModel.inferenceHistory
    var showShutdownDialog by remember { mutableStateOf(false) }
    var inferenceHistoryExpanded by remember { mutableStateOf(false) }

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

            val reloadingModel = appViewModel.chatState.isReloading()
            StatusCardWithSubtitle(
                icon = Icons.Outlined.Memory,
                title = "Model",
                value = when {
                    offloading -> "Offloading…"
                    reloadingModel -> "Loading ${modelName.substringBefore("-q4f")}…"
                    isLoaded -> modelName.substringBefore("-q4f")
                    else -> "No model loaded"
                },
                subtitle = "${ramUsed}MB / ${ramTotal}MB RAM",
                ok = isLoaded && !offloading
            )

            val apiServerStarting = service?.apiServerStarting == true
            val apiServerObj = service?.apiServer
            val modelLoadedForCard = service?.isModelLoaded() == true
            val apiPort = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getInt("api_port", 8080)
            val manualIp = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getString("manual_ip", "") ?: ""

            val apiCardState = when {
                apiServerObj == null && !apiServerStarting -> ApiCardState.STOPPED
                apiServerStarting -> ApiCardState.STARTING
                apiServerObj != null && !modelLoadedForCard -> ApiCardState.RUNNING_NO_MODEL
                else -> ApiCardState.RUNNING_READY
            }

            val resolvedIp = manualIp.ifBlank { activity.getLocalIpAddress() }
            val apiUrl = when (apiCardState) {
                ApiCardState.STOPPED -> "Stopped"
                ApiCardState.STARTING -> "Starting…"
                else -> "http://$resolvedIp:$apiPort"
            }

            val cardTint = when (apiCardState) {
                ApiCardState.STOPPED -> MaterialTheme.colorScheme.error
                ApiCardState.STARTING -> MaterialTheme.colorScheme.onSurfaceVariant
                ApiCardState.RUNNING_NO_MODEL -> MaterialTheme.colorScheme.tertiary
                ApiCardState.RUNNING_READY -> MaterialTheme.colorScheme.primary
            }

            val statusLabel = when (apiCardState) {
                ApiCardState.STOPPED -> "Stopped"
                ApiCardState.STARTING -> "Starting…"
                ApiCardState.RUNNING_NO_MODEL -> "Running — load a model to use · tap to copy"
                ApiCardState.RUNNING_READY -> "Running · tap to copy"
            }

            var selfTestResult by remember { mutableStateOf<String?>(null) }
            val coroutineScopeForTest = rememberCoroutineScope()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (apiCardState == ApiCardState.RUNNING_READY || apiCardState == ApiCardState.RUNNING_NO_MODEL) Modifier.clickable {
                        // Recompute everything at click time — composition-time values can be stale
                        val svc = activity.getInferenceService()
                        if (svc?.apiServer == null) {
                            Toast.makeText(activity, "API server is stopped", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        val port = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getInt("api_port", 8080)
                        val manual = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getString("manual_ip", "") ?: ""
                        val ip = manual.ifBlank { activity.getLocalIpAddress() }
                        val url = "http://$ip:$port"
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("api_url", url))
                        // HyperOS can silently block clipboard writes — verify by reading back
                        val copied = cm.primaryClip?.getItemAt(0)?.text?.toString() == url
                        if (copied) Toast.makeText(activity, "Copied: $url", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(activity, "Copy blocked by system — long-press the URL to select it manually", Toast.LENGTH_LONG).show()
                    } else Modifier)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Outlined.Cloud, null, tint = cardTint)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("API Server", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(apiUrl, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = cardTint)
                        }
                        if (apiCardState == ApiCardState.RUNNING_NO_MODEL || apiCardState == ApiCardState.RUNNING_READY) {
                            IconButton(onClick = {
                                coroutineScopeForTest.launch {
                                    selfTestResult = "Testing…"
                                    val ok = service?.testApiServerLoopback(apiPort) ?: false
                                    selfTestResult = if (ok) {
                                        "Server OK locally. If another device can't connect: confirm it's on the same Wi-Fi network, check your router's AP/client isolation setting, and check background data restrictions for this app."
                                    } else {
                                        "Server not responding locally — try restarting it."
                                    }
                                }
                            }) {
                                Icon(Icons.Outlined.NetworkCheck, "test connection", tint = cardTint)
                            }
                        }
                        Icon(
                            if (apiCardState == ApiCardState.RUNNING_READY) Icons.Filled.CheckCircle
                            else if (apiCardState == ApiCardState.STARTING) Icons.Filled.Schedule
                            else Icons.Filled.Cancel,
                            null,
                            tint = cardTint
                        )
                    }
                    selfTestResult?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Inference History ──────────────────────────────────────────
            if (inferenceHistory.isNotEmpty()) {
                HorizontalDivider()
                Text("Inference", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                InferenceHistoryCard(
                    history = inferenceHistory,
                    expanded = inferenceHistoryExpanded,
                    onToggle = { inferenceHistoryExpanded = !inferenceHistoryExpanded }
                )
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
                                    val reloadingThis = appViewModel.chatState.isReloading() && modelName == m.modelConfig.modelId
                                    Button(
                                        onClick = { m.startChat() },
                                        enabled = !reloadingThis,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        if (reloadingThis) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Loading…", style = MaterialTheme.typography.labelMedium)
                                        } else {
                                            Text("Load", style = MaterialTheme.typography.labelMedium)
                                        }
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
                    Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.padding(end = 4.dp))
                    Text("Models")
                }
                val reloading = appViewModel.chatState.isReloading()
                Button(
                    onClick = {
                        if (isLoaded) navController.navigate("chat")
                        else navController.navigate("models")
                    },
                    enabled = !reloading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (reloading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Loading…")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.padding(end = 4.dp))
                        Text(if (isLoaded) "Chat" else "Load Model")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val apiRunning = service?.apiServer != null
                OutlinedButton(
                    onClick = {
                        val svc = service ?: return@OutlinedButton
                        if (apiRunning) {
                            svc.apiServer?.stop()
                            svc.apiServer = null
                        } else {
                            val prefs = activity.getSharedPreferences("mobileai", android.content.Context.MODE_PRIVATE)
                            svc.startApiServer(prefs.getInt("api_port", 8080))
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
                        val svc = service ?: return@OutlinedButton
                        if (botRunning) {
                            svc.stopTelegramPoller()
                        } else {
                            val prefs = activity.getSharedPreferences("mobileai", android.content.Context.MODE_PRIVATE)
                            val token = prefs.getString("bot_token", "") ?: ""
                            if (token.isNotBlank()) svc.startTelegramPoller(token)
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
                "Localis — Distributed Edge AI Node\nPowered by Google LiteRT-LM",
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

@Composable
private fun StatusCardWithSubtitle(icon: ImageVector, title: String, value: String, subtitle: String, ok: Boolean) {
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
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun InferenceHistoryCard(
    history: List<InferenceStats>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Header row — always visible, shows latest entry summary + expand toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.Speed, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    val latest = history.first()
                    Text(
                        "%.1f tok/s  •  ${latest.completionTokens} tokens".format(latest.tokensPerSecond),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "TTFT ${latest.timeToFirstTokenMs}ms  •  ${timeFormat.format(Date(latest.lastRequestMs))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${history.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded history list
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(thickness = 0.5.dp)
                    history.forEachIndexed { idx, entry ->
                        if (idx == 0) return@forEachIndexed  // already shown in header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "#${idx + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "%.1f tok/s  •  ${entry.completionTokens} tok out  •  ${entry.promptTokens} tok in".format(entry.tokensPerSecond),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "TTFT ${entry.timeToFirstTokenMs}ms  •  ${timeFormat.format(Date(entry.lastRequestMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (idx < history.size - 1) HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }
}

