"""Export the Hebrew ivrit.ai Whisper Large v3 Turbo encoder to LiteRT.

This script is intentionally local-only: point --model-dir at a complete local
Transformers checkpoint directory. It never contacts Hugging Face.

Run on Linux with Python 3.11. The official LiteRT Torch converter currently
supports Linux and recommends Python 3.11.

Expected source model:
  ivrit-ai/whisper-large-v3-turbo
  Whisper Large v3 Turbo Hebrew fine-tune
  encoder_layers=32, decoder_layers=4, d_model=1280, num_mel_bins=128
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np
import torch
from transformers import AutoModelForSpeechSeq2Seq
import litert_torch

EXPECTED = {
    "model_type": "whisper",
    "d_model": 1280,
    "encoder_layers": 32,
    "decoder_layers": 4,
    "num_mel_bins": 128,
    "max_source_positions": 1500,
}
INPUT_SHAPE = (1, 128, 3000)


class EncoderOnly(torch.nn.Module):
    def __init__(self, whisper_model: torch.nn.Module) -> None:
        super().__init__()
        self.encoder = whisper_model.model.encoder

    def forward(self, input_features: torch.Tensor) -> torch.Tensor:
        # Keep the exported interface tensor-only. The outer converter receives
        # positional tensors only; HF-specific kwargs stay encapsulated here.
        return self.encoder(
            input_features=input_features,
            return_dict=False,
        )[0]


class EncoderFrontend(torch.nn.Module):
    """Whisper conv frontend + learned positional embeddings, no Transformer."""

    def __init__(self, whisper_model: torch.nn.Module) -> None:
        super().__init__()
        encoder = whisper_model.model.encoder
        self.conv1 = encoder.conv1
        self.conv2 = encoder.conv2
        self.embed_positions = encoder.embed_positions

    def forward(self, input_features: torch.Tensor) -> torch.Tensor:
        hidden_states = torch.nn.functional.gelu(self.conv1(input_features))
        hidden_states = torch.nn.functional.gelu(self.conv2(hidden_states))
        hidden_states = hidden_states.permute(0, 2, 1)
        positions = torch.arange(
            self.embed_positions.num_embeddings,
            device=hidden_states.device,
        )
        return hidden_states + self.embed_positions(positions)


class EncoderConv1Only(torch.nn.Module):
    """Whisper conv1 only, no activation or downstream frontend ops."""

    def __init__(self, whisper_model: torch.nn.Module) -> None:
        super().__init__()
        self.conv1 = whisper_model.model.encoder.conv1

    def forward(self, input_features: torch.Tensor) -> torch.Tensor:
        return self.conv1(input_features)


class EncoderConv1Direct2d(torch.nn.Module):
    """The same Whisper conv1 weights expressed directly as Conv2d.

    Input is NHWC-like logical audio data converted to NCHW for PyTorch:
    [1, 1, 3000, 128]. This avoids the Conv1d lowering's outer
    RESHAPE/TRANSPOSE pairs and isolates the Tensor compiler's CONV_2D path.
    """

    def __init__(self, whisper_model: torch.nn.Module) -> None:
        super().__init__()
        source = whisper_model.model.encoder.conv1
        self.conv2d = torch.nn.Conv2d(
            in_channels=source.in_channels,
            out_channels=source.out_channels,
            kernel_size=(1, source.kernel_size[0]),
            stride=(1, source.stride[0]),
            padding=(0, source.padding[0]),
            bias=source.bias is not None,
        )
        with torch.no_grad():
            self.conv2d.weight.copy_(source.weight.unsqueeze(2))
            if source.bias is not None:
                self.conv2d.bias.copy_(source.bias)

    def forward(self, input_features: torch.Tensor) -> torch.Tensor:
        # Input: [batch, 1, time, mel]. Convert to PyTorch NCHW where channels
        # are mel bins and width is time, then convert output back to a simple
        # [batch, time, channels] layout.
        x = input_features.permute(0, 3, 1, 2)
        y = self.conv2d(x)
        return y.squeeze(2).permute(0, 2, 1)


class EncoderConvOnly(torch.nn.Module):
    """Whisper conv1/GELU + conv2/GELU + layout change, no positions."""

    def __init__(self, whisper_model: torch.nn.Module) -> None:
        super().__init__()
        encoder = whisper_model.model.encoder
        self.conv1 = encoder.conv1
        self.conv2 = encoder.conv2

    def forward(self, input_features: torch.Tensor) -> torch.Tensor:
        hidden_states = torch.nn.functional.gelu(self.conv1(input_features))
        hidden_states = torch.nn.functional.gelu(self.conv2(hidden_states))
        return hidden_states.permute(0, 2, 1)


class EncoderPositionalAddOnly(torch.nn.Module):
    """Whisper learned positional embedding lookup + add, no convolutions."""

    def __init__(self, whisper_model: torch.nn.Module) -> None:
        super().__init__()
        self.embed_positions = whisper_model.model.encoder.embed_positions

    def forward(self, hidden_states: torch.Tensor) -> torch.Tensor:
        positions = torch.arange(
            self.embed_positions.num_embeddings,
            device=hidden_states.device,
        )
        return hidden_states + self.embed_positions(positions)


class EncoderBlockOnly(torch.nn.Module):
    """One real ivrit.ai Whisper encoder Transformer block, no frontend."""

    def __init__(self, whisper_model: torch.nn.Module) -> None:
        super().__init__()
        self.layer = whisper_model.model.encoder.layers[0]

    def forward(self, hidden_states: torch.Tensor) -> torch.Tensor:
        return self.layer(hidden_states, None)


def validate_local_checkpoint(model_dir: Path) -> dict:
    required = [
        model_dir / "config.json",
        model_dir / "preprocessor_config.json",
        model_dir / "model.safetensors",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise SystemExit(
            "Incomplete local checkpoint. Missing:\n  " + "\n  ".join(missing)
        )

    config = json.loads((model_dir / "config.json").read_text())
    mismatches = {
        key: (config.get(key), expected)
        for key, expected in EXPECTED.items()
        if config.get(key) != expected
    }
    if mismatches:
        details = "\n".join(
            f"  {key}: got {actual!r}, expected {expected!r}"
            for key, (actual, expected) in mismatches.items()
        )
        raise SystemExit(
            "Checkpoint does not match ivrit.ai Whisper Large v3 Turbo:\n" + details
        )

    pre = json.loads((model_dir / "preprocessor_config.json").read_text())
    if pre.get("feature_size") != 128 or pre.get("nb_max_frames") != 3000:
        raise SystemExit(
            "Unexpected preprocessing contract: expected feature_size=128 and "
            "nb_max_frames=3000"
        )
    if pre.get("sampling_rate") != 16000:
        raise SystemExit("Unexpected sample rate; expected 16 kHz")

    return config


def first_array(value):
    if isinstance(value, (tuple, list)):
        if not value:
            raise RuntimeError("LiteRT converter returned an empty output sequence")
        value = value[0]
    if hasattr(value, "numpy"):
        value = value.numpy()
    return np.asarray(value)


def main() -> None:
    if sys.version_info[:2] != (3, 11):
        raise SystemExit(
            f"Use Python 3.11 for LiteRT Torch conversion; got {sys.version.split()[0]}"
        )
    if sys.platform != "linux":
        raise SystemExit(
            "LiteRT Torch conversion is run on Linux. Use a Linux x86_64 environment "
            "for this export step; do not attempt it with macOS Python 3.14."
        )

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model-dir",
        required=True,
        type=Path,
        help="Local directory containing the complete ivrit.ai Transformers checkpoint",
    )
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="Output .tflite path, outside Git",
    )
    parser.add_argument(
        "--diagnostic-encoder-layers",
        type=int,
        default=None,
        help=(
            "Export only the first N encoder layers as a compiler diagnostic. "
            "This is not a user-facing ASR model. Valid range: 1..32."
        ),
    )
    parser.add_argument(
        "--diagnostic-component",
        choices=("frontend", "conv1", "conv1-direct2d", "conv", "positional-add", "block"),
        default=None,
        help=(
            "Export only a Whisper encoder component: full frontend, conv1, "
            "direct Conv2d control, full conv path, positional-add path, or "
            "one Transformer block. "
            "Mutually exclusive "
            "with --diagnostic-encoder-layers."
        ),
    )
    args = parser.parse_args()

    if (
        args.diagnostic_component is not None
        and args.diagnostic_encoder_layers is not None
    ):
        raise SystemExit(
            "--diagnostic-component and --diagnostic-encoder-layers are mutually exclusive"
        )

    model_dir = args.model_dir.expanduser().resolve()
    output = args.output.expanduser().resolve()
    validate_local_checkpoint(model_dir)
    output.parent.mkdir(parents=True, exist_ok=True)

    print(f"Loading local checkpoint: {model_dir}")
    model = AutoModelForSpeechSeq2Seq.from_pretrained(
        str(model_dir),
        local_files_only=True,
        torch_dtype=torch.float32,
        low_cpu_mem_usage=True,
    ).eval()

    if args.diagnostic_encoder_layers is not None:
        layer_count = args.diagnostic_encoder_layers
        if not 1 <= layer_count <= EXPECTED["encoder_layers"]:
            raise SystemExit(
                "--diagnostic-encoder-layers must be between 1 and "
                f"{EXPECTED['encoder_layers']}"
            )
        if layer_count < EXPECTED["encoder_layers"]:
            original_layers = model.model.encoder.layers
            model.model.encoder.layers = torch.nn.ModuleList(
                list(original_layers[:layer_count])
            )
            print(
                "DIAGNOSTIC ONLY: exporting the first "
                f"{layer_count}/{EXPECTED['encoder_layers']} encoder layers"
            )

    expected_output_shape = (1, 1500, EXPECTED["d_model"])
    if args.diagnostic_component == "frontend":
        encoder = EncoderFrontend(model).eval()
        sample_shape = INPUT_SHAPE
        print("DIAGNOSTIC ONLY: exporting Whisper convolutional frontend")
    elif args.diagnostic_component == "conv1":
        encoder = EncoderConv1Only(model).eval()
        sample_shape = INPUT_SHAPE
        expected_output_shape = (1, EXPECTED["d_model"], 3000)
        print("DIAGNOSTIC ONLY: exporting Whisper conv1 only")
    elif args.diagnostic_component == "conv1-direct2d":
        encoder = EncoderConv1Direct2d(model).eval()
        sample_shape = (1, 1, 3000, EXPECTED["num_mel_bins"])
        expected_output_shape = (1, 3000, EXPECTED["d_model"])
        print("DIAGNOSTIC ONLY: exporting Whisper conv1 as direct Conv2d")
    elif args.diagnostic_component == "conv":
        encoder = EncoderConvOnly(model).eval()
        sample_shape = INPUT_SHAPE
        print("DIAGNOSTIC ONLY: exporting Whisper conv path")
    elif args.diagnostic_component == "positional-add":
        encoder = EncoderPositionalAddOnly(model).eval()
        sample_shape = (1, 1500, EXPECTED["d_model"])
        print("DIAGNOSTIC ONLY: exporting Whisper positional-add path")
    elif args.diagnostic_component == "block":
        encoder = EncoderBlockOnly(model).eval()
        sample_shape = (1, 1500, EXPECTED["d_model"])
        print("DIAGNOSTIC ONLY: exporting one Whisper Transformer encoder block")
    else:
        encoder = EncoderOnly(model).eval()
        sample_shape = INPUT_SHAPE
    total = sum(p.numel() for p in model.parameters())
    encoder_total = sum(p.numel() for p in encoder.parameters())
    print(f"Full parameters: {total:,}")
    print(f"Encoder parameters: {encoder_total:,}")

    # Deterministic non-zero sample helps expose conversion/parity problems that
    # an all-zero tensor can hide.
    torch.manual_seed(7)
    sample = torch.randn(sample_shape, dtype=torch.float32) * 0.05
    sample_inputs = (sample,)

    with torch.no_grad():
        reference = encoder(sample).detach().cpu().numpy()

    if reference.shape != expected_output_shape:
        raise RuntimeError(
            f"Unexpected PyTorch encoder output shape: {reference.shape}; "
            f"expected {expected_output_shape}"
        )
    if not np.isfinite(reference).all():
        raise RuntimeError("PyTorch encoder produced non-finite values")

    print("Converting encoder directly from PyTorch to LiteRT…")
    edge_model = litert_torch.convert(encoder, sample_inputs)

    print("Running host parity check…")
    converted = first_array(edge_model(*sample_inputs))
    if converted.shape != reference.shape:
        raise RuntimeError(
            f"Converted output shape {converted.shape} != reference {reference.shape}"
        )
    if not np.isfinite(converted).all():
        raise RuntimeError("Converted LiteRT encoder produced non-finite values")

    abs_error = np.abs(reference - converted)
    max_abs = float(abs_error.max())
    mean_abs = float(abs_error.mean())
    p999 = float(np.quantile(abs_error, 0.999))
    print(f"Parity max abs error: {max_abs:.8f}")
    print(f"Parity mean abs error: {mean_abs:.8f}")
    print(f"Parity p99.9 abs error: {p999:.8f}")

    # Encoder conversion may introduce isolated floating-point outliers while the
    # overall tensor remains numerically equivalent. Gate on both global average
    # error and a high percentile, while retaining a loose ceiling for the single
    # worst element. End-to-end transcript parity remains the real acceptance gate.
    if max_abs > 1e-2 or p999 > 2e-3 or mean_abs > 5e-4:
        raise RuntimeError(
            "LiteRT encoder parity is outside the initial acceptance threshold"
        )

    edge_model.export(str(output))
    print(f"Exported: {output}")
    print(f"Size: {output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
