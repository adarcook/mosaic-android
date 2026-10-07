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
import shutil
import tempfile

from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.google_tensor import target
from ai_edge_litert.aot.vendors import fallback_backend
try:
    import ai_edge_litert_sdk_google_tensor
except ModuleNotFoundError:
    ai_edge_litert_sdk_google_tensor = None


EXPECTED_INPUT = [1, 128, 3000]
DIAGNOSTIC_BLOCK_INPUT = [1, 1500, 1280]


def resolve_sdk_root() -> Path:
    compiler = os.environ.get("GOOGLE_TENSOR_COMPILER_LIB")
    if compiler:
        configured = Path(compiler).expanduser().resolve()
        # Despite the environment variable name, LiteRT's Google Tensor backend
        # passes this value as sdk_libs_path. It therefore expects the SDK
        # directory, not the liblitert_plugin_compiler.so file itself.
        sdk_root = configured.parent if configured.is_file() else configured
        compiler_so = sdk_root / "liblitert_plugin_compiler.so"
        if not compiler_so.is_file():
            raise RuntimeError(
                "GOOGLE_TENSOR_COMPILER_LIB must point to the extracted SDK "
                f"directory (or its compiler .so). Missing: {compiler_so}"
            )
        return sdk_root

    if ai_edge_litert_sdk_google_tensor is not None:
        return Path(ai_edge_litert_sdk_google_tensor.path_to_sdk_libs()).resolve()

    raise RuntimeError(
        "Google Tensor SDK path is unavailable. Set GOOGLE_TENSOR_COMPILER_LIB "
        "to the extracted liblitert_plugin_compiler.so, or install the "
        "ai-edge-litert-sdk-google-tensor wrapper with its SDK location configured."
    )


def restore_sdk_executable_bits() -> None:
    sdk = resolve_sdk_root()

    # SDK releases do not all bundle the same LLVM directory layout. The v2
    # archive currently uses llvmorg_23_init, while older drops may use a
    # different toolchain layout or no bundled LLVM directory at all. Search
    # rather than hard-coding one release-specific path.
    third_party = sdk / "third_party"
    bin_dirs = (
        sorted(path for path in third_party.rglob("bin") if path.is_dir())
        if third_party.exists()
        else []
    )
    if not bin_dirs:
        print("Tensor SDK bundled LLVM tools not found; skipping executable-bit repair")
        return

    repaired = 0
    for tools in bin_dirs:
        for path in tools.iterdir():
            if not path.is_file():
                continue
            try:
                with path.open("rb") as handle:
                    is_elf = handle.read(4) == b"\x7fELF"
            except OSError:
                continue
            if is_elf:
                path.chmod(path.stat().st_mode | 0o111)
                repaired += 1

    print(
        f"Checked {len(bin_dirs)} Tensor SDK tool bin director"
        f"{'y' if len(bin_dirs) == 1 else 'ies'}; "
        f"ensured execute bits on {repaired} ELF tools"
    )


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
    if shape not in (EXPECTED_INPUT, DIAGNOSTIC_BLOCK_INPUT):
        raise RuntimeError(
            "Expected encoder input "
            f"{EXPECTED_INPUT} or diagnostic block input {DIAGNOSTIC_BLOCK_INPUT}, "
            f"got {shape}; refusing compile"
        )
    if shape == DIAGNOSTIC_BLOCK_INPUT:
        print("Diagnostic graph: one Transformer block with hidden-state input")
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
    sdk_root = resolve_sdk_root()
    # Normalize the value before LiteRT reads it: the backend treats this
    # variable as sdk_libs_path, even though its public name ends in _LIB.
    os.environ["GOOGLE_TENSOR_COMPILER_LIB"] = str(sdk_root)
    print(f"Tensor SDK root: {sdk_root}")
    print("Large model support: enabled")
    print(f"Tensor SDK libs path: {os.environ['GOOGLE_TENSOR_COMPILER_LIB']}")
    print(f"Tensor compiler: {sdk_root / 'liblitert_plugin_compiler.so'}")
    restore_sdk_executable_bits()
    inspect_encoder(encoder)

    before_errors = set(Path(tempfile.gettempdir()).glob("*.error"))
    result = aot_compile.aot_compile(
        str(encoder),
        target=[
            target.Target(target.SocModel.TENSOR_G5),
            fallback_backend.FallbackTarget(),
        ],
        keep_going=True,
        google_tensor_truncation_type="half",
        google_tensor_enable_large_model_support=True,
    )
    after_errors = set(Path(tempfile.gettempdir()).glob("*.error"))
    new_errors = sorted(
        after_errors - before_errors,
        key=lambda path: path.stat().st_mtime,
    )
    for index, error_path in enumerate(new_errors, start=1):
        preserved = output / f"tensor-g5-compiler-error-{index}.txt"
        shutil.copyfile(error_path, preserved)
        print(f"Preserved compiler stderr: {preserved}")

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
