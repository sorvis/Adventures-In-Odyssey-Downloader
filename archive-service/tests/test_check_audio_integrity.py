"""Unit tests for scripts/check_audio_integrity.py.

The MP3 header parsing is the load-bearing part: every verdict this
script produces rests on reading the Xing/Info byte count and the frame
offset correctly. Get either wrong and the checker either passes
truncated files silently or accuses healthy ones. So the tests build
synthetic MP3 heads with known declared lengths and drive the real
parser over them, rather than mocking it out.
"""
from __future__ import annotations
import importlib.util
import sys
from pathlib import Path

import pytest


def _load_script():
    here = Path(__file__).resolve().parent.parent
    spec = importlib.util.spec_from_file_location(
        "check_audio_integrity", here / "scripts" / "check_audio_integrity.py"
    )
    mod = importlib.util.module_from_spec(spec)
    sys.modules["check_audio_integrity"] = mod
    spec.loader.exec_module(mod)
    return mod


AI = _load_script()


# ---------------------------------------------------------------------------
# Synthetic MP3 builders
# ---------------------------------------------------------------------------


def _id3v2(payload_len: int) -> bytes:
    """An ID3v2 tag whose declared size uses syncsafe (7-bit) bytes."""
    def syncsafe(n: int) -> bytes:
        return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F,
                      (n >> 7) & 0x7F, n & 0x7F])
    return b"ID3" + b"\x03\x00" + b"\x00" + syncsafe(payload_len) \
        + b"\x00" * payload_len


def _frame_head() -> bytes:
    """MPEG1 Layer III, 44.1 kHz, 128 kbps, stereo."""
    return bytes([0xFF, 0xFB, 0x90, 0x00])


def _mp3_head(frames: int, byte_count: int, *, id3_len: int = 64,
              tag: bytes = b"Info") -> bytes:
    """ID3 tag + first frame carrying a Xing/Info header."""
    head = _id3v2(id3_len) + _frame_head()
    head += b"\x00" * 32                      # side info (MPEG1 stereo)
    head += tag + (0x3).to_bytes(4, "big")    # flags: frames + bytes
    head += frames.to_bytes(4, "big")
    head += byte_count.to_bytes(4, "big")
    return head + b"\x00" * 4096


class FakeClient:
    """Stands in for AudioClient: serves a fixed head and total size."""

    def __init__(self, head: bytes, total: int | None):
        self.head, self.total = head, total
        self.calls = 0

    def head_bytes(self, episode_id: int, n: int):
        self.calls += 1
        return self.head[:n], self.total


# ---------------------------------------------------------------------------
# ID3 / frame / Xing parsing
# ---------------------------------------------------------------------------


def test_id3v2_size_decodes_syncsafe_length():
    """Syncsafe uses 7 bits per byte. Treating it as plain big-endian
    under-reads the tag and shifts every later offset."""
    assert AI.id3v2_size(_id3v2(1000)) == 10 + 1000
    # 200 needs 2 syncsafe digits (0x01,0x48) — a naive parse differs.
    assert AI.id3v2_size(_id3v2(200)) == 210


def test_id3v2_size_zero_when_absent():
    assert AI.id3v2_size(b"\xff\xfb\x90\x00" + b"\x00" * 20) == 0
    assert AI.id3v2_size(b"") == 0


def test_parse_frame_header_reads_mpeg1_layer3():
    fi = AI.parse_frame_header(_frame_head(), 0)
    assert fi is not None
    assert fi.sample_rate == 44100
    assert fi.bitrate_kbps == 128
    assert fi.channels == 2
    assert fi.samples_per_frame == 1152


def test_parse_frame_header_rejects_non_sync():
    assert AI.parse_frame_header(b"\x00\x00\x00\x00", 0) is None
    # Valid sync but reserved MPEG version → not a real frame.
    assert AI.parse_frame_header(bytes([0xFF, 0xEB, 0x90, 0x00]), 0) is None


