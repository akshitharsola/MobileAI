# Distributed Mobile LLM Architecture — Project Blueprint

## Vision

Build a lightweight distributed AI system where:

- Android phone performs local LLM inference
- MacBook handles orchestration/UI/workflows
- Telegram acts as communication bridge
- OpenClaw (optional) acts as workflow orchestrator
- Claude Code remains available for heavy reasoning/coding

This architecture is designed specifically for:

- Low-RAM laptops
- Portable local AI
- Edge AI experimentation
- Robotics workflows
- Lightweight business assistants
- Distributed AI systems

---

# Core Idea

Instead of forcing large models onto an 8GB MacBook Air:

- Use the Xiaomi 14 Ultra (16GB RAM) as the inference node
- Run local LLM on Android
- Expose communication via Telegram Bot API
- Mac sends prompts/tasks
- Phone processes locally
- Results return back to Mac/OpenClaw

This creates a distributed local AI architecture.

---

# Final System Architecture

```text
MacBook Air
   ├── OpenClaw / Scripts / UI
   ├── Claude Code
   └── Telegram Bot Client
            ↓
      Telegram Bot API
            ↓
     Xiaomi 14 Ultra
            ↓
      Local LLM Runtime
            ↓
      Local Inference
            ↓
      Telegram Response
            ↓
Mac receives result
```

---

# Why This Architecture Is Good

## Benefits

### 1. Offloads Inference From Mac

The MacBook Air no longer needs to:

- load large models
- consume high RAM
- deal with thermal throttling
- maintain huge context windows

Phone performs inference instead.

---

### 2. Telegram Solves Networking Problems

Telegram becomes:

- transport layer
- authentication layer
- NAT traversal layer
- async queue
- persistent communication channel

This avoids:

- port forwarding
- reverse proxies
- Docker networking issues
- local LAN complexity

---

### 3. Modular Architecture

Each device has a dedicated role.

| Device | Role |
|---|---|
| MacBook | orchestration/UI |
| Android | inference server |
| Telegram | transport layer |
| Claude | heavy reasoning |
| OpenClaw | workflows/tools |

---

# Development Philosophy

Keep everything:

- lightweight
- simple
- modular
- replaceable
- API-first

Avoid:

- overengineering
- giant contexts
- large local models
- unstable Docker stacks

---

# Recommended Model Sizes

For Xiaomi 14 Ultra:

| Model Size | Expected Performance |
|---|---|
| 1B–3B | Excellent |
| 4B | Very Good |
| 7B Q4 | Possibly workable |
| 8B+ | Risky |

Recommended initial models:

- qwen3:4b
- llama3.2:3b
- qwen2.5:3b

---

# Android Side — Initial Setup

## Option 1 (Recommended Initially)

Use:

- Termux
- llama.cpp
- Python server

This is simpler and controllable.

---

# Android Requirements

## Install Termux

Recommended source:

- F-Droid version of Termux

Avoid outdated Play Store build.

---

# Packages To Install

Inside Termux:

```bash
pkg update && pkg upgrade
pkg install git python clang cmake
```

---

# Clone llama.cpp

```bash
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
```

---

# Build llama.cpp

```bash
cmake -B build
cmake --build build -j4
```

---

# Model Storage Strategy

Create:

```text
/storage/emulated/0/AI/models
```

Store GGUF models there.

---

# Recommended Download Strategy

Use quantized GGUF models.

Recommended:

| Model | Quant |
|---|---|
| qwen3:4b | Q4_K_M |
| llama3.2:3b | Q4_K_M |

---

# Example Download

Download GGUF manually from HuggingFace.

Store inside:

```text
/storage/emulated/0/AI/models
```

---

# Basic Local Inference Test

Example:

```bash
./build/bin/llama-cli \
  -m /storage/emulated/0/AI/models/qwen3-4b.gguf \
  -p "Explain distributed AI systems simply"
```

---

# Create Local HTTP API

Use llama.cpp server mode.

Example:

