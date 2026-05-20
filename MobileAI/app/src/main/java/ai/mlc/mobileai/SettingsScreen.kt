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
fun SettingsScreen(navController: NavController, activity: MainActivity) {
    val prefs = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE)
    var botToken by remember { mutableStateOf(prefs.getString("bot_token", "") ?: "") }
    var apiPort by remember { mutableStateOf(prefs.getInt("api_port", 8080).toString()) }
    var contextLen by remember { mutableStateOf(prefs.getInt("context_len", 2048).toString()) }
    var showToken by remember { mutableStateOf(false) }
    var savedMsg by remember { mutableStateOf("") }

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

            Button(
                onClick = {
                    prefs.edit()
                        .putString("bot_token", botToken.trim())
                        .putInt("api_port", apiPort.toIntOrNull() ?: 8080)
                        .putInt("context_len", contextLen.toIntOrNull() ?: 2048)
                        .apply()
                    savedMsg = "Settings saved"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Settings") }

            if (savedMsg.isNotEmpty()) {
                Text(savedMsg, color = MaterialTheme.colorScheme.primary)
            }

            Divider()
            Text(
                "About\n\nMobileAI v1.0\nDistributed Edge LLM Inference Node\n\nBased on MLCChat from the MLC-LLM project\nCopyright (c) 2023 MLC LLM Team\nLicensed under Apache 2.0\n\nExtensions Copyright (c) 2026 Akshit Harsola",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
