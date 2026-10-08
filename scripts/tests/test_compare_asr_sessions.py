import json
import tempfile
import unittest
from pathlib import Path
from scripts.compare_asr_sessions import summarize


class SessionMetricsTest(unittest.TestCase):
    def metrics(self, records):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.jsonl"
            path.write_text("\n".join(json.dumps(r) for r in records), encoding="utf-8")
            return summarize(path)

    def segment(self, a, b, compute=11000):
        return dict(type="segment", start_sample=a*16000, end_sample=b*16000,
                    rate=16000, compute_ms=compute, decode_ms=8000,
                    backlog_s=12, thermal_status=0, pss_kb=900*1024)

    def test_overlap_is_counted_once_and_missing_tail_reported(self):
        m = self.metrics([dict(type="start", version=1), self.segment(0, 12), self.segment(11, 23),
                          dict(type="end", state="incomplete", captured_samples=34*16000)])
        self.assertEqual(m["processed_unique_s"], 23)
        self.assertEqual(m["unprocessed_s"], 11)
        self.assertAlmostEqual(m["rtf"], 22/23, places=4)
        self.assertEqual(m["beam_size"], 5)

    def test_gap_is_unprocessed_even_with_later_completed_window(self):
        m = self.metrics([self.segment(0, 12), self.segment(23, 35),
                          dict(type="end", state="incomplete", captured_samples=35*16000)])
        self.assertEqual(m["gaps_s"], 11)
        self.assertEqual(m["unprocessed_s"], 11)

    def test_tail_excluded_from_full_window_mean_and_interruption_unknown(self):
        m = self.metrics([dict(type="start", version=2, beam_size=2, cpu_threads=2),
                          self.segment(0, 12), self.segment(11, 14, 3000)])
        self.assertEqual(m["full_windows"], 1)
        self.assertEqual(m["full_window_mean_compute_ms"], 11000)
        self.assertIsNone(m["unprocessed_s"])
        self.assertIn("interrupted", m["state"])

    def test_out_of_order_rejected(self):
        with self.assertRaises(ValueError):
            self.metrics([self.segment(11, 23), self.segment(0, 12)])


if __name__ == "__main__":
    unittest.main()