```bash
./build/bin/llama-server \
  -m /storage/emulated/0/AI/models/qwen3-4b.gguf \
  --host 0.0.0.0 \
  --port 8080
```

This creates:

```text
http://localhost:8080
```

on Android.

---

# Telegram Relay Architecture

## Why Telegram?

Telegram gives:

- stable connectivity
- async messaging
- authentication
- mobile support
- easy APIs
- background delivery

Perfect for MVP.

---

# Telegram Workflow

```text
Mac/OpenClaw
   ↓
Telegram Bot
   ↓
Phone receives prompt
   ↓
Local LLM runs
   ↓
Phone sends response
   ↓
Mac/OpenClaw receives output
```

---

# Telegram Bot Requirements

Create bot using:

- @BotFather

Obtain:

- Telegram Bot Token

---

# Python Telegram Bot

Recommended stack:

- python-telegram-bot
- Flask/FastAPI

---

# Bot Responsibilities

## Mac Side

- send prompts
- receive outputs
- clipboard integration
- OpenClaw integration

## Android Side

- listen for prompts
- run local inference
- return generated text

---

# Clipboard-Based Workflow (Recommended MVP)

This is intentionally simple.

---

## Why Clipboard First?

Benefits:

- fast iteration
- minimal engineering
- low complexity
- easier debugging
- immediate usability

---

# Clipboard Workflow

## Mac

1. Copy prompt
2. Send to Telegram bot
3. Wait for result
4. Auto-copy response to clipboard
5. Paste anywhere

---

## Android

1. Telegram bot receives prompt
2. Passes to local model
3. Captures output
4. Sends response back

---

# Future Improvements

After MVP works:

## Add:

- REST APIs
- WebSocket streaming
- local network communication
- vector memory
- multi-agent routing
- automatic summarization
- tool calling
- voice input/output
- Android app UI
- OpenAI-compatible API layer

---

# Long-Term Vision

Eventually build:

```text
Phone-hosted AI inference server
```

Capabilities:

- local inference
- OpenAI-compatible APIs
- Telegram integration
- battery-aware execution
- local-first AI
- edge AI orchestration

---

# OpenClaw Integration

OpenClaw should eventually:

- send tasks
- receive responses
- orchestrate workflows
- delegate inference to phone

Meaning:

```text
OpenClaw = orchestrator
Phone = inference worker
```

---

# Why This Matters

This architecture:

- reduces dependency on cloud GPUs
- enables portable AI systems
- supports privacy-first inference
- works on weaker laptops
- enables edge AI experimentation

---

# Recommended Immediate MVP

## Phase 1

Build:

- Telegram bot
- Android local inference
- clipboard workflow

Do NOT overengineer.

---

## Phase 2

Add:

- local APIs
- OpenClaw integration
- memory systems
- automation workflows

---

## Phase 3

Build custom Android application.

Features:

- embedded model runtime
- REST API
- WebSocket streaming
- model manager
- battery management
- multi-model routing

---

# Important Engineering Principles

## 1. Small Models > Huge Models

Efficient workflows matter more than giant context windows.

---

## 2. Distributed Systems Win

Separate:

- orchestration
- inference
- memory
- UI
- transport

---

## 3. External Memory Matters

Filesystem + retrieval > gigantic prompt history.

---

## 4. Modularity Is Critical

Every layer should be replaceable.

---

# Final Recommended Stack

| Component | Recommendation |
|---|---|
| Local Models | qwen3:4b + llama3.2:3b |
| Android Runtime | llama.cpp |
| Transport | Telegram |
| Mac UI | OpenClaw/OpenWebUI |
| Heavy Reasoning | Claude |
| Workflow Layer | OpenClaw |
| Storage | USB + phone storage |

---

# Custom Android Application Vision

Instead of relying permanently on:

- Termux
- Telegram relay hacks
- llama.cpp CLI commands

The long-term goal is to build a dedicated Android application.

This application becomes:

```text
Portable Personal AI Node
```

---

# Why Build Our Own App?

Because existing Android AI apps:

- are generic
- are chat-focused
- lack orchestration
- do not integrate well with distributed systems
- do not expose customizable APIs cleanly
- are not designed for OpenClaw-style workflows

