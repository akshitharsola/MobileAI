package ai.mlc.mobileai

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var contextLen by remember { mutableStateOf(prefs.getInt("context_len", 2048).toString()) }
    var showToken by remember { mutableStateOf(false) }
    var savedMsg by remember { mutableStateOf("") }
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
                        Icon(Icons.Filled.ArrowBack, "back", tint = MaterialTheme.colorScheme.onPrimary)
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

            Divider()
            Text("API Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiPort,
                onValueChange = { apiPort = it.filter { c -> c.isDigit() } },
                label = { Text("API Port (default 8080)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Divider()
            Text("Inference", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = contextLen,
                onValueChange = { contextLen = it.filter { c -> c.isDigit() } },
                label = { Text("Context Length (e.g. 2048)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (modelIds.isNotEmpty()) {
                Divider()
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
                        .putString("bot_token", newToken)
                        .putInt("api_port", newPort)
                        .putInt("context_len", contextLen.toIntOrNull() ?: 2048)
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

            Divider()
            Text(
                "About\n\nLocalis v2.4.2\nDistributed Edge LLM Inference Node\n\nBased on MLCChat from the MLC-LLM project\nCopyright (c) 2023 MLC LLM Team\nLicensed under Apache 2.0\n\nExtensions Copyright (c) 2026 Akshit Harsola",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
