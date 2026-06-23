# Localis UI Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the API Server card's misleading "running" state and IP-reachability bug, declutter the Home screen of legacy MLC-LLM-era monitoring UI, and visually group/space the Model picker list.

**Architecture:** Pure Android/Kotlin/Jetpack Compose changes inside the existing `MobileAI` (Localis) app. No new modules, no new dependencies. Work touches `HomeScreen.kt`, `MainActivity.kt`, `ForegroundInferenceService.kt`, `ApiServer.kt`, `SettingsScreen.kt`, `AppViewModel.kt`, `SystemMonitor.kt`, and `StartView.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Ktor (embedded Netty server), Android SharedPreferences.

## Global Constraints

- Spec source of truth: `docs/superpowers/specs/2026-06-20-localis-ui-stability-design.md`
- `ThermalGovernor` and its inference throttling (`AppViewModel.kt:389-390`, `:473-474`) must remain fully functional — only its Home-screen *display* is removed, never its logic.
- `SystemMonitor` (CPU% polling) has no consumers outside `HomeScreen.kt` — safe to delete entirely.
- No real brand logos in this pass — model picker badges are letter-on-color placeholders only.
- Manual IP override default is blank — auto-detection (with Wi-Fi-interface preference) remains the default path.
- Build with `cd MobileAI && ./gradlew assembleDebug` after each task to confirm compilation; this project has no unit test suite for UI composables, so "tests" here means compile success + manual verification steps described per task.

---

### Task 1: Prefer Wi-Fi interface in `getLocalIpAddress()`

**Files:**
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/MainActivity.kt:145-157`

**Interfaces:**
- Produces: `MainActivity.getLocalIpAddress(): String` — same signature, improved interface-selection logic. Used by `HomeScreen.kt` (Task 4) and `ForegroundInferenceService`/`ApiServer` callers unchanged.

- [ ] **Step 1: Replace the interface-selection loop to prefer `wlan0`**

Current code (`MainActivity.kt:145-157`):

```kotlin
fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "unknown"
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress || addr.hostAddress?.contains(':') == true) continue
                return addr.hostAddress ?: continue
            }
        }
    } catch (_: Exception) {}
    return "unknown"
}
```

Replace with:

```kotlin
fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "unknown"
        val candidates = mutableListOf<Pair<String, String>>()  // (interfaceName, ip)
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress || addr.hostAddress?.contains(':') == true) continue
                val ip = addr.hostAddress ?: continue
                candidates.add(iface.name to ip)
            }
        }
        // Prefer the Wi-Fi interface (wlan0) over mobile data, tethering, or VPN interfaces
        candidates.firstOrNull { it.first == "wlan0" }?.let { return it.second }
        candidates.firstOrNull()?.let { return it.second }
    } catch (_: Exception) {}
    return "unknown"
}
```

- [ ] **Step 2: Build the debug APK to confirm compilation**

Run: `cd /Users/akshitharsola/Documents/LLM/MobileAI && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manual verification**

Install the debug APK on the phone (`adb install -r app/build/outputs/apk/debug/app-debug.apk`), open the app, ensure Wi-Fi is connected, and check the Home screen API Server card shows an IP matching the phone's actual Wi-Fi IP (visible in Android Settings → Wi-Fi → network details). Confirmed correct if it matches.

- [ ] **Step 4: Commit**

```bash
cd /Users/akshitharsola/Documents/LLM
git add MobileAI/app/src/main/java/ai/mlc/mobileai/MainActivity.kt
git commit -m "fix: prefer wlan0 interface when detecting local IP for API server URL"
```

---

### Task 2: Manual IP override in Settings

**Files:**
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/SettingsScreen.kt:99-108` (API Server section)

**Interfaces:**
- Produces: SharedPreferences key `"manual_ip"` (String, default `""`) in the `"mobileai"` prefs file. Consumed by Task 4 (`HomeScreen.kt`).

- [ ] **Step 1: Add a `manualIp` state and text field next to the existing Port field**

In `SettingsScreen.kt`, after the line `var apiPort by remember { mutableStateOf(prefs.getInt("api_port", 8080).toString()) }` (line 25), add:

