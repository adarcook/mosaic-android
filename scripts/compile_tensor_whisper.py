"""Compile the public LiteRT Whisper Tiny graph for Tensor G5.

Run only in the authorized Tensor SDK environment. Compiler outputs stay outside Git.

Expected packages for the current Mosaic Tensor SDK experiment:
  ai-edge-litert-nightly==2.2.0.dev20260809
  ai-edge-litert-sdk-google-tensor==2.1.6
  flatbuffers

The compilation intentionally includes the Tensor fallback target. The goal is to
allow the heavy encoder partition to execute on Tensor G5 while unsupported control/
decoder work can remain on CPU. The Android gate executes graph/subgraph 0, expected
to be the Whisper encode signature.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re

from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.google_tensor import target
import ai_edge_litert_sdk_google_tensor


def restore_sdk_executable_bits() -> None:
    sdk = Path(ai_edge_litert_sdk_google_tensor.path_to_sdk_libs())
    tools = sdk / "third_party/unsupported_toolchains/darwinn_riscv/llvmorg_23_init/bin"
    if not tools.exists():
        raise RuntimeError(f"Tensor SDK LLVM directory not found: {tools}")
    for path in tools.iterdir():
        if not path.is_file():
            continue
        with path.open("rb") as handle:
            if handle.read(4) == b"\x7fELF":
                path.chmod(path.stat().st_mode | 0o111)


def inspect_source(model_path: Path) -> None:
    data = model_path.read_bytes()
    model = schema.Model.GetRootAsModel(data, 0)
    if model.SubgraphsLength() < 2:
        raise RuntimeError(
            f"Expected Whisper encode+decode graph, got {model.SubgraphsLength()} subgraphs"
        )
    first = model.Subgraphs(0)
    if first.InputsLength() != 1:
        raise RuntimeError(
            f"Expected encoder subgraph 0 to have one input; got {first.InputsLength()}"
        )
    tensor_index = first.Inputs(0)
    tensor = first.Tensors(tensor_index)
    shape = [tensor.Shape(i) for i in range(tensor.ShapeLength())]
    if shape != [1, 80, 3000]:
        raise RuntimeError(
            "Unexpected Whisper encoder input shape. "
            f"Expected [1, 80, 3000], got {shape}. Refusing to package a mismatched graph."
        )
    print(f"Source model: {model.SubgraphsLength()} subgraphs; encoder input {shape}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path, help="Verified whisper_tiny_30s_f32.tflite")
    parser.add_argument("output", type=Path, help="Private output directory outside Git")
    args = parser.parse_args()

    if not args.model.is_file():
        raise SystemExit(f"Missing model: {args.model}")
    args.output.mkdir(parents=True, exist_ok=True)

    # Some Tensor SDK installs gate backend registration behind this flag.
    os.environ.setdefault("GOOGLE_TENSOR_BACKEND_ENABLED", "1")
    restore_sdk_executable_bits()
    inspect_source(args.model)

    tensor_g5 = target.Target(target.SocModel.TENSOR_G5)
    fallback = target.FallbackTarget()

    result = aot_compile.aot_compile(
        str(args.model),
        target=[tensor_g5, fallback],
        keep_going=True,
        google_tensor_truncation_type="half",
    )

    report = result.compilation_report()
    print(report)
    (args.output / "whisper-tensor-g5-compilation-report.txt").write_text(report)

    # At least one Google Tensor partition must exist. We deliberately do not
    # require the decoder/control graph to compile; CPU fallback is part of the
    # hybrid plan.
    if "Google_Tensor_G5" not in report:
        raise RuntimeError("Compilation report contains no Tensor G5 target")
    offloaded = [int(v) for v in re.findall(r"(\d+)\s*/\s*\d+\s+ops offloaded", report)]
    if not offloaded or max(offloaded) <= 0:
        raise RuntimeError("No Whisper operations were offloaded to Tensor G5")

    result.export(str(args.output), model_name="whisper_tiny_30s")
    compiled = args.output / "whisper_tiny_30s_Google_Tensor_G5.tflite"
    if not compiled.is_file():
        raise RuntimeError(f"Compiler did not emit expected artifact: {compiled}")

    compiled_model = schema.Model.GetRootAsModel(compiled.read_bytes(), 0)
    custom_codes = set()
    for i in range(compiled_model.OperatorCodesLength()):
        code = compiled_model.OperatorCodes(i)
        value = code.CustomCode()
        if value:
            custom_codes.add(value.decode("utf-8", errors="replace"))
    if "DISPATCH_OP" not in custom_codes:
        raise RuntimeError("Compiled artifact has no DISPATCH_OP; refusing CPU-only artifact")

    print(f"Tensor G5 artifact ready: {compiled}")


if __name__ == "__main__":
    main()
