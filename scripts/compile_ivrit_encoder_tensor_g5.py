"""AOT-compile an exported ivrit.ai Whisper encoder for Pixel 10 / Tensor G5.

Requires the authorized Google Tensor SDK environment. Compiler output stays
outside Git. The script accepts only the encoder-only LiteRT graph produced by
export_ivrit_whisper_encoder.py.
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


EXPECTED_INPUT = [1, 128, 3000]


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


def inspect_encoder(path: Path) -> None:
    model = schema.Model.GetRootAsModel(path.read_bytes(), 0)
    if model.SubgraphsLength() != 1:
        raise RuntimeError(
            f"Expected one encoder subgraph, got {model.SubgraphsLength()}"
        )
    graph = model.Subgraphs(0)
    if graph.InputsLength() != 1:
        raise RuntimeError(f"Expected one encoder input, got {graph.InputsLength()}")
    tensor = graph.Tensors(graph.Inputs(0))
    shape = [tensor.Shape(i) for i in range(tensor.ShapeLength())]
    if shape != EXPECTED_INPUT:
        raise RuntimeError(
            f"Expected encoder input {EXPECTED_INPUT}, got {shape}; refusing compile"
        )
    print(
        f"Encoder graph: {graph.OperatorsLength()} operators; "
        f"input shape={shape}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("encoder", type=Path, help="Encoder-only LiteRT .tflite")
    parser.add_argument("output", type=Path, help="Private output directory outside Git")
    args = parser.parse_args()

    encoder = args.encoder.expanduser().resolve()
    output = args.output.expanduser().resolve()
    if not encoder.is_file():
        raise SystemExit(f"Missing encoder: {encoder}")
    output.mkdir(parents=True, exist_ok=True)

    os.environ.setdefault("GOOGLE_TENSOR_BACKEND_ENABLED", "1")
    restore_sdk_executable_bits()
    inspect_encoder(encoder)

    result = aot_compile.aot_compile(
        str(encoder),
        target=[
            target.Target(target.SocModel.TENSOR_G5),
            target.FallbackTarget(),
        ],
        keep_going=True,
        google_tensor_truncation_type="half",
    )

    report = result.compilation_report()
    print(report)
    report_path = output / "ivrit-encoder-tensor-g5-compilation-report.txt"
    report_path.write_text(report)

    # Reports have changed formatting between SDK drops, so use a permissive
    # numeric search but require both the target name and at least one offloaded op.
    if "Google_Tensor_G5" not in report:
        raise RuntimeError("Compilation report contains no Tensor G5 target")
    fractions = [
        (int(a), int(b))
        for a, b in re.findall(r"(\d+)\s*/\s*(\d+)", report)
    ]
    if not fractions or max(a for a, _ in fractions) <= 0:
        raise RuntimeError("No encoder operations were reported as offloaded")

    result.export(str(output), model_name="ivrit_whisper_encoder")

    candidates = sorted(output.glob("*Google_Tensor_G5*.tflite"))
    if not candidates:
        raise RuntimeError("Tensor SDK emitted no Google Tensor G5 .tflite artifact")
    compiled = candidates[0]

    model = schema.Model.GetRootAsModel(compiled.read_bytes(), 0)
    custom_codes = set()
    for i in range(model.OperatorCodesLength()):
        code = model.OperatorCodes(i)
        value = code.CustomCode()
        if value:
            custom_codes.add(value.decode("utf-8", errors="replace"))
    if "DISPATCH_OP" not in custom_codes:
        raise RuntimeError(
            "Compiled artifact has no DISPATCH_OP; refusing a CPU-only result"
        )

    print(f"Tensor G5 artifact: {compiled}")
    print(f"Compilation report: {report_path}")


if __name__ == "__main__":
    main()
