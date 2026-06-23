# LLM — Localis: Private On-Device AI Infrastructure

This is the working repo behind **Localis**: a private, on-device LLM inference node running on an Android phone (Xiaomi 14 Ultra, 16GB RAM), plus the surrounding tooling — model conversion, a memory/RAG layer, and AI-agent integration (OpenClaw/Claude) that talks to it over the local network.

The core idea: no cloud dependency for inference. The phone runs the model; everything else (Mac, agents, bots) is a client.

## Repo Map

| Path | What it is |
|---|---|
| [`MobileAI/`](MobileAI/README.md) | **The Android app itself** — Localis, package `ai.localis.app`. LiteRT-LM inference engine, OpenAI-compatible API server, model manager, Telegram bridge. See its own README for build/run instructions and API usage. |
| [`memory-stack/`](memory-stack/) | Python proxy + persistent memory layer in front of Localis — ChromaDB-backed long-term memory (conversations, documents, persona facts), a learning loop that extracts facts from chat exchanges, and Telegram command handling. Talks to Localis over `localis.local:8080` (mDNS, with IP-override fallback). |
| [`litert_conversion/`](litert_conversion/) | Shell scripts (`setup.sh`, `run_capped.sh`) for setting up and running LiteRT-LM model conversions outside of Kaggle notebooks. |
| `Localis_LiteRTLM_*.ipynb` | Kaggle notebooks used to convert HuggingFace checkpoints (Qwen2.5, Qwen3-4B, Qwen3-8B, etc.) to `.litertlm` format on Kaggle's free TPU tier, including extended-context (`cache_length=8192`) variants. |
| `OPENCLAW_*.md` | Handoff and test prompts for validating Localis from an OpenClaw (Claude-in-VM) session — connectivity, round-trip, long-context retrieval, and known failure modes. |
| `LOCALIS_GAPS.md` | Running list of known gaps/issues in the Localis app. |
| `LOCALIS_LINKEDIN_POST.md` + [`linkedin_post_images/`](linkedin_post_images/) | Write-up of the project's build journey for sharing externally. |
| [`Researches/`](Researches/) | Notes gathered from various AI assistants (ChatGPT, Gemini, Perplexity) and reference material on LiteRT-LM and supermemory-style systems, used while designing this project. |
| [`Errors_Problems/`](Errors_Problems/) | Logcat dumps and screenshots from on-device debugging sessions — historical/diagnostic, not active docs. |
| [`docs/superpowers/`](docs/superpowers/) | Spec and plan documents for specific implementation efforts (e.g. the LiteRT-LM migration, UI stability pass) written using the Superpowers skill workflow. |
| `distributed_mobile_llm_architecture_plan.md` | Original architecture plan for the distributed phone-as-inference-node concept. |
| [`tools/`](tools/) | Standalone helper scripts (`convert_model.py`). |

## Architecture (top-level)

```
Mac / OpenClaw (Claude in a VM) / Telegram
       │  HTTP, local network
       ▼
memory-stack (optional proxy)        ← persistent memory, fact extraction
       │
       ▼
Xiaomi 14 Ultra — Localis app (MobileAI/)
  ├── LiteRT-LM Engine (GPU/NPU backend)
  ├── ApiServer (Ktor, :8080) — OpenAI-compatible
  └── TelegramPoller — optional bot bridge
```

For the app's own architecture, API routes, model catalog, and build steps, see **[`MobileAI/README.md`](MobileAI/README.md)**.

## Project History (short version)

1. Started as **MobileAI**, built on MLC-LLM — hit a GPU fence contention bug causing hard freezes under load.
2. Rebuilt the inference layer on **Google's LiteRT-LM** runtime instead (separate NPU silicon, sidesteps the GPU contention path) and rebranded to **Localis**.
3. Connected to **OpenClaw** (Claude running in a local VM) over the OpenAI-compatible API; found and fixed real protocol edge cases (content-array message format, model-mismatch handling).
4. Hit a **context-length wall** — OpenClaw's system prompt alone is ~7,600 tokens, beyond the default 4,096-token models. Converted custom extended-context (`ekv8192`) models on Kaggle's free TPU tier.
5. Found the **hardware ceiling**: `Qwen2.5-1.5B-ekv8192` runs stably at extended context; `Qwen3-4B-ekv8192` can crash on long-context generation; `Qwen3-8B-ekv8192` can crash the entire phone. Full writeup in `LOCALIS_LINKEDIN_POST.md`.

## Attribution

LiteRT-LM runtime by Google. Earlier app versions were based on **MLCChat** from [MLC-LLM](https://github.com/mlc-ai/mlc-llm) (Copyright (c) 2023 MLC LLM Team — Apache License 2.0); that engine has since been fully replaced. See [`MobileAI/LICENSE`](MobileAI/LICENSE) and [`MobileAI/NOTICE.md`](MobileAI/NOTICE.md) for full attribution.

Extensions Copyright (c) 2026 Akshit Harsola.
