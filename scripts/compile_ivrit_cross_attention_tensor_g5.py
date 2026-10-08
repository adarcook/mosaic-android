"""Fail-closed Tensor G5 AOT gate for Whisper cross-attention preparation."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re


def inspect_contract(path, schema):
    model = schema.Model.GetRootAsModel(path.read_bytes(), 0)
    if model.SubgraphsLength() != 1:
        raise ValueError("Expected exactly one cross-attention subgraph")
    graph = model.Subgraphs(0)
    if graph.InputsLength() != 1 or graph.OutputsLength() != 1:
        raise ValueError("Expected one input and one stacked K/V output")
    for index, shape in ((graph.Inputs(0), [1, 1500, 1280]),
                         (graph.Outputs(0), [4, 2, 1, 1500, 1280])):
        tensor = graph.Tensors(index)
        actual = [tensor.Shape(i) for i in range(tensor.ShapeLength())]
        if actual != shape or tensor.Type() != schema.TensorType.FLOAT32:
            raise ValueError(f"Unexpected FP32 tensor contract: {actual}, expected {shape}")
        if tensor.ShapeSignatureLength() and any(
                tensor.ShapeSignature(i) < 0 for i in range(tensor.ShapeSignatureLength())):
            raise ValueError("Dynamic tensor dimensions are not allowed in this gate")
    count = graph.OperatorsLength()
    if count == 0:
        raise ValueError("Empty cross-attention graph")
    return count


def require_full_offload(report, count):
    match = re.search(r"Subgraph\s+0\s+fully compiled:\s+(\d+)\s*/\s*(\d+)\s+ops offloaded", report)
    if not match or (int(match[1]), int(match[2])) != (count, count):
        raise ValueError("Tensor report does not confirm full cross-attention offload")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    from ai_edge_litert import schema_py_generated as schema
    from ai_edge_litert.aot import aot_compile
    from ai_edge_litert.aot.vendors.google_tensor import target
    from compile_ivrit_encoder_tensor_g5 import sdk_root

    model = args.model.expanduser().resolve()
    output = args.output.expanduser().resolve()
    count = inspect_contract(model, schema)
    os.environ.setdefault("GOOGLE_TENSOR_BACKEND_ENABLED", "1")
    print(f"Official Tensor SDK: {sdk_root()}")
    output.mkdir(parents=True, exist_ok=True)
    result = aot_compile.aot_compile(str(model),
        target=[target.Target(target.SocModel.TENSOR_G5)], keep_going=False)
    report = result.compilation_report()
    (output / "ivrit-cross-attention-tensor-g5-compilation-report.txt").write_text(report)
    print(report)
    require_full_offload(report, count)
    name = "ivrit_whisper_cross_attention"
    result.export(str(output), model_name=name)
    candidates = list(output.glob(name + "*Google_Tensor_G5*.tflite"))
    if len(candidates) != 1:
        raise ValueError("Expected exactly one compiled cross-attention artifact")
    inspect_contract(candidates[0], schema)
    compiled = schema.Model.GetRootAsModel(candidates[0].read_bytes(), 0)
    codes = [compiled.OperatorCodes(i).CustomCode() for i in range(compiled.OperatorCodesLength())]
    if b"DISPATCH_OP" not in codes:
        raise ValueError("No DISPATCH_OP: refusing an unaccelerated artifact")
    print(f"Compiled artifact: {candidates[0]}; device latency NOT TESTED")


if __name__ == "__main__":
    main()
