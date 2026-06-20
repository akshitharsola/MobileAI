# Localis: API Server Reliability, Home Screen Declutter, Model Picker Polish

Date: 2026-06-20
Status: Approved (pending spec review)

## Context

Localis (MobileAI) has moved off MLC-LLM onto Google's LiteRT-LM engine (v4.1). Several home-screen elements (CPU/Thermal monitoring) were built to debug a GPU freeze bug specific to MLC-LLM, which no longer applies. Separately, the home screen's API Server card can report "running" in states where it can't actually serve a working response, and users have observed connection failures from external devices even when a model is loaded. The model picker (Models screen) list is visually cramped, with no per-family grouping or visual identity.

This spec covers three sub-projects, to be implemented in this order:
1. API Server card reliability (states + self-test + diagnostics)
2. Home screen declutter (remove CPU/Thermal, remove Telegram card, fold RAM into Model card)
3. Model picker visual polish (family grouping + spacing + placeholder badges)

"Better stability" is treated as a byproduct of (1) and (2) — no separate stability bugs were reported; removing the CPU/Thermal polling loop reduces background work, and fixing the API card's false-positive "running" state removes a source of user-perceived flakiness.

## Sub-Project 1: API Server Card Reliability

### Problem

`HomeScreen.kt`'s API Server card currently shows a green checkmark and a copyable URL whenever `service?.apiServer != null` — i.e., whenever the Netty process has been *started*, regardless of:
- whether a model is loaded (requests to `/chat`, `/generate`, `/v1/chat/completions` will return "No model loaded" or hang)
- whether the server is actually reachable from another device on the network

A screenshot confirmed the card shows "running" (green check) with **no model loaded**, and the user confirmed that even with a model loaded, external requests still fail with "not responding."

### Design

**Card states** (replacing the current binary running/stopped):

| State | Condition | Visual |
|---|---|---|
| Stopped | `apiServer == null` | Red X, "Stopped" |
| Starting | Server start requested, Netty not yet bound | Spinner, "Starting…" |
| Running — no model | `apiServer != null`, `!service.isModelLoaded()` | Amber/warning tint, "Running — load a model to use", URL still shown/copyable |
| Running — ready | `apiServer != null`, `service.isModelLoaded()` | Green check, "tap to copy" (current behavior) |

**Self-test button:** add a small action (icon button) on the card that fires a real HTTP GET to `http://127.0.0.1:<port>/health` from inside the app (using the existing `/health` route in `ApiServer.kt`) and shows a toast/inline result: "Server OK locally" or "Server not responding locally — restart it."

**Guided diagnostic on success:** if the loopback self-test succeeds, but the user reports external devices still can't connect, the result message includes a troubleshooting hint: *"Server OK locally. If another device can't connect: confirm it's on the same Wi-Fi network, check your router's AP/client isolation setting, and check HyperOS background data restrictions for this app."* This is a static hint, not a network diagnostic the app can run on its own — the app cannot inspect the user's router or other devices, so loopback success is the strongest verifiable signal we can give before pointing to environment causes.

**Out of scope:** automatically detecting Wi-Fi client isolation, VPN interfaces, or HyperOS network restrictions programmatically. These require either no API exposure on Android or manual user verification — not buildable as a reliable in-app check.

### Implementation notes

- `ForegroundInferenceService.isModelLoaded()` already exists (`ForegroundInferenceService.kt:205`) — reuse for the "no model" check.
- "Starting" state needs a transient flag set when `startApiServer()` is called and cleared once `ApiServer.start()`'s `embeddedServer(...).start(wait = false)` call returns — currently fire-and-forget via `serviceScope.launch`.
- Self-test uses a simple HTTP client (e.g. Ktor client or `HttpURLConnection`) against `127.0.0.1:<port>/health`, on a background coroutine, with a short timeout (e.g. 3s).

## Sub-Project 2: Home Screen Declutter

### Problem

`HomeScreen.kt` currently renders 7 cards/sections before Quick Actions: Model, RAM, System Usage (CPU graph + Thermal), API Server, Telegram Bot, Inference History, Model Selector. RAM/CPU/Thermal were built to diagnose the MLC-LLM GPU freeze bug (resolved in the LiteRT-LM migration) and no longer serve daily use. Telegram Bot's standalone status card is redundant with the Quick Actions button, whose label already reflects Active/Inactive state.

### Design

