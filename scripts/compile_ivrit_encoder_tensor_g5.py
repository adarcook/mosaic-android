"""AOT-compile an exported ivrit.ai Whisper encoder for Pixel 10 / Tensor G5.

This intentionally follows the Google Tensor SDK reference path:
- install the official ai-edge-litert-sdk-google-tensor wrapper;
- target Tensor G5 only;
- fail fast on compiler errors;
- do not add fallback, truncation, or large-model flags unless a future SDK
  release explicitly requires them.

Compiler output stays outside Git.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re

from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.google_tensor import target

try:
    import ai_edge_litert_sdk_google_tensor
except ModuleNotFoundError as exc:
    raise RuntimeError(
        "Install the official ai-edge-litert-sdk-google-tensor package in the "
        "AOT environment. Mosaic no longer uses a manually wired compiler path."
    ) from exc


EXPECTED_INPUT = [1, 128, 3000]
DIAGNOSTIC_BLOCK_INPUT = [1, 1500, 1280]
DIRECT_CONV2D_INPUT = [1, 1, 3000, 128]
SYNTHETIC_CONV2D_INPUT = [1, 32, 32, 32]


def sdk_root() -> Path:
    root = Path(ai_edge_litert_sdk_google_tensor.path_to_sdk_libs()).resolve()
    compiler = root / "liblitert_plugin_compiler.so"
    if not compiler.is_file():
        raise RuntimeError(f"Tensor compiler not found through official wrapper: {compiler}")
    return root


def _builtin_operator_names() -> dict[int, str]:
    return {
        value: name
        for name, value in vars(schema.BuiltinOperator).items()
        if isinstance(value, int) and not name.startswith("_")
    }


def inspect_encoder(path: Path) -> int:
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
    supported_shapes = (
        EXPECTED_INPUT,
        DIAGNOSTIC_BLOCK_INPUT,
        DIRECT_CONV2D_INPUT,
        SYNTHETIC_CONV2D_INPUT,
    )
    if shape not in supported_shapes:
        raise RuntimeError(
            f"Unexpected input shape {shape}; expected one of {supported_shapes}"
        )

    operator_count = graph.OperatorsLength()
    print(f"Encoder graph: {operator_count} operators; input shape={shape}")

    builtin_names = _builtin_operator_names()
    op_names: list[str] = []
    for i in range(operator_count):
        op = graph.Operators(i)
        opcode = model.OperatorCodes(op.OpcodeIndex())
        builtin_code = opcode.BuiltinCode()
        name = builtin_names.get(builtin_code, f"BUILTIN_{builtin_code}")
        if name == "CUSTOM":
            custom = opcode.CustomCode()
            if custom:
                name = f"CUSTOM:{custom.decode('utf-8', errors='replace')}"
        op_names.append(name)

    if operator_count <= 32:
        print("Operator sequence: " + " -> ".join(op_names))
    else:
        print(f"Operator sequence omitted ({operator_count} ops)")

    return operator_count


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

    root = sdk_root()
    print(f"Tensor SDK root: {root}")
    print(f"Tensor compiler: {root / 'liblitert_plugin_compiler.so'}")
    operator_count = inspect_encoder(encoder)

    result = aot_compile.aot_compile(
        str(encoder),
        target=[target.Target(target.SocModel.TENSOR_G5)],
        keep_going=False,
    )

    report = result.compilation_report()
    print(report)
    report_path = output / "ivrit-encoder-tensor-g5-compilation-report.txt"
    report_path.write_text(report)

    match = re.search(
        r"Subgraph\s+0\s+fully compiled:\s+(\d+)\s*/\s*(\d+)\s+ops offloaded",
        report,
    )
    if not match:
        raise RuntimeError("Compilation report does not confirm a fully compiled subgraph")

    offloaded = int(match.group(1))
    reported_total = int(match.group(2))
    if offloaded != reported_total or reported_total != operator_count:
        raise RuntimeError(
            "Tensor G5 compilation was not complete: "
            f"report={offloaded}/{reported_total}, graph_ops={operator_count}"
        )

    result.export(str(output), model_name="ivrit_whisper_encoder")

    candidates = sorted(output.glob("*Google_Tensor_G5*.tflite"))
    if not candidates:
        raise RuntimeError("Tensor SDK emitted no Google Tensor G5 .tflite artifact")

    compiled = candidates[0]
    compiled_model = schema.Model.GetRootAsModel(compiled.read_bytes(), 0)
    custom_codes: set[str] = set()
    for i in range(compiled_model.OperatorCodesLength()):
        code = compiled_model.OperatorCodes(i)
        value = code.CustomCode()
        if value:
            custom_codes.add(value.decode("utf-8", errors="replace"))

    if "DISPATCH_OP" not in custom_codes:
        raise RuntimeError(
            "Compiled artifact has no DISPATCH_OP; refusing a non-Tensor result"
        )

    print(f"Tensor G5 artifact: {compiled}")
    print(f"Compilation report: {report_path}")


if __name__ == "__main__":
    main()