We want:

- full control
- lightweight infrastructure
- modular architecture
- API-first design
- distributed inference capability

---

# Application Objectives

The application should:

## Core Goals

- Run local GGUF models
- Expose local inference APIs
- Support clipboard workflows
- Integrate with Telegram
- Support OpenClaw orchestration
- Work as a local AI worker node
- Be lightweight and battery-aware

---

# MVP Application Features

## 1. Model Manager

Capabilities:

- Import GGUF models
- Delete models
- Select active model
- Display model size
- Display RAM usage
- Switch quantizations

Supported model examples:

- qwen3:4b
- llama3.2:3b
- qwen2.5

---

## 2. Inference Engine

Use:

- llama.cpp backend initially

Responsibilities:

- load model
- manage context
- run inference
- stream tokens
- manage memory

---

## 3. Local API Server

Expose endpoints like:

```text
POST /chat
POST /generate
GET /health
GET /models
```

This allows:

- OpenClaw integration
- MacBook integration
- local orchestration
- automation workflows

Eventually support:

- OpenAI-compatible API format

---

# Example API Request

```json
{
  "prompt": "Explain distributed AI systems",
  "max_tokens": 256
}
```

---

# Example API Response

```json
{
  "response": "Distributed AI systems separate workloads across devices..."
}
```

---

## 4. Telegram Integration

Application should:

- receive Telegram prompts
- process locally
- return generated responses

Telegram acts as:

- transport layer
- remote control layer
- async communication layer

---

## 5. Clipboard Workflow

Very important for MVP.

Capabilities:

- monitor clipboard
- send clipboard prompt to model
- auto-copy response
- quick actions

This enables:

- rapid workflows
- coding assistance
- mobile productivity
- low-friction usage

---

## 6. Background Service

Application should support:

- persistent inference service
- background API server
- Telegram polling/webhooks
- notification integration

---

# Suggested Tech Stack

| Layer | Recommendation |
|---|---|
| UI | Jetpack Compose |
| Language | Kotlin |
| Inference | llama.cpp |
| Local DB | Room |
| Networking | Ktor |
| Background Tasks | WorkManager |
| Telegram | Bot API |
| API Server | Embedded Ktor |

---

# Application Architecture

```text
Android App
   ├── UI Layer
   ├── Model Manager
   ├── Inference Engine
   ├── API Server
   ├── Telegram Module
   ├── Clipboard Module
   ├── Background Service
   └── Storage Layer
```

---

# Important Engineering Constraints

## RAM Constraints

Even with 16GB RAM:

- avoid giant contexts
- avoid huge models
- prioritize responsiveness

Recommended:

| Setting | Recommended |
|---|---|
| Context | 2048–4096 |
| Quantization | Q4_K_M |
| Model Size | 3B–4B |

---

# Thermal Management

Application should:

- detect overheating
- pause inference
- reduce context dynamically
- unload idle models

---

# Battery Management

Application should:

- support low-power mode
- stop background inference when needed
- reduce token streaming frequency

---

# Security Considerations

If APIs become externally accessible:

- require authentication
- limit LAN access
- encrypt sensitive communication
- avoid exposing inference publicly

---

# Future Features

## Potential Advanced Features

- voice assistant
- multimodal support
- camera integration
- offline RAG
- vector database
- multi-agent coordination
- tool calling
- robotics integration
- wearable integration

---

# Long-Term Ecosystem Vision

Eventually the system becomes:

```text
Phone = Edge AI Worker
Mac = Orchestrator
Claude = Cloud Reasoning
Telegram = Transport Layer
OpenClaw = Workflow Engine
```

This creates:

```text
Distributed Personal AI Infrastructure
```

---

# Final Goal

Create a portable distributed AI ecosystem where:

- phone performs inference
- Mac orchestrates workflows
- Telegram bridges communication
- local AI remains lightweight and modular
- cloud reasoning is used only when needed

This becomes:

```text
Personal Distributed Edge AI Infrastructure
```

