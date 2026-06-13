# Localis v2.8 - Post-Build Issue Analysis Report

This report summarizes the performance state of Localis v2.8 based on Logcat sessions captured on a Xiaomi 14 Ultra during heavy inference (Qwen3-4B).

## 🔴 Critical Performance Failures (Hangs & ANRs)

### 1. GPU Fence Timeouts (The "Total Freeze")
*   **Log Indicator:** `waitForever: Throttling EGL Production: fence 249 didn't signal in 3000 ms`
*   **Technical Cause:** The LLM engine is saturating the GPU during the Prefill/Think phases. The Android graphics system (SurfaceFlinger/EGL) requests a GPU "fence" to render a UI frame, but the fence never clears within the 3-second hard timeout.
*   **Result:** **9-second frame delays** (`Davey! duration=9230ms`). The screen completely freezes and stops responding to any user interaction.

### 2. Main Thread Starvation (Input Hang)
*   **Log Indicator:** `Enter APP_SCOUT_HANG_INPUT state` / `Skipped 478 frames!`
*   **Technical Cause:** The Main (UI) thread is blocked in native code at `android.graphics.HardwareRenderer.nSyncAndDrawFrame`. It is waiting for the graphics driver to return, but the driver is busy serving the LLM.
*   **Result:** Touch events (`MotionEvent`) are captured by the hardware but **discarded** by the app because the UI thread is too busy to process them. Scrolling becomes impossible during generation.

### 3. IPC Channel Clogging (Message Flooding)
*   **Log Indicator:** `Large outgoing transaction of 21208 bytes ... IAccessibilityInteractionConnectionCallback`
*   **Technical Cause:** The app is sending too many UI update requests per second as the model streams tokens. This floods the Binder (Android's inter-process communication) and adds to the overall system lag.
*   **Result:** System-wide sluggishness and potential crash risk due to transaction buffer overflows.

---

## 🟡 Configuration & Library Errors

### 4. Theme Mismatch (CustomTextView)
*   **Log Indicator:** `View class dev.jeziellago.compose.markdowntext.CustomTextView is an AppCompat widget that can only be used with a Theme.AppCompat theme.`
*   **Root Cause:** `themes.xml` defines a `Material.Light` parent, but the markdown library requires `Theme.AppCompat`.
*   **Impact:** Performance overhead and potential rendering glitches in the chat bubbles.

### 5. Native Library Loading (TVM)
*   **Log Indicator:** `java.lang.UnsatisfiedLinkError: Couldn't find the resource libtvm4j.so`
*   **Cause:** The app looks for the base TVM library and fails, falling back to the `runtime_packed` version.
*   **Impact:** Adds several hundred milliseconds to the initial app startup/model load time.

### 6. Missing Predictive Back Support
*   **Log Indicator:** `OnBackInvokedCallback is not enabled for the application.`
*   **Impact:** Inconsistent back-button behavior on Android 13/14+.

---

## 🛠️ Required Fixes for v2.9

1.  **Throttled UI Updates:** Implement a 250ms "batching" timer for chat message updates to stop flooding the Main thread.
2.  **GPU "Breath" Windows:** Insert an explicit `delay(50)` (suspending) every 10 tokens to allow the Android OS to sneak in a frame render.
3.  **Theme Alignment:** Change `res/values/themes.xml` parent to `Theme.AppCompat.DayNight.NoActionBar`.
4.  **Back Invoked Support:** Add `android:enableOnBackInvokedCallback="true"` to `<application>` in `AndroidManifest.xml`.
