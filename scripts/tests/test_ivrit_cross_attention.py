import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from export_ivrit_whisper_cross_attention import EXPECTED, parity, project_numpy, validate_config


class CrossAttentionContractTest(unittest.TestCase):
    def test_cache_scaling_bias_and_layer_order(self):
        # Hand-calculated fixture: head_dim=16, scale=1/2.
        hidden = np.arange(16, dtype=np.float32).reshape(1, 1, 16)
        eye = np.eye(16, dtype=np.float32)
        weights = [(eye, eye * 3, np.ones(16, dtype=np.float32)),
                   (eye * 2, eye, np.ones(16, dtype=np.float32) * 5)]
        result = project_numpy(hidden, weights, heads=1)
        self.assertEqual(result.shape, (2, 2, 1, 1, 16))
        np.testing.assert_array_equal(result[0, 0], hidden / 2)
        np.testing.assert_array_equal(result[0, 1], hidden * 3 + 1)
        np.testing.assert_array_equal(result[1, 0], hidden)
        np.testing.assert_array_equal(result[1, 1], hidden + 5)

    def test_output_gate_rejects_shape_nan_and_error(self):
        reference = np.ones((2, 3), dtype=np.float32)
        for invalid in (np.ones((3, 2)), np.full((2, 3), np.nan), reference + 1):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    parity(reference, invalid)
        self.assertEqual(parity(reference, reference)["max_abs"], 0)

    def test_other_whisper_architecture_rejected(self):
        validate_config(EXPECTED)
        for field in EXPECTED:
            wrong = dict(EXPECTED)
            wrong[field] = None
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_config(wrong)

class CompilerReportTest(unittest.TestCase):
    def test_reject_partial_or_missing_offload(self):
        from compile_ivrit_cross_attention_tensor_g5 import require_full_offload
        require_full_offload("Subgraph 0 fully compiled: 20 / 20 ops offloaded", 20)
        for report in ("", "Subgraph 0 fully compiled: 19 / 20 ops offloaded",
                       "Subgraph 0 fully compiled: 21 / 21 ops offloaded"):
            with self.subTest(report=report), self.assertRaises(ValueError):
                require_full_offload(report, 20)


if __name__ == "__main__":
    unittest.main()
