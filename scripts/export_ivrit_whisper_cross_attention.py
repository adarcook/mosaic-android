"""Export Whisper cross-attention K/V preparation; no decoder loop or audio.

Local FP32 safetensors checkpoint only. This is a compiler feasibility gate,
NOT a drop-in replacement for the quantized GGML decoder's cache.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

INPUT_SHAPE = (1, 1500, 1280)
OUTPUT_SHAPE = (4, 2, 1, 1500, 1280)
EXPECTED = {"model_type": "whisper", "d_model": 1280, "decoder_layers": 4,
            "decoder_attention_heads": 20, "max_source_positions": 1500}


def validate_config(config):
    for key, expected in EXPECTED.items():
        if config.get(key) != expected:
            raise ValueError(f"{key}: expected {expected!r}, got {config.get(key)!r}")


def project_numpy(hidden, weights, heads):
    """whisper.cpp flash-attention cache convention: scaled K, biased V.

    HF weights are [out,in]. whisper.cpp scales K by head_dim**(-1/4),
    with the other half of attention scaling applied to Q in the decoder.
    This output has no GGML cache padding and remains FP32.
    """
    scale = (hidden.shape[-1] // heads) ** -0.25
    return np.stack([np.stack([(hidden @ k.T) * scale,
                              hidden @ v.T + bias], axis=0)
                     for k, v, bias in weights], axis=0)


def parity(reference, actual, max_error=0.01, mean_error=0.0005):
    actual = np.asarray(actual)
    if actual.shape != reference.shape:
        raise ValueError(f"Output shape {actual.shape} != {reference.shape}")
    if not np.isfinite(reference).all() or not np.isfinite(actual).all():
        raise ValueError("Non-finite cross-attention output")
    error = np.abs(reference - actual)
    result = {"max_abs": float(error.max()), "mean_abs": float(error.mean())}
    if result["max_abs"] > max_error or result["mean_abs"] > mean_error:
        raise ValueError(f"Cross-attention parity failed: {result}")
    return result


def make_module(weights, heads):
    import torch

    class CrossPreparation(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.keys = torch.nn.ModuleList()
            self.values = torch.nn.ModuleList()
            for k, v, bias in weights:
                dim = k.shape[0]
                key = torch.nn.Linear(dim, dim, bias=False)
                value = torch.nn.Linear(dim, dim, bias=True)
                with torch.no_grad():
                    key.weight.copy_(torch.from_numpy(k))
                    value.weight.copy_(torch.from_numpy(v))
                    value.bias.copy_(torch.from_numpy(bias))
                self.keys.append(key)
                self.values.append(value)
            self.scale = (weights[0][0].shape[0] // heads) ** -0.25

        def forward(self, hidden):
            return torch.stack([torch.stack((key(hidden) * self.scale, value(hidden)), dim=0)
                                for key, value in zip(self.keys, self.values)], dim=0)

    return CrossPreparation().eval()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    import torch
    import litert_torch
    from safetensors import safe_open

    model_dir = args.model_dir.expanduser().resolve()
    config = json.loads((model_dir / "config.json").read_text())
    validate_config(config)
    weights = []
    # Load only the eight projections and four biases, not the full encoder.
    with safe_open(model_dir / "model.safetensors", framework="np") as checkpoint:
        for layer in range(4):
            prefix = f"model.decoder.layers.{layer}.encoder_attn."
            k = checkpoint.get_tensor(prefix + "k_proj.weight").astype(np.float32)
            v = checkpoint.get_tensor(prefix + "v_proj.weight").astype(np.float32)
            bias = checkpoint.get_tensor(prefix + "v_proj.bias").astype(np.float32)
            if k.shape != (1280, 1280) or v.shape != k.shape or bias.shape != (1280,):
                raise ValueError(f"Unexpected projection shapes in layer {layer}")
            weights.append((k, v, bias))

    module = make_module(weights, config["decoder_attention_heads"])
    samples = [np.random.default_rng(seed).normal(0, 0.05, INPUT_SHAPE).astype(np.float32)
               for seed in (7, 19)]
    example = torch.from_numpy(samples[0])
    edge = litert_torch.convert(module, (example,))
    results = []
    for sample in samples:
        reference = project_numpy(sample, weights, 20)
        with torch.no_grad():
            parity(reference, module(torch.from_numpy(sample)).numpy(), 0.001, 0.0001)
        actual = edge(torch.from_numpy(sample))
        if isinstance(actual, (tuple, list)):
            actual = actual[0]
        if hasattr(actual, "detach"):
            actual = actual.detach().cpu().numpy()
        results.append(parity(reference, actual))

    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    edge.export(str(output))
    report = {"component": "cross_attention_preparation", "input_shape": INPUT_SHAPE,
              "output_shape": OUTPUT_SHAPE, "layout": "layer,KV,batch,time,channel",
              "key_scale": 64 ** -0.25, "dtype": "float32", "parity": results,
              "ggml_quantized_cache_parity": "NOT TESTED",
              "pixel_latency": "NOT TESTED"}
    output.with_suffix(".parity.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Exported {output} ({output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
