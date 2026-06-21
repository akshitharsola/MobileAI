// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
// Extended by Akshit Harsola — renamed package, kept model manager UI
package ai.mlc.mobileai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.res.painterResource
import ai.localis.app.R
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@ExperimentalMaterial3Api
@Composable
fun StartView(navController: NavController, appViewModel: AppViewModel) {
    val localFocusManager = LocalFocusManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models", color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { localFocusManager.clearFocus() })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 10.dp)
        ) {
            Text(
                text = "Tap download to fetch a model. Load and chat from the Home screen.",
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
            val grouped = appViewModel.modelList.groupBy { familyOf(it.modelConfig.modelId) }
            val familyOrder = listOf("Gemma", "Qwen", "DeepSeek", "Other")
            LazyColumn {
                familyOrder.forEach { family ->
                    val models = grouped[family] ?: return@forEach
                    item(key = "header-$family") {
                        Text(
                            text = family,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(items = models, key = { it.id }) { modelState ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 14.dp)) {
                            ModelBadge(family = family)
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                ModelView(navController = navController, modelState = modelState, appViewModel = appViewModel)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        if (appViewModel.isShowingAlert()) {
            AlertDialog(
                onDismissRequest = { appViewModel.dismissAlert() },
                onConfirmation = { appViewModel.copyError() },
                error = appViewModel.errorMessage()
            )
        }
    }
}

private fun familyOf(modelId: String): String = when {
    modelId.startsWith("Gemma", ignoreCase = true) -> "Gemma"
    modelId.startsWith("Qwen", ignoreCase = true) -> "Qwen"
    modelId.startsWith("DeepSeek", ignoreCase = true) -> "DeepSeek"
    else -> "Other"
}

@Composable
private fun ModelBadge(family: String) {
    val logoRes = when (family) {
        "Gemma" -> R.drawable.ic_model_gemma
        "Qwen" -> R.drawable.ic_model_qwen
        "DeepSeek" -> R.drawable.ic_model_deepseek
        else -> null
    }
    if (logoRes != null) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(logoRes),
                contentDescription = family,
                modifier = Modifier.size(40.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.outline, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("?", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@ExperimentalMaterial3Api
@Composable
fun AlertDialog(onDismissRequest: () -> Unit, onConfirmation: () -> Unit, error: String) {
    AlertDialog(
        title = { Text("Error") },
        text = { Text(error) },
        onDismissRequest = { onDismissRequest() },
        confirmButton = { TextButton(onClick = { onConfirmation() }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = { onDismissRequest() }) { Text("Dismiss") } }
    )
}

@Composable
fun ModelView(navController: NavController, modelState: AppViewModel.ModelState, appViewModel: AppViewModel) {
    var isDeletingModel by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.wrapContentHeight()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        ) {
            Column(modifier = Modifier.weight(8f)) {
                Text(text = modelState.modelConfig.modelId, textAlign = TextAlign.Left, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "~${modelState.estimatedVramGB()} GB",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            VerticalDivider(modifier = Modifier.height(20.dp).width(1.dp))
            when (modelState.modelInitState.value) {
                ModelInitState.Paused -> IconButton(onClick = { modelState.handleStart() }, modifier = Modifier.aspectRatio(1f).weight(1f)) {
                    Icon(Icons.Outlined.Download, "download")
                }
                ModelInitState.Downloading -> IconButton(onClick = { modelState.handlePause() }, modifier = Modifier.aspectRatio(1f).weight(1f)) {
                    Icon(Icons.Outlined.Pause, "pause")
                }
                ModelInitState.Finished -> IconButton(onClick = { isDeletingModel = true }, modifier = Modifier.aspectRatio(1f).weight(1f)) {
                    Icon(Icons.Outlined.Delete, "delete", tint = MaterialTheme.colorScheme.error)
                }
                else -> IconButton(enabled = false, onClick = {}, modifier = Modifier.aspectRatio(1f).weight(1f)) {
                    Icon(Icons.Outlined.Schedule, "pending")
                }
            }
        }
        LinearProgressIndicator(
            progress = { modelState.progress.value.toFloat() / modelState.total.value },
            modifier = Modifier.fillMaxWidth()
        )
        if (isDeletingModel) {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            ) {
                TextButton(onClick = { isDeletingModel = false }) { Text("cancel") }
                TextButton(onClick = { isDeletingModel = false; modelState.handleClear() }) {
                    Text("clear data", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { isDeletingModel = false; modelState.handleDelete() }) {
                    Text("delete files", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
