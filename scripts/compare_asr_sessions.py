"""Compare private continuous-gate JSONL journals locally; never upload transcripts."""
import argparse
import json
from pathlib import Path
from statistics import mean


def summarize(path):
    records = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    start = next((r for r in records if r.get("type") == "start"), {})
    end = next((r for r in reversed(records) if r.get("type") == "end"), None)
    segments = [r for r in records if r.get("type") == "segment"]
    previous_end = 0
    unique_s = 0.0
    gaps_s = 0.0
    for r in segments:
        rate = r["rate"]
        a, b = r["start_sample"], r["end_sample"]
        if rate != 16000 or a < 0 or b <= a or b <= previous_end:
            raise ValueError("Invalid or out-of-order sample offsets")
        gaps_s += max(0, a - previous_end) / rate
        unique_s += (b - max(a, previous_end)) / rate
        previous_end = b
    compute_ms = sum(r["compute_ms"] for r in segments)
    # Full-window metrics isolate decoder changes from a differently sized Stop tail.
    full = [r for r in segments if r["end_sample"] - r["start_sample"] == 12 * r["rate"]]
    def average(field, rows):
        return round(mean(r[field] for r in rows), 3) if rows else None
    captured_s = end["captured_samples"] / 16000 if end else None
    return {
        "file": str(path),
        "beam_size": start.get("beam_size", 5 if start.get("version") == 1 else None),
        "cpu_threads": start.get("cpu_threads", 2 if start.get("version") == 1 else None),
        "state": end.get("state") if end else "interrupted (no end record)",
        "segments": len(segments), "captured_s": captured_s,
        "processed_unique_s": round(unique_s, 4),
        "unprocessed_s": round(max(0, captured_s - unique_s), 4) if end else None,
        "gaps_s": round(gaps_s, 4),
        "rtf": round(compute_ms / (unique_s * 1000), 4) if unique_s else None,
        "mean_decode_ms": average("decode_ms", segments),
        "full_windows": len(full), "full_window_mean_decode_ms": average("decode_ms", full),
        "full_window_mean_compute_ms": average("compute_ms", full),
        "max_backlog_s": max((r["backlog_s"] for r in segments), default=None),
        "max_thermal_status": max((r["thermal_status"] for r in segments), default=None),
        "max_pss_mb": round(max((r["pss_kb"] for r in segments), default=0) / 1024, 2),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    baseline, candidate = summarize(args.baseline), summarize(args.candidate)
    changes = {}
    for field in ("rtf", "full_window_mean_decode_ms", "full_window_mean_compute_ms"):
        before, after = baseline[field], candidate[field]
        changes[field + "_reduction_pct"] = round(100 * (1 - after / before), 2) if before and after is not None else None
    print(json.dumps({"baseline": baseline, "candidate": candidate, "changes": changes,
                      "quality": "Compare Hebrew, numbers and boundary text manually; timing does not prove accuracy.",
                      "comparison": "Use identical source audio and inspect shared windows; different recordings are indicative only."},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