```kotlin
var manualIp by remember { mutableStateOf(prefs.getString("manual_ip", "") ?: "") }
```

Then, in the "API Server" section (after the existing `apiPort` `OutlinedTextField`, i.e. after line 108), add:

```kotlin
OutlinedTextField(
    value = manualIp,
    onValueChange = { manualIp = it.trim() },
    label = { Text("Manual IP override (optional)") },
    supportingText = { Text("Leave blank to auto-detect. Use if the auto-detected IP isn't reachable from other devices.") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true
)
```

- [ ] **Step 2: Persist `manual_ip` in the Save button's `prefs.edit()` block**

In the `Button(onClick = { ... })` block (`SettingsScreen.kt:179-203`), add `.putString("manual_ip", manualIp)` to the existing chained `prefs.edit()` calls, e.g.:

```kotlin
prefs.edit()
    .putString("hf_token", hfToken.trim())
    .putString("bot_token", newToken)
    .putInt("api_port", newPort)
    .putString("manual_ip", manualIp)
    .putInt("max_tokens", (maxTokens.toIntOrNull() ?: 2048).coerceIn(256, 4096))
    .putInt("think_max_tokens", (thinkMaxTokens.toIntOrNull() ?: 1024).coerceIn(256, 2048))
    .putBoolean("no_think", noThink)
    .remove("context_len")
    .putString("default_model", defaultModel)
    .apply()
```

- [ ] **Step 3: Build to confirm compilation**

Run: `cd /Users/akshitharsola/Documents/LLM/MobileAI && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manual verification**

Install and open Settings, confirm the new "Manual IP override" field appears under API Server, type a value (e.g. `10.0.0.5`), tap "Save & Apply", reopen Settings, and confirm the value persisted.

- [ ] **Step 5: Commit**

```bash
cd /Users/akshitharsola/Documents/LLM
git add MobileAI/app/src/main/java/ai/mlc/mobileai/SettingsScreen.kt
git commit -m "feat: add manual IP override field to Settings API Server section"
```

---

### Task 3: API Server card — 4-state model + loopback self-test

**Files:**
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/HomeScreen.kt:145-190` (API Server card block)
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/ForegroundInferenceService.kt:91-95` (`startApiServer`, add starting-state flag)

**Interfaces:**
- Consumes: `ForegroundInferenceService.isModelLoaded(): Boolean` (already exists, `ForegroundInferenceService.kt:205`), `MainActivity.getLocalIpAddress(): String` (Task 1), `"manual_ip"` SharedPreferences key (Task 2), `ApiServer`'s existing `/health` route (`ApiServer.kt:59-66`, returns JSON `{"status":"ok","model":...,"loaded":...}`).
- Produces: `ForegroundInferenceService.apiServerStarting: Boolean` (new `@Volatile var`) — true between `startApiServer()` call and the embedded server's `start(wait=false)` returning.

- [ ] **Step 1: Add a `starting` flag to `ForegroundInferenceService`**

In `ForegroundInferenceService.kt`, near the existing `var apiServer: ApiServer? = null` (line 41), add:

```kotlin
@Volatile var apiServerStarting: Boolean = false
```

Modify `startApiServer` (`ForegroundInferenceService.kt:91-95`) from:

```kotlin
fun startApiServer(port: Int) {
    apiServer = ApiServer(this, port)
    serviceScope.launch { apiServer!!.start() }
    updateNotification("API server running on :$port")
}
```

to:

```kotlin
fun startApiServer(port: Int) {
    apiServer = ApiServer(this, port)
    apiServerStarting = true
    serviceScope.launch {
        apiServer!!.start()
        apiServerStarting = false
    }
    updateNotification("API server running on :$port")
}
```

- [ ] **Step 2: Add a loopback self-test function to `ForegroundInferenceService`**

Add this function to `ForegroundInferenceService.kt` (near `startApiServer`):

```kotlin
suspend fun testApiServerLoopback(port: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("http://127.0.0.1:$port/health")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (_: Exception) {
        false
    }
}
```

- [ ] **Step 3: Rewrite the API Server card in `HomeScreen.kt` with 4 states**

Replace the block from `val apiRunningForCard = service?.apiServer != null` through the closing of the `Card { ... }` (`HomeScreen.kt:145-190`) with:

```kotlin
val apiServerStarting = service?.apiServerStarting == true
val apiServerObj = service?.apiServer
val modelLoadedForCard = service?.isModelLoaded() == true
val apiPort = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getInt("api_port", 8080)
val manualIp = activity.getSharedPreferences("mobileai", Context.MODE_PRIVATE).getString("manual_ip", "") ?: ""

