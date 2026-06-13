# Logcat Analysis Report (V2) - June 13, 2026

This report analyzes the second provided Logcat snippet from the Localis app (`ai.mlc.mobileai.debug`) on the Xiaomi 14 Ultra.

## 🔴 Critical Failures & Hangs

### 1. Main Thread Blocked on Canvas Lock (`nativeLockCanvas`)
*   **Log Indicator:** 
    ```
    MIUIScout App: Enter APP_SCOUT_WARNING State
    MIUIScout App: Event:APP_SCOUT_WARNING Thread:main backtrace:
    at android.view.Surface.nativeLockCanvas(Native Method)
    at android.view.Surface.lockCanvas(Surface.java:637)
    at android.view.ViewRootImpl.drawSoftware(ViewRootImpl.java:7322)
    ```
*   **Technical Cause:** The Main thread is attempting to draw the UI but is blocked in `nativeLockCanvas`. This usually happens when the system compositor (SurfaceFlinger) cannot provide a graphics buffer because the GPU is saturated or SurfaceFlinger is under extreme backpressure.
*   **Impact:** Complete UI freeze and eventual ANR (as seen in the previous analysis).

### 2. SurfaceFlinger Hang (Backpressure)
*   **Log Indicator:** `SF-BufferCheck: Buffer processing hung for over 7877.01ms due to backpressure.`
*   **Technical Cause:** SurfaceFlinger confirms that it has been unable to process graphics buffers for nearly **8 seconds**. This is a system-level stall caused by the LLM's GPU usage.

### 3. Telegram Poller Timeout
*   **Log Indicator:** `TelegramPoller: Poll error: timeout`
*   **Technical Cause:** The background polling service for the Telegram bot timed out. This could be due to the app being process-thrashed or the system being so busy with LLM tasks that networking threads are starved.

---

## 🟡 Rendering & UI Errors

### 4. Ripple Animation Failure (Non-Hardware Canvas)
*   **Log Indicator:** `RippleDrawable: The RippleDrawable.STYLE_PATTERNED animation is not supported for a non-hardware accelerated Canvas. Skipping animation.`
*   **Observation:** This log is flooding the output (hundreds of times). 
*   **Technical Cause:** The app is falling back to a **Software Canvas** (likely because hardware acceleration failed due to the GPU being busy). Standard Material ripples require hardware acceleration; when they fail, they generate this error for every frame of the animation.

### 5. Hidden API Denials
*   **Log Indicator:** `.mobileai.debug: hiddenapi: Accessing hidden method ... Landroid/view/View;->updateDisplayListIfDirty() ... denied`
*   **Impact:** Performance overhead as the system blocks reflection-based access to internal rendering methods used by some libraries (potentially Jetpack Compose or a 3rd party UI tool).

---

## 🔵 Native Library & Configuration Issues

### 6. Netty (Ktor) Native Library Failure
*   **Log Indicator:** 
    *   `No implementation found for int io.netty.channel.kqueue.Native.sizeofKEvent()`
    *   `dlopen failed: library "libnetty_transport_native_epoll_aarch_64.so" not found`
*   **Technical Cause:** The embedded Ktor server (API server) is attempting to use high-performance native transports (Epoll/KQueue) but cannot find the shared libraries. 
*   **Impact:** Ktor falls back to standard Java NIO, which is slower and consumes more CPU.

### 7. TVM Library Pathing
*   **Log Indicator:** `java.lang.UnsatisfiedLinkError: Couldn't find the resource libtvm4j.so`
*   **Observation:** The app fails to find the standard TVM library but successfully loads the "runtime packed version" (`libtvm4j_runtime_packed.so`) shortly after.

---

## Summary Conclusion
This Logcat confirms that the system is entering a state of **Graphics Fallback**. Because the GPU is saturated, hardware acceleration is failing, forcing the app to use a software canvas (`drawSoftware`). This further strains the Main thread, leading to it blocking on `nativeLockCanvas` for up to 8 seconds. The massive flooding of `RippleDrawable` errors is a symptom of this hardware acceleration failure.
