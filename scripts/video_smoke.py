"""Compose smoke: real media, real upload, Kafka worker, Mock transcript/agents."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

BASE = os.environ.get("HARNESS_URL", "http://127.0.0.1:8080")


def call(method: str, path: str, body: object | bytes | None = None, headers: dict | None = None) -> dict:
    data = None if body is None else body if isinstance(body, bytes) else json.dumps(body).encode()
    sent_headers = {"Content-Type": "application/json"} if not isinstance(body, bytes) else {}
    sent_headers.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, headers=sent_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: {error.read().decode()}") from error


def main() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        video = Path(temporary) / "smoke.mp4"
        # The Worker image already has FFmpeg for media processing. Generate the
        # fixture there so CI does not need a second host-side installation.
        subprocess.run(["docker", "compose", "exec", "-T", "worker", "ffmpeg", "-y",
                        "-f", "lavfi", "-i", "testsrc=size=320x180:rate=12",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000",
                        "-t", "2", "-c:v", "mpeg4", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "/tmp/long-video-harness-smoke.mp4"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["docker", "compose", "cp", "worker:/tmp/long-video-harness-smoke.mp4", str(video)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        size = video.stat().st_size
        session = call("POST", "/api/v1/uploads", {"filename": video.name, "contentType": "video/mp4", "sizeBytes": size})
        digest = hashlib.sha256()
        with video.open("rb") as source:
            for index in range(session["chunkCount"]):
                chunk = source.read(session["chunkSize"])
                digest.update(chunk)
                call("PUT", f"/api/v1/uploads/{session['uploadId']}/chunks/{index}", chunk,
                     {"X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest()})
        video_id = call("POST", f"/api/v1/uploads/{session['uploadId']}/complete", {"sha256": digest.hexdigest()})["videoId"]
        transcript = {"segments": [
            {"id": "s1", "startMs": 0, "endMs": 600, "text": "The demo explains a reliable upload pipeline."},
            {"id": "s2", "startMs": 600, "endMs": 1200, "text": "Kafka dispatches a worker to analyze source evidence."},
            {"id": "s3", "startMs": 1200, "endMs": 2000, "text": "A blind critic checks the proposed summary."},
        ]}
        attached = call("POST", f"/api/v1/videos/{video_id}/transcript", transcript)
        assert attached["mode"] == "SIMULATED"
        run_id = call("POST", "/api/v1/runs", {"videoId": video_id})["runId"]
        for _ in range(120):
            run = call("GET", f"/api/v1/runs/{run_id}")
            if run["status"] in ("COMPLETED", "FAILED"):
                break
            time.sleep(2)
        assert run["status"] == "COMPLETED", json.dumps(run, ensure_ascii=False)
        assert run["report"]["facts"][0]["evidence"] in run["report"]["summary"]
        assert any(node["node"] == "media_parse" and node["status"] == "COMPLETED" for node in run["nodes"])
        assert any(node["node"] == "audio_extract" and node["status"] == "COMPLETED" for node in run["nodes"])
        benchmark = call("POST", "/api/v1/benchmark")
        assert benchmark["validation"]["cases"] == 12
        assert benchmark["holdout"]["cases"] == 8
        print(json.dumps({"runId": run_id, "status": run["status"], "nodes": len(run["nodes"]),
                          "validation": benchmark["validation"]["keyInformationRecall"]}))


if __name__ == "__main__":
    main()
