package ai.mlc.mobileai

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@ExperimentalMaterial3Api
@Composable
fun SettingsScreen(navController: NavController, activity: MainActivity, appViewModel: AppViewModel) {
    val prefs = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE)
    var botToken by remember { mutableStateOf(prefs.getString("bot_token", "") ?: "") }
    var apiPort by remember { mutableStateOf(prefs.getInt("api_port", 8080).toString()) }
    var maxTokens by remember { mutableStateOf(prefs.getInt("max_tokens", 2048).toString()) }
    var noThink by remember { mutableStateOf(prefs.getBoolean("no_think", false)) }
    var thinkMaxTokens by remember { mutableStateOf(prefs.getInt("think_max_tokens", 1024).toString()) }
    var showToken by remember { mutableStateOf(false) }
    var savedMsg by remember { mutableStateOf("") }
    var hfToken by remember { mutableStateOf(prefs.getString("hf_token", "") ?: "") }
    var showHfToken by remember { mutableStateOf(false) }
    val service = activity.getInferenceService()

    val downloadedModels = appViewModel.modelList.filter { it.modelInitState.value == ModelInitState.Finished }
    val modelIds = downloadedModels.map { it.modelConfig.modelId }
    var defaultModel by remember {
        mutableStateOf(prefs.getString("default_model", modelIds.firstOrNull() ?: "") ?: "")
    }
    var defaultModelExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = MaterialTheme.colorScheme.onPrimary)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("HuggingFace", style = MaterialTheme.typography.titleMedium)
            Text(
                "Required to download models from litert-community (accept license on huggingface.co first)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = hfToken,
                onValueChange = { hfToken = it },
                label = { Text("HF Token (hf_…)") },
                visualTransformation = if (showHfToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showHfToken = !showHfToken }) {
                        Text(if (showHfToken) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()
            Text("Telegram Bot", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = botToken,
                onValueChange = { botToken = it },
                label = { Text("Bot Token (from @BotFather)") },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(if (showToken) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()
            Text("API Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiPort,
                onValueChange = { apiPort = it.filter { c -> c.isDigit() } },
                label = { Text("API Port (default 8080)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()
            Text("Inference", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                label = { Text("Max output tokens (256–4096)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = thinkMaxTokens,
                onValueChange = { thinkMaxTokens = it.filter { c -> c.isDigit() } },
                label = { Text("Think budget tokens (256–2048)") },
                supportingText = { Text("Max tokens Qwen3 spends in <think> before being forced to answer") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Disable thinking (/no_think)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Qwen3 answers directly, skipping the <think> phase",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = noThink, onCheckedChange = { noThink = it })
            }

            if (modelIds.isNotEmpty()) {
                HorizontalDivider()
                Text("Default Model", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Used when a request arrives and no model is loaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExposedDropdownMenuBox(
                    expanded = defaultModelExpanded,
                    onExpandedChange = { defaultModelExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = defaultModel.substringBefore("-q4f").ifBlank { "Select model" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = defaultModelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = defaultModelExpanded,
                        onDismissRequest = { defaultModelExpanded = false }
                    ) {
                        modelIds.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(id.substringBefore("-q4f")) },
                                onClick = { defaultModel = id; defaultModelExpanded = false }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val newToken = botToken.trim()
                    val newPort = apiPort.toIntOrNull() ?: 8080
                    prefs.edit()
                        .putString("hf_token", hfToken.trim())
                        .putString("bot_token", newToken)
                        .putInt("api_port", newPort)
                        .putInt("max_tokens", (maxTokens.toIntOrNull() ?: 2048).coerceIn(256, 4096))
                        .putInt("think_max_tokens", (thinkMaxTokens.toIntOrNull() ?: 1024).coerceIn(256, 2048))
                        .putBoolean("no_think", noThink)
                        .remove("context_len")
                        .putString("default_model", defaultModel)
                        .apply()
                    // Restart bot immediately if token changed and service is bound
                    if (newToken.isNotBlank() && service != null) {
                        service.stopTelegramPoller()
                        service.startTelegramPoller(newToken)
                        savedMsg = "Settings saved — Telegram bot restarted"
                    } else {
                        savedMsg = "Settings saved"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save & Apply") }

            if (savedMsg.isNotEmpty()) {
                Text(savedMsg, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider()
            Text(
                "About\n\nLocalis v4.0\nDistributed Edge LLM Inference Node\n\nPowered by Google LiteRT-LM\nBased on MLCChat from the MLC-LLM project\nCopyright (c) 2023 MLC LLM Team\nLicensed under Apache 2.0\n\nExtensions Copyright (c) 2026 Akshit Harsola",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