def test_find_first_frame_skips_the_tag():
    head = _mp3_head(frames=100, byte_count=5000, id3_len=64)
    fi = AI.find_first_frame(head, AI.id3v2_size(head))
    assert fi is not None
    assert fi.frame_offset == 10 + 64


def test_parse_xing_reads_frames_and_bytes():
    head = _mp3_head(frames=58593, byte_count=18_000_000)
    fi = AI.find_first_frame(head, AI.id3v2_size(head))
    x = AI.parse_xing(head, fi)
    assert x.tag == "Info"
    assert x.frames == 58593
    assert x.byte_count == 18_000_000


def test_declared_duration_matches_frame_math():
    """58593 frames x 1152 samples / 44100 Hz ~= 25.5 min, the real
    length of an AIO episode."""
    head = _mp3_head(frames=58593, byte_count=18_000_000)
    fi = AI.find_first_frame(head, AI.id3v2_size(head))
    secs = AI.declared_duration_secs(fi, AI.parse_xing(head, fi))
    assert secs == pytest.approx(1530, abs=5)


# ---------------------------------------------------------------------------
# The verdict that matters: truncation
# ---------------------------------------------------------------------------


def _check(head: bytes, total: int | None, **ep):
    row = {"episode_id": 1, "title": "T", "provider_id": "aio",
           "file_size": total, **ep}
    return AI.check_episode(FakeClient(head, total), row, probe_bytes=16384)


def test_intact_file_reports_no_problems():
    declared = 18_000_000
    head = _mp3_head(frames=58593, byte_count=declared)
    offset = AI.find_first_frame(head, AI.id3v2_size(head)).frame_offset
    f = _check(head, offset + declared)
    assert f.ok, f.problems
    assert f.declared_bytes == declared