**Remove:**
- `SystemUsageCard` composable and its rendering (CPU graph, Thermal row) — `HomeScreen.kt:138-143, 372-440`
- Underlying CPU/Thermal data plumbing: `cpuHistory` state + `LaunchedEffect(systemStats.cpuPercent)` (`HomeScreen.kt:58-64`), `systemStats`/`thermalInfo` collection (`HomeScreen.kt:50-51`), and the `CpuGraph` composable (`HomeScreen.kt:526-557`)
- `SystemMonitor`/`ThermalGovernor` polling hookup from `AppViewModel` if no longer consumed elsewhere (verify no other screen reads `appViewModel.systemMonitor` / `appViewModel.thermalGovernor` before deleting the underlying classes; if unused elsewhere, delete `SystemMonitor.kt` polling logic and thermal governor wiring)
- Standalone `Telegram Bot` `StatusCard` (`HomeScreen.kt:192-197`) — status remains visible via the Start/Stop Bot button label in Quick Actions

**Fold RAM into Model card:**
- Drop the standalone RAM `StatusCard` (`HomeScreen.kt:130-135`)
- Append RAM as a subtitle/second line on the Model status card, e.g. "Gemma3-1B-IT · 1.2GB / 15GB RAM"

**Resulting home screen order:**
1. Model status (with RAM subtitle)
2. API Server (redesigned per Sub-Project 1)
3. Inference History (unchanged, collapsible)
4. Model Selector list (unchanged)
5. Quick Actions (unchanged: Models, Chat, Start/Stop API, Start/Stop Bot)
6. Shutdown button
7. Footer

### Implementation notes

- Removing the RAM poll (`LaunchedEffect(Unit) { ... appViewModel.updateRamUsage() ... }`, `HomeScreen.kt:67-72`) is NOT in scope — RAM display is kept, just relocated, so this polling loop stays.
- Verify `appViewModel.systemMonitor` / `appViewModel.thermalGovernor` aren't referenced in `SettingsScreen.kt` or elsewhere before deleting their backing classes; if referenced elsewhere, leave the classes but remove `HomeScreen.kt`'s subscription to them.

## Sub-Project 3: Model Picker Visual Polish

### Problem

`StartView.kt`'s `ModelView` renders a flat list of 9 models with tight `5.dp` row spacing and only a `HorizontalDivider()` between entries — no grouping, no visual identity beyond text. Models visually "stick together," especially as the catalog has grown to 9 entries across 3 families (Gemma, Qwen, DeepSeek).

### Design

**Group by family with section headers:** derive family from `model_id` prefix (`Gemma...` / `Qwen...` / `DeepSeek...`) and render as sections:

```
Gemma
  [badge] Gemma3-270M-SM8650 ...
  [badge] Gemma3-1B-SM8650 ...
  [badge] Gemma3-1B-Generic ...
  [badge] Gemma4-E2B ...
  [badge] Gemma4-E4B ...

Qwen
  [badge] Qwen3-0.6B ...
  [badge] Qwen2.5-1.5B ...
  [badge] Qwen3-4B ...

DeepSeek
  [badge] DeepSeek-R1-1.5B ...
```

Section headers use the same style as `HomeScreen`'s existing section titles (`titleMedium`, bold) for visual consistency.

**Spacing:** increase row vertical padding (e.g. `12.dp` vertical padding per row, replacing reliance on bare dividers) so rows don't feel merged.

**Placeholder badges:** each row gets a leading circular badge (~32dp) with the family's first letter (G / Q / D) on a family-distinct background color, generated from the existing Material theme palette (not hardcoded brand colors, since real logos aren't available yet). This is a drop-in placeholder — when the user provides real brand logo assets, the badge composable swaps from "letter on color" to "Image with the logo," same slot, no layout change needed.

### Implementation notes

- Family extraction: simple prefix match against known families (`"Gemma"`, `"Qwen"`, `"DeepSeek"`) on `model_id`; fall back to "Other" section if a future model doesn't match (defensive, but YAGNI beyond a simple `when`/`startsWith` check).
- `StartView`'s `LazyColumn` changes from a flat `items(modelList)` to grouping models first (e.g. `groupBy { familyOf(it.modelConfig.modelId) }`) then rendering a header + items per group.
- Badge composable should be a separate small composable (e.g. `ModelBadge(family: String)`) so swapping in real logos later is a one-function change.

## Out of Scope

- Automatic network diagnostics beyond loopback self-test (Sub-Project 1)
- Re-architecting `SystemMonitor`/`ThermalGovernor` for future use — if unused after this change, they are dead code candidates, but full deletion only happens if confirmed unused elsewhere (handled during implementation, not blocking design approval)
- Real brand logo integration — explicitly deferred until the user provides logo assets