enum class ApiCardState { STOPPED, STARTING, RUNNING_NO_MODEL, RUNNING_READY }
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
    ApiCardState.RUNNING_NO_MODEL -> "Running — load a model to use"
    ApiCardState.RUNNING_READY -> "Running"
}

var selfTestResult by remember { mutableStateOf<String?>(null) }
val coroutineScopeForTest = rememberCoroutineScope()

Card(
    modifier = Modifier
        .fillMaxWidth()
        .then(if (apiCardState == ApiCardState.RUNNING_READY || apiCardState == ApiCardState.RUNNING_NO_MODEL) Modifier.clickable {
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
```

Add the missing import for the new icon at the top of `HomeScreen.kt` (it already has `import androidx.compose.material.icons.outlined.*`, which covers `Icons.Outlined.NetworkCheck` and `Icons.Outlined.Cloud` — verify this compiles; if `NetworkCheck` isn't in that wildcard import, add `import androidx.compose.material.icons.outlined.NetworkCheck` explicitly).

Add `import androidx.compose.runtime.rememberCoroutineScope` and `import kotlinx.coroutines.launch` if not already present (check existing imports first — `HomeScreen.kt` already imports `kotlinx.coroutines.delay`, confirm `kotlinx.coroutines.launch` is present or add it).

- [ ] **Step 4: Build to confirm compilation**

Run: `cd /Users/akshitharsola/Documents/LLM/MobileAI && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install and open the app. Verify all 4 states by exercising them in order:
1. Don't start API → card shows red "Stopped"
2. Tap "Start API" with no model loaded → card briefly shows "Starting…" then settles on amber "Running — load a model to use"
3. Tap the network-check icon → toast/inline text shows a loopback result
4. Load a model, confirm card flips to green "Running"

- [ ] **Step 6: Commit**

```bash
cd /Users/akshitharsola/Documents/LLM
git add MobileAI/app/src/main/java/ai/mlc/mobileai/HomeScreen.kt MobileAI/app/src/main/java/ai/mlc/mobileai/ForegroundInferenceService.kt
git commit -m "feat: add 4-state API server card with loopback self-test and manual IP support"
```

---

### Task 4: Delete `SystemMonitor`, keep `ThermalGovernor`, remove Home screen CPU/Thermal display

**Files:**
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/SystemMonitor.kt` (delete `SystemMonitor` class and `SystemStats` data class only; keep `ThermalGovernor`, `ThermalState`, `ThermalInfo`)
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/AppViewModel.kt:46,58,64` (remove `systemMonitor` field and its `start()`/`stop()` calls; keep `thermalGovernor` lines untouched)
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/HomeScreen.kt` (remove `SystemUsageCard` call, `CpuGraph` composable, `cpuHistory` state, `systemStats`/`thermalInfo` collection)

**Interfaces:**
- Consumes: nothing new.
- Produces: `AppViewModel` no longer exposes `systemMonitor`. `thermalGovernor` remains exposed and functional, used internally by `AppViewModel.kt:389-390,473-474` — unchanged.

- [ ] **Step 1: Remove `SystemMonitor` class and `SystemStats` from `SystemMonitor.kt`**

In `SystemMonitor.kt`, delete the `data class SystemStats(...)` block (lines 19-23) and the entire `class SystemMonitor(private val context: Context) { ... }` block (lines 162-288). Keep everything else (`ThermalState`, `ThermalInfo`, `ThermalGovernor`) exactly as-is.

The file should end right after `ThermalGovernor`'s closing brace (previously line 160).

- [ ] **Step 2: Remove `systemMonitor` from `AppViewModel.kt`**

Remove this line (`AppViewModel.kt:46`):
```kotlin
val systemMonitor = SystemMonitor(app)
```

In `init { }` (around line 58), remove:
```kotlin
systemMonitor.start()
```
Keep `thermalGovernor.start()` (line 59) unchanged.

In `onCleared()` (around line 64), remove:
```kotlin
systemMonitor.stop()
```
Keep `thermalGovernor.stop()` (line 65) unchanged.

- [ ] **Step 3: Remove CPU/Thermal display from `HomeScreen.kt`**

Remove these lines from the top of `HomeScreen()` (around lines 50-51):
```kotlin
val systemStats by appViewModel.systemMonitor.stats.collectAsState()
val thermalInfo by appViewModel.thermalGovernor.state.collectAsState()
```

Remove the CPU history tracking block (around lines 57-64):
```kotlin
// Keep last 30 CPU samples (~60s of history at 2s polling)
val cpuHistory = remember { mutableStateListOf<Float>() }
LaunchedEffect(systemStats.cpuPercent) {
    if (systemStats.cpuPercent >= 0f) {
        cpuHistory.add(systemStats.cpuPercent)
        if (cpuHistory.size > 30) cpuHistory.removeAt(0)
    }
}
```

Remove the `SystemUsageCard` call (around lines 137-143):
```kotlin
// ── System Usage Graph Card ────────────────────────────────────
SystemUsageCard(
    cpuPercent = systemStats.cpuPercent,
    cpuHistory = cpuHistory,
    thermalInfo = thermalInfo,
    thermalAvailable = systemStats.thermalAvailable
)
```

Delete the `SystemUsageCard` composable function entirely (the `@Composable private fun SystemUsageCard(...)` block, originally lines 372-440) and the `CpuGraph` composable entirely (originally lines 526-557).

- [ ] **Step 4: Build to confirm compilation**

Run: `cd /Users/akshitharsola/Documents/LLM/MobileAI && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` — if `SystemStats`, `SystemUsageCard`, or `CpuGraph` are referenced anywhere else, the build will fail with "unresolved reference," confirming full removal is needed in that spot too.

- [ ] **Step 5: Manual verification**

Install and open the app. Confirm the Home screen no longer shows a "System Usage" card with CPU% or Thermal. Run the app long enough (a few minutes, with a model loaded and generating) to confirm thermal throttling still works as before (no regression expected since `ThermalGovernor` logic in `AppViewModel.kt` is untouched) — this is a smoke check, not a new behavior to verify.

- [ ] **Step 6: Commit**

```bash
cd /Users/akshitharsola/Documents/LLM
git add MobileAI/app/src/main/java/ai/mlc/mobileai/SystemMonitor.kt MobileAI/app/src/main/java/ai/mlc/mobileai/AppViewModel.kt MobileAI/app/src/main/java/ai/mlc/mobileai/HomeScreen.kt
git commit -m "refactor: remove SystemMonitor and CPU/Thermal display from Home screen, keep ThermalGovernor throttling intact"
```

---

### Task 5: Remove Telegram Bot status card and fold RAM into Model card

**Files:**
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/HomeScreen.kt`

**Interfaces:**
- Consumes: `appViewModel.ramUsageMB`, `appViewModel.totalRamMB` (existing, unchanged), `service?.telegramPoller` (existing, used in Quick Actions button label, unchanged).
- Produces: no new interfaces.

- [ ] **Step 1: Remove the standalone RAM `StatusCard` and fold into Model card**

Replace this block:
```kotlin
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
```

with:
```kotlin
StatusCardWithSubtitle(
    icon = Icons.Outlined.Memory,
    title = "Model",
    value = when {
        offloading -> "Offloading…"
        isLoaded -> modelName.substringBefore("-q4f")
        else -> "No model loaded"
    },
    subtitle = "${ramUsed}MB / ${ramTotal}MB RAM",
    ok = isLoaded && !offloading
)
```

- [ ] **Step 2: Add the `StatusCardWithSubtitle` composable**

Add this new composable near the existing `StatusCard` composable (`HomeScreen.kt`, after the `StatusCard` function):

```kotlin
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
```

- [ ] **Step 3: Remove the standalone Telegram Bot `StatusCard`**

Remove this block entirely:
```kotlin
StatusCard(
    icon = Icons.AutoMirrored.Outlined.Send,
    title = "Telegram Bot",
    value = if (service?.telegramPoller != null) "Active" else "Inactive",
    ok = service?.telegramPoller != null
)
```

Do not touch the Quick Actions "Start/Stop Bot" button — it already reflects bot state via its label (`HomeScreen.kt`, Quick Actions row, unchanged).

- [ ] **Step 4: Build to confirm compilation**

Run: `cd /Users/akshitharsola/Documents/LLM/MobileAI && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install and open the app. Confirm: Model card shows RAM as a subtitle line, no standalone RAM card, no standalone Telegram Bot card, and the "Start Bot"/"Stop Bot" Quick Action button still toggles and reflects state correctly.

- [ ] **Step 6: Commit**

```bash
cd /Users/akshitharsola/Documents/LLM
git add MobileAI/app/src/main/java/ai/mlc/mobileai/HomeScreen.kt
git commit -m "refactor: fold RAM into Model card subtitle, remove standalone Telegram Bot status card"
```

---

### Task 6: Model picker — group by family, add spacing and placeholder badges

**Files:**
- Modify: `MobileAI/app/src/main/java/ai/mlc/mobileai/StartView.kt`

**Interfaces:**
- Consumes: `AppViewModel.ModelState.modelConfig.modelId: String` (existing, via `FakeModelConfig` shim, `AppViewModel.kt:119`).
- Produces: `familyOf(modelId: String): String` helper function, `ModelBadge(family: String)` composable — both new, local to `StartView.kt`.

- [ ] **Step 1: Add a `familyOf` helper function**

Add to `StartView.kt` (top-level function, outside any composable):

```kotlin
private fun familyOf(modelId: String): String = when {
    modelId.startsWith("Gemma", ignoreCase = true) -> "Gemma"
    modelId.startsWith("Qwen", ignoreCase = true) -> "Qwen"
    modelId.startsWith("DeepSeek", ignoreCase = true) -> "DeepSeek"
    else -> "Other"
}
```

- [ ] **Step 2: Add a `ModelBadge` placeholder composable**

Add to `StartView.kt`:

```kotlin
@Composable
private fun ModelBadge(family: String) {
    val (letter, color) = when (family) {
        "Gemma" -> "G" to MaterialTheme.colorScheme.primary
        "Qwen" -> "Q" to MaterialTheme.colorScheme.tertiary
        "DeepSeek" -> "D" to MaterialTheme.colorScheme.secondary
        else -> "?" to MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(color, shape = androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}
```

Add the missing import `import androidx.compose.foundation.background` at the top of `StartView.kt` if not already present.

- [ ] **Step 3: Replace the flat `LazyColumn` with a grouped one**

Replace this block in `StartView`:
```kotlin
LazyColumn {
    items(items = appViewModel.modelList, key = { it.id }) { modelState ->
        ModelView(navController = navController, modelState = modelState, appViewModel = appViewModel)
        HorizontalDivider()
    }
}
```

with:
```kotlin
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                ModelBadge(family = family)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ModelView(navController = navController, modelState = modelState, appViewModel = appViewModel)
                }
            }
            HorizontalDivider()
        }
    }
}
```

- [ ] **Step 4: Build to confirm compilation**

Run: `cd /Users/akshitharsola/Documents/LLM/MobileAI && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install and open the app, navigate to Models. Confirm: models are grouped under "Gemma", "Qwen", "DeepSeek" section headers in that order, each row has a colored letter badge, and rows have visibly more vertical spacing than before (no longer "sticky").

- [ ] **Step 6: Commit**

```bash
cd /Users/akshitharsola/Documents/LLM
git add MobileAI/app/src/main/java/ai/mlc/mobileai/StartView.kt
git commit -m "feat: group model picker by family with section headers, spacing, and placeholder badges"
```

---

## Final Verification

- [ ] **Full app smoke test**: install the final debug APK, exercise the full flow — load a model, start API server, confirm 4-state card behavior, copy the URL and test from another device on the same Wi-Fi, check Settings manual IP override field works, check Models screen grouping/spacing/badges, confirm no crashes across all screens.
- [ ] **Final build**: `cd /Users/akshitharsola/Documents/LLM/MobileAI && ./gradlew assembleDebug` — `BUILD SUCCESSFUL`.
