# NOTICE

## MobileAI

MobileAI is a distributed edge AI inference application for Android.

### Upstream Attribution

This project is based on **MLCChat** from the **MLC-LLM** project.

- Repository: https://github.com/mlc-ai/mlc-llm
- Copyright: Copyright (c) 2023 MLC LLM Team
- License: Apache License 2.0

Files derived from MLCChat include:
- `AppViewModel.kt` (extended)
- `ChatView.kt` (repackaged, minor edits)
- `StartView.kt` (repackaged, extended)
- `NavView.kt` (repackaged, extended)
- `MainActivity.kt` (repackaged, extended)

All derived files carry the attribution comment:
```
// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
```

### Extensions and Modifications

Extensions and modifications:
- Copyright (c) 2026 Akshit Harsola
- New files: `ForegroundInferenceService.kt`, `ApiServer.kt`, `TelegramPoller.kt`,
  `ClipboardMonitor.kt`, `ClipboardActionReceiver.kt`, `BootReceiver.kt`,
  `HomeScreen.kt`, `SettingsScreen.kt`, `ui/theme/Theme.kt`

The complete project is licensed under the Apache License 2.0.
See `LICENSE` for the full license text.
