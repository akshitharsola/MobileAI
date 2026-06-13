#!/usr/bin/env python3
"""
Localis Model Conversion Pipeline
Converts HuggingFace models to .litertlm format for LiteRT-LM.

Usage:
    python tools/convert_model.py --model Qwen/Qwen3-1.7B --output ~/Downloads/Qwen3-1.7B.litertlm
    python tools/convert_model.py --model Qwen/Qwen3-4B --output ~/Downloads/Qwen3-4B.litertlm

Requirements (run once):
    pip install ai-edge-torch ai-edge-litert huggingface_hub transformers

After conversion:
    adb push ~/Downloads/Qwen3-1.7B.litertlm /sdcard/Android/data/ai.mlc.mobileai/files/Qwen3-1.7B.litertlm
"""

import argparse
import os
import sys


def check_deps():
    missing = []
    for pkg in ["ai_edge_torch", "transformers", "huggingface_hub"]:
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg.replace("_", "-"))
    if missing:
        print(f"Missing packages: {', '.join(missing)}")
        print(f"Install with: pip install {' '.join(missing)}")
        sys.exit(1)


def convert(model_id: str, output_path: str, quantize: bool = True):
    import ai_edge_torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    import torch

    print(f"Loading {model_id} from HuggingFace...")
    tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        model_id,
        torch_dtype=torch.float32,
        trust_remote_code=True,
        low_cpu_mem_usage=True,
    )
    model.eval()

    print("Converting to LiteRT-LM format...")
    sample_inputs = tokenizer("Hello", return_tensors="pt")
    input_ids = sample_inputs["input_ids"]

    edge_model = ai_edge_torch.convert(model, (input_ids,))

    if quantize:
        print("Applying int8 quantization...")
        from ai_edge_torch.quantize import pt2e_quantizer
        edge_model = pt2e_quantizer.quantize(edge_model)

    output_path = os.path.expanduser(output_path)
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    edge_model.export(output_path)
    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Saved to {output_path} ({size_mb:.0f} MB)")
    print(f"\nNext step:")
    print(f"  adb push {output_path} /sdcard/Android/data/ai.mlc.mobileai/files/{os.path.basename(output_path)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Convert HuggingFace model to .litertlm")
    parser.add_argument("--model", required=True, help="HuggingFace model ID (e.g. Qwen/Qwen3-1.7B)")
    parser.add_argument("--output", required=True, help="Output .litertlm file path")
    parser.add_argument("--no-quantize", action="store_true", help="Skip int8 quantization")
    args = parser.parse_args()

    check_deps()
    convert(args.model, args.output, quantize=not args.no_quantize)
