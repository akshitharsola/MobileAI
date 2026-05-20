# MobileAI

**Distributed Edge AI Inference Node for Android**

Turn your Xiaomi 14 Ultra (or any high-RAM Android device) into a portable AI inference server.

## Architecture

```
MacBook / OpenClaw
       ↓
  Telegram Bot API
       ↓
  Xiaomi 14 Ultra (MobileAI)
       ↓
  Local LLM (MLC-LLM / llama.cpp)
       ↓
  Response back to Mac
```

## Features

- **Local LLM inference** via MLC-LLM engine (Qwen3-4B, Llama3.2-3B, etc.)
- **Telegram Bot** — receive prompts, run inference, reply automatically
- **Embedded HTTP API** — `POST /chat`, `GET /health`, `GET /models` on port 8080
- **Clipboard workflow** — monitors clipboard, sends to AI, copies response back
- **Background Service** — persistent foreground service keeps inference alive
- **Model Manager** — download, switch, and manage GGUF models
- **Battery & RAM aware** — designed for Xiaomi 14 Ultra (16GB RAM)

## Build Prerequisites

### 1. Build the MLC-LLM Native Library

The app depends on `mlc4j` — the MLC-LLM Android JNI library. You must build it first:

```bash
# From the mlc-llm repo root (../mlc-llm relative to this directory)
export ANDROID_NDK=/Users/akshitharsola/Library/Android/sdk/ndk/26.3.11579264

cd ../mlc-llm
pip install -e ".[dev]"

# Build the mlc4j library (this compiles TVM + MLC for Android arm64)
cd android/mlc4j
python prepare_libs.py

# Output will be at: android/mlc4j/output/
# Then create the dist directory MLCChat expects:
mkdir -p ../MLCChat/dist/lib/mlc4j
cp -r output ../MLCChat/dist/lib/mlc4j/
```

This takes 20–60 minutes depending on your machine.

### 2. Build MobileAI APK

```bash
cd /Users/akshitharsola/Documents/LLM/MobileAI
./gradlew assembleDebug

# APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Quick Start (after install)

1. Open MobileAI on your phone
2. Go to **Models** → tap download icon next to your chosen model
3. Wait for download to complete, tap chat icon
4. Go to **Settings** → enter your Telegram bot token from [@BotFather](https://t.me/BotFather)
5. On **Home** screen → tap **Start API** and **Start Bot**
6. Send a message to your Telegram bot — the phone processes it locally!

## API Usage (from Mac)

```bash
# Health check
curl http://<phone-ip>:8080/health

# Run inference
curl -X POST http://<phone-ip>:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Explain distributed AI systems", "max_tokens": 256}'
```

## Recommended Models

| Model | VRAM | Performance |
|---|---|---|
| Qwen3-4B-Instruct-q4f16_1 | ~3 GB | Excellent |
| Llama-3.2-3B-Instruct-q4f16_1 | ~2 GB | Very Good |

## Attribution

Based on **MLCChat** from the [MLC-LLM](https://github.com/mlc-ai/mlc-llm) project.  
Copyright (c) 2023 MLC LLM Team — Apache License 2.0.

Extensions Copyright (c) 2026 Akshit Harsola.  
See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for full attribution.
