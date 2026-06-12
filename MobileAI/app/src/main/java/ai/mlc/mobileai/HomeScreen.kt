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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val thermalInfo by appViewModel.thermalGovernor.state.collectAsState()
    val inferenceStats = appViewModel.inferenceStats.value
    val inferenceHistory = appViewModel.inferenceHistory
    var showShutdownDialog by remember { mutableStateOf(false) }
    var inferenceHistoryExpanded by remember { mutableStateOf(false) }

    // Keep last 30 CPU samples (~60s of history at 2s polling)
    val cpuHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(systemStats.cpuPercent) {
        if (systemStats.cpuPercent >= 0f) {
            cpuHistory.add(systemStats.cpuPercent)
            if (cpuHistory.size > 30) cpuHistory.removeAt(0)
        }
    }

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

            // ── System Usage Graph Card ────────────────────────────────────
            SystemUsageCard(
                cpuPercent = systemStats.cpuPercent,
                cpuHistory = cpuHistory,
                thermalInfo = thermalInfo,
                thermalAvailable = systemStats.thermalAvailable
            )

            val apiRunningForCard = service?.apiServer != null
            val apiPort = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getInt("api_port", 8080)
            val apiUrl = if (apiRunningForCard) {
                val ip = activity.getLocalIpAddress()
                "http://$ip:$apiPort"
            } else "Stopped"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (apiRunningForCard) Modifier.clickable {
                        // Recompute everything at click time — composition-time values can be stale
                        val svc = activity.getInferenceService()
                        if (svc?.apiServer == null) {
                            Toast.makeText(activity, "API server is stopped", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        val port = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getInt("api_port", 8080)
                        val url = "http://${activity.getLocalIpAddress()}:$port"
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("api_url", url))
                        // HyperOS can silently block clipboard writes — verify by reading back
                        val copied = cm.primaryClip?.getItemAt(0)?.text?.toString() == url
                        if (copied) Toast.makeText(activity, "Copied: $url", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(activity, "Copy blocked by system — long-press the URL to select it manually", Toast.LENGTH_LONG).show()
                    } else Modifier)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.Cloud, null, tint = if (apiRunningForCard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("API Server", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(apiUrl, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (apiRunningForCard) Text("tap to copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (apiRunningForCard) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        null,
                        tint = if (apiRunningForCard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            StatusCard(
                icon = Icons.Outlined.Send,
                title = "Telegram Bot",
                value = if (service?.telegramPoller != null) "Active" else "Inactive",
                ok = service?.telegramPoller != null
            )

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

@Composable
private fun SystemUsageCard(
    cpuPercent: Float,
    cpuHistory: List<Float>,
    thermalInfo: ThermalInfo,
    thermalAvailable: Boolean
) {
    val cpuColor = when {
        cpuPercent > 75f -> MaterialTheme.colorScheme.error
        cpuPercent > 50f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val thermalColor = when (thermalInfo.state) {
        ThermalState.CRITICAL -> MaterialTheme.colorScheme.error
        ThermalState.HOT      -> MaterialTheme.colorScheme.error
        ThermalState.WARM     -> MaterialTheme.colorScheme.tertiary
        ThermalState.COOL     -> MaterialTheme.colorScheme.primary
    }
    val thermalLabel = when {
        !thermalAvailable -> "N/A"
        else -> thermalInfo.state.name
    }
    val limitTag = when {
        thermalInfo.hardLimit -> "  [Hard Limit]"
        thermalInfo.softLimit -> "  [Soft Limit]"
        else -> ""
    }
    val sourceTag = if (thermalInfo.source == "Battery") "  via battery" else ""

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("System Usage", style = MaterialTheme.typography.labelMedium)

            // CPU row with graph
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Speed, null, tint = cpuColor, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (cpuPercent < 0f) "CPU  Measuring…" else "CPU  %.0f%%".format(cpuPercent),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cpuColor
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    CpuGraph(history = cpuHistory, lineColor = cpuColor)
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            // Thermal row
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Thermostat, null, tint = thermalColor, modifier = Modifier.size(20.dp))
                Text(
                    if (thermalAvailable)
                        "Thermal  $thermalLabel$limitTag  (%.0f%% headroom$sourceTag)".format(thermalInfo.headroom1s * 100)
                    else
                        "Thermal  N/A (Android < 11)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = thermalColor,
                    modifier = Modifier.weight(1f)
                )
            }
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

@Composable
private fun CpuGraph(history: List<Float>, lineColor: Color) {
    val gridColor = lineColor.copy(alpha = 0.15f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val w = size.width
        val h = size.height
        val maxSamples = 30

        // Grid lines at 25%, 50%, 75%
        listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
            val y = h - (frac * h)
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        if (history.size < 2) return@Canvas

        val step = w / (maxSamples - 1).toFloat()
        val startIdx = (maxSamples - history.size).coerceAtLeast(0)

        val path = Path()
        history.forEachIndexed { i, value ->
            val x = (startIdx + i) * step
            val y = h - (value / 100f * h).coerceIn(0f, h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}
