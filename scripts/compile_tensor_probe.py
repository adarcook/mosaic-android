"""Run privately with the authorized Tensor SDK installed; never commit its outputs.

Dependencies: ai-edge-litert-nightly==2.2.0.dev20260809,
ai-edge-litert-sdk-google-tensor==2.1.6 (SDK v2.0 archive), flatbuffers.
The 1x8 ADD graph is only a driver check, not a speech benchmark.
"""
import argparse
from pathlib import Path
import flatbuffers
from ai_edge_litert import schema_py_generated as s
from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.google_tensor import target
import ai_edge_litert_sdk_google_tensor

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="Private output directory, outside the repository")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    # Wheel installation can drop executable bits. Restore only actual ELF tools.
    sdk = Path(ai_edge_litert_sdk_google_tensor.path_to_sdk_libs())
    tools = sdk / "third_party/unsupported_toolchains/darwinn_riscv/llvmorg_23_init/bin"
    for path in tools.iterdir():
        if path.is_file():
            with path.open("rb") as f:
                executable = f.read(4) == b"\x7fELF"
            if executable:
                path.chmod(path.stat().st_mode | 0o111)
    model = s.ModelT(); model.version = 3; model.buffers = [s.BufferT()]
    code = s.OperatorCodeT(); code.builtinCode = s.BuiltinOperator.ADD; code.version = 1
    model.operatorCodes = [code]
    graph = s.SubGraphT(); graph.name = "main"; graph.inputs = [0, 1]; graph.outputs = [2]; graph.tensors = []
    for name in ["x", "y", "sum"]:
        tensor = s.TensorT(); tensor.name = name; tensor.shape = [1, 8]
        tensor.type = s.TensorType.FLOAT32; tensor.buffer = 0; graph.tensors.append(tensor)
    op = s.OperatorT(); op.inputs = [0, 1]; op.outputs = [2]; op.opcodeIndex = 0
    op.builtinOptionsType = s.BuiltinOptions.AddOptions; op.builtinOptions = s.AddOptionsT()
    graph.operators = [op]; model.subgraphs = [graph]
    builder = flatbuffers.Builder(1024); builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    original = args.output / "probe.tflite"; original.write_bytes(bytes(builder.Output()))
    result = aot_compile.aot_compile(str(original), target=[target.Target(target.SocModel.TENSOR_G5)], keep_going=False)
    report = result.compilation_report(); print(report)
    (args.output / "compilation-report.txt").write_text(report)
    result.export(str(args.output), model_name="probe")
    compiled = s.Model.GetRootAsModel((args.output / "probe_Google_Tensor_G5.tflite").read_bytes(), 0)
    if compiled.SubgraphsLength() != 1 or compiled.Subgraphs(0).OperatorsLength() != 1:
        raise RuntimeError("Probe was not fully compiled")
    code = compiled.OperatorCodes(compiled.Subgraphs(0).Operators(0).OpcodeIndex())
    if code.CustomCode() != b"DISPATCH_OP":
        raise RuntimeError("Expected only DISPATCH_OP; refusing CPU fallback")

if __name__ == "__main__":
    main()
