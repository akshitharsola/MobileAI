# Localis — On-Device LLM Inference Node for Android

**Turn your Android phone into a private, local AI server — no cloud dependency.**

> App source: [`MobileAI/`](MobileAI/) (package `ai.localis.app`, repo name predates the Localis rebrand)

---

Localis turns a high-RAM Android device (built and tested on a Xiaomi 14 Ultra, 16GB RAM) into a standalone LLM inference server. It runs entirely on-device using Google's **LiteRT-LM** runtime, exposes an OpenAI-compatible HTTP API, and can be driven from a Mac/PC, a Telegram bot, or any client that speaks the OpenAI chat completions protocol — including AI agents like OpenClaw/Claude running in a separate environment on your local network.

## Architecture

```
Mac / OpenClaw (or any OpenAI-compatible client)
       │  HTTP, local network
       ▼
Xiaomi 14 Ultra — Localis app
  ├── ForegroundInferenceService (always-on)
  │     ├── LiteRT-LM Engine (GPU/NPU backend)
  │     ├── ApiServer (Ktor, :8080) — /v1/chat/completions, /health, /models...
  │     └── TelegramPoller — optional bot bridge
  └── ThermalGovernor — paces inference under thermal load
```

LiteRT-LM replaced an earlier MLC-LLM-based engine (v4.0) after a GPU fence contention bug caused hard freezes under load on this hardware. LiteRT-LM uses separate silicon (Hexagon NPU) for some backends, avoiding that contention path entirely.

## Features

- **Local LLM inference** via LiteRT-LM — 13 models in the current catalog (Gemma3, Qwen2.5/3, DeepSeek-R1), including extended-context (`ekv8192`) variants for longer prompts
- **OpenAI-compatible API** (Ktor, port 8080): `GET /health`, `GET /v1/models`, `POST /v1/chat/completions`, plus simpler `POST /chat` / `POST /generate` endpoints and `POST /v1/models/load` for remote model switching
- **In-app model manager** — download from HuggingFace (including gated repos via token), switch, and delete models from the Models screen
- **Telegram Bot bridge** — optional, long-polls Telegram and replies with inference output
- **Always-on foreground service** — inference stays available even when the app isn't in the foreground
- **Thermal-aware pacing** — `ThermalGovernor` throttles inference under sustained load instead of letting the device overheat

## Build Prerequisites

### 1. Build the app

```bash
cd /Users/akshitharsola/Documents/LLM/MobileAI
./gradlew assembleDebug

# APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

LiteRT-LM is consumed as a prebuilt dependency (no native TVM/MLC build step required, unlike the old engine).

### 2. Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Quick Start (after install)

1. Open **Localis** on your phone
2. Go to **Models** → tap the download icon next to your chosen model, wait for it to finish
3. From **Home**, select the model and tap **Load**
4. The **API Server** card shows live status (Stopped / Starting / Running) and the phone's current IP — tap to copy
5. *(Optional)* Go to **Settings** → enter a Telegram bot token from [@BotFather](https://t.me/BotFather), then **Start Bot** from Home

## API Usage (from Mac / any client)

```bash
# Health check
curl http://<phone-ip>:8080/health

# OpenAI-compatible chat completion
curl -X POST http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "Qwen3-4B",
    "messages": [{"role": "user", "content": "Explain on-device AI in one sentence."}],
    "max_tokens": 256
  }'

# Switch the loaded model remotely
curl -X POST http://<phone-ip>:8080/v1/models/load \
  -H "Content-Type: application/json" \
  -d '{"model": "Qwen2.5-1.5B-ekv8192"}'
```

## Model Catalog Notes

- Default-context models (`ekv4096` and below) are stable for everyday chat.
- `ekv8192` (extended 8K context) variants were custom-converted via `litert-torch export_hf --cache_length=8192` on Kaggle's TPU tier, for use cases needing longer prompts (e.g. AI agents with large system prompts).
- **Known hardware ceiling**: on this device, `Qwen3-4B-ekv8192` can crash deterministically on long-context generation near the end of an ~8K-token window, and `Qwen3-8B-ekv8192` can crash the entire phone on load. These are marked experimental/unsupported in the model list — `Qwen2.5-1.5B-ekv8192` is the current stable ceiling for extended context on 16GB-class hardware.

## Attribution

LiteRT-LM runtime by Google. Earlier versions of this app (v1.0–v3.x) were based on **MLCChat** from the [MLC-LLM](https://github.com/mlc-ai/mlc-llm) project (Copyright (c) 2023 MLC LLM Team — Apache License 2.0); that engine has since been fully replaced.

Extensions Copyright (c) 2026 Akshit Harsola.
See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for full attribution.