def test_truncated_file_is_caught():
    """The whole point: a file that ends early still parses, still
    plays, and only its own header knows it's incomplete."""
    declared = 18_000_000
    head = _mp3_head(frames=58593, byte_count=declared)
    offset = AI.find_first_frame(head, AI.id3v2_size(head)).frame_offset
    f = _check(head, offset + declared // 2)       # stopped halfway
    assert not f.ok
    assert any(p.startswith("TRUNCATED") for p in f.problems)
    assert f.missing_bytes == pytest.approx(declared // 2, abs=10)


def test_small_trailing_slack_is_not_truncation():
    """ID3v1 trailers and encoder padding leave the real file a few
    bytes off. Flagging those would bury real findings in noise."""
    declared = 18_000_000
    head = _mp3_head(frames=58593, byte_count=declared)
    offset = AI.find_first_frame(head, AI.id3v2_size(head)).frame_offset
    f = _check(head, offset + declared - 128)
    assert f.ok, f.problems


def test_size_drift_between_disk_and_db_is_flagged():
    """A file complete at archive time that has lost bytes since."""
    declared = 18_000_000
    head = _mp3_head(frames=58593, byte_count=declared)
    offset = AI.find_first_frame(head, AI.id3v2_size(head)).frame_offset
    row = {"episode_id": 1, "title": "T", "provider_id": "aio",
           "file_size": offset + declared + 5000}
    f = AI.check_episode(FakeClient(head, offset + declared), row,
                         probe_bytes=16384)
    assert any("size drift" in p for p in f.problems)


def test_missing_xing_header_is_noted_not_silently_conflated():
    """A headerless file can't be checked against itself, only against
    its peers. That's a weaker verdict, so it must carry a note —
    "intact" here means something different than it does for a file
    that verified against its own declared length."""
    head = _id3v2(64) + _frame_head() + b"\x00" * 4096
    f = _check(head, 1_000_000)
    assert f.declared_bytes is None          # nothing to self-check against
    assert any("Xing/Info" in n for n in f.notes)
    assert not any("Xing/Info" in p for p in f.problems)


def test_no_mpeg_frame_at_all_is_reported():
    f = _check(_id3v2(64) + b"\x11" * 4096, 5000)
    assert not f.ok
    assert any("no MPEG frame" in p for p in f.problems)


def test_oversized_id3_triggers_a_second_fetch():
    """Cover art can push the first frame past the probe window; the
    checker must re-fetch rather than report a bogus 'no frame'."""
    head = _mp3_head(frames=58593, byte_count=18_000_000, id3_len=40000)
    client = FakeClient(head, 40000 + 18_000_000)
    row = {"episode_id": 1, "title": "T", "provider_id": "aio",
           "file_size": None}
    f = AI.check_episode(client, row, probe_bytes=16384)
    assert client.calls == 2
    assert f.declared_bytes == 18_000_000


# ---------------------------------------------------------------------------
# Duration outliers
# ---------------------------------------------------------------------------


def _finding(eid, secs, provider="aio"):
    return AI.Finding(episode_id=eid, title=f"e{eid}", provider=provider,
                      declared_secs=secs)


def test_duration_outlier_flags_the_short_one():
    """Catches the case the header self-check cannot: a header written
    against already-truncated input, so the file agrees with itself."""
    rows = [_finding(i, 1530.0) for i in range(10)] + [_finding(99, 400.0)]
    AI.flag_duration_outliers(rows, 0.6)
    assert any(p.startswith("SHORT") for p in rows[-1].problems)
    assert all(not r.problems for r in rows[:-1])


def test_duration_outliers_are_scoped_per_provider():
    """A provider whose episodes are genuinely shorter must not be
    flagged wholesale against another provider's median."""
    rows = ([_finding(i, 1530.0) for i in range(6)]
            + [_finding(100 + i, 700.0, provider="ysh") for i in range(6)])
    AI.flag_duration_outliers(rows, 0.6)
    assert all(not r.problems for r in rows)


def test_duration_outliers_need_a_sample_to_judge_against():
    """Too few episodes to establish a median → no verdict, rather than
    a verdict against a median of one."""
    rows = [_finding(1, 1530.0), _finding(2, 100.0)]
    AI.flag_duration_outliers(rows, 0.6)
    assert all(not r.problems for r in rows)


# ---------------------------------------------------------------------------
# CBR fallback for files with no Xing/Info header
# ---------------------------------------------------------------------------


def test_headerless_file_gets_duration_from_bitrate():
    """Seven real files in the archive carry no Xing/Info tag. Rather
    than declaring them unverifiable, derive duration from the constant
    bitrate so they can still be judged against their peers."""
    # 128 kbps = 16000 bytes/sec; 1530 s of audio ~= 24.5 MB.
    audio = 16000 * 1530
    head = _id3v2(64) + _frame_head() + b"\x00" * 4096
    offset = 10 + 64
    f = _check(head, offset + audio)
    assert f.ok, f.problems
    assert f.declared_secs == pytest.approx(1530, abs=1)
    assert any("CBR" in n for n in f.notes)


def test_headerless_truncated_file_is_caught_by_duration():
    """The fallback still catches a short file — just via the peer
    median rather than the file's own declaration."""
    head = _id3v2(64) + _frame_head() + b"\x00" * 4096
    offset = 10 + 64
    short = _check(head, offset + 16000 * 200)          # ~3.3 min
    healthy = [_finding(i, 1530.0) for i in range(8)]
    AI.flag_duration_outliers(healthy + [short], 0.6)
    assert any(p.startswith("SHORT") for p in short.problems)


def test_headerless_file_still_reports_size_drift():
    head = _id3v2(64) + _frame_head() + b"\x00" * 4096
    offset = 10 + 64
    row = {"episode_id": 1, "title": "T", "provider_id": "aio",
           "file_size": offset + 16000 * 1530 + 9999}
    f = AI.check_episode(FakeClient(head, offset + 16000 * 1530), row,
                         probe_bytes=16384)
    assert any("size drift" in p for p in f.problems)
