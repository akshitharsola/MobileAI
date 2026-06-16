// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
// Extended by Akshit Harsola
package ai.mlc.mobileai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch

@ExperimentalMaterial3Api
@Composable
fun ChatView(navController: NavController, chatState: AppViewModel.ChatState, activity: Activity) {
    val localFocusManager = LocalFocusManager.current
    (activity as MainActivity).chatState = chatState
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Localis: " + chatState.modelName.value.split("-")[0], color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, enabled = chatState.interruptable()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { chatState.requestResetChat(); activity.hasImage = false }, enabled = chatState.interruptable()) {
                        Icon(Icons.Filled.Replay, "reset", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { localFocusManager.clearFocus() }) }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 10.dp)) {
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            if (chatState.modelName.value.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No model loaded.\nGo to Models and tap the chat icon to load one first.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp)
                    )
                }
                return@Scaffold
            }
            Text(text = chatState.report.value, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(top = 5.dp))
            HorizontalDivider(thickness = 1.dp, modifier = Modifier.padding(vertical = 5.dp))
            LazyColumn(
                modifier = Modifier.weight(9f),
                verticalArrangement = Arrangement.spacedBy(5.dp, alignment = Alignment.Bottom),
                state = listState
            ) {
                coroutineScope.launch { listState.animateScrollToItem(chatState.messages.size) }
                items(items = chatState.messages, key = { it.id }) { message ->
                    MessageView(messageData = message, activity)
                }
                item {}
            }
            HorizontalDivider(thickness = 1.dp, modifier = Modifier.padding(top = 5.dp))
            SendMessageView(chatState = chatState, activity)
        }
    }
}

@Composable
fun MessageView(messageData: MessageData, activity: Activity?) {
    var useMarkdown by remember { mutableStateOf(true) }
    SelectionContainer {
        if (messageData.role == MessageRole.Assistant) {
            Column {
                if (messageData.thinkContent.isNotEmpty() || messageData.isThinkOpen) {
                    ThinkCard(messageData)
                }
                if (messageData.text.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Markdown", color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.wrapContentWidth().padding(end = 8.dp).widthIn(max = 300.dp))
                        Switch(checked = useMarkdown, onCheckedChange = { useMarkdown = it })
                    }
                }
                Row(horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth()) {
                    if (useMarkdown) {
                        MarkdownText(
                            isTextSelectable = true,
                            modifier = Modifier.wrapContentWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(5.dp)).padding(5.dp).widthIn(max = 300.dp),
                            markdown = messageData.text
                        )
                    } else {
                        Text(text = messageData.text, textAlign = TextAlign.Left, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.wrapContentWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(5.dp)).padding(5.dp).widthIn(max = 300.dp))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (messageData.imageUri != null && activity != null) {
                    val uri = messageData.imageUri
                    val bitmap = uri?.let { activity.contentResolver.openInputStream(it)?.use { i -> BitmapFactory.decodeStream(i) } }
                    bitmap?.let {
                        Image(
                            Bitmap.createScaledBitmap(it, 224, 224, true).asImageBitmap(), "",
                            modifier = Modifier.wrapContentWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(5.dp)).padding(5.dp).widthIn(max = 300.dp)
                        )
                    }
                    val ma = activity as? MainActivity
                    if (ma != null && !ma.hasImage) { ma.chatState.requestImageBitmap(messageData.imageUri); ma.hasImage = true }
                } else {
                    Text(text = messageData.text, textAlign = TextAlign.Right, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.wrapContentWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(5.dp)).padding(5.dp).widthIn(max = 300.dp))
                }
            }
        }
    }
}

@Composable
private fun ThinkCard(messageData: MessageData) {
    var expanded by remember(messageData.id) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .padding(bottom = 4.dp)
            .widthIn(max = 300.dp)
            .clickable { expanded = !expanded }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            if (messageData.isThinkOpen) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Thinking…", style = MaterialTheme.typography.labelMedium)
            } else {
                Icon(
                    Icons.Outlined.Psychology, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text("Thought process", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded && messageData.thinkContent.isNotEmpty()) {
            Text(
                messageData.thinkContent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            )
        }
    }
}

@ExperimentalMaterial3Api
@Composable
fun SendMessageView(chatState: AppViewModel.ChatState, activity: Activity) {
    val localFocusManager = LocalFocusManager.current
    val ma = activity as MainActivity
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(IntrinsicSize.Max).fillMaxWidth().padding(bottom = 5.dp)
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Message") }, modifier = Modifier.weight(9f))
        IconButton(onClick = { activity.takePhoto() }, modifier = Modifier.aspectRatio(1f).weight(1f), enabled = chatState.chatable() && !ma.hasImage) {
            Icon(Icons.Filled.AddAPhoto, "camera")
        }
        IconButton(onClick = { activity.pickImageFromGallery() }, modifier = Modifier.aspectRatio(1f).weight(1f), enabled = chatState.chatable() && !ma.hasImage) {
            Icon(Icons.Filled.Photo, "gallery")
        }
        IconButton(
            onClick = { localFocusManager.clearFocus(); chatState.requestGenerate(text, activity); text = "" },
            modifier = Modifier.aspectRatio(1f).weight(1f),
            enabled = text.isNotEmpty() && chatState.chatable()
        ) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
    }
}
