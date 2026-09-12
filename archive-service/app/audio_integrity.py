"""
MP3 completeness checking, shared by the upload path and the sweep
script (`scripts/check_audio_integrity.py`).

The failure being guarded against: a download that stopped partway
through produces a file that still parses, still plays, and simply ends
in the middle of the story. Episode 313 sat in the archive that way for
months — 3.1 minutes of a 25.5 minute episode — and nothing noticed,
because every layer only ever asked "did the bytes transfer without an
error", never "are all of them here".

The check is one the file makes against itself. An encoder writes a
Xing (VBR) or Info (CBR) header into the first frame declaring how many
frames and how many BYTES the finished file contains. Comparing that
declaration against the real size is decisive and needs only the first
few KB, so it's cheap enough to run inline on every upload.

Files with no Xing/Info header can't be self-checked. They are reported
as UNKNOWN rather than BAD: refusing them would reject the handful of
legitimately headerless files already in the archive.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

# Layer III bitrate tables, indexed by the 4-bit field in the frame
# header. Index 0 ("free") and 15 ("bad") are not real rates.
_BITRATES_V1_L3 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160,
                   192, 224, 256, 320, 0]
_BITRATES_V2_L3 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96,
                   112, 128, 144, 160, 0]
_SAMPLE_RATES = {
    3: [44100, 48000, 32000, 0],   # MPEG 1
    2: [22050, 24000, 16000, 0],   # MPEG 2
    0: [11025, 12000, 8000, 0],    # MPEG 2.5
}

# How many bytes short a file may be before it counts as truncated.
# An ID3v1 trailer is 128 bytes and encoders leave a little padding, so
# a small shortfall is normal; anything past this is missing audio.
SLACK_BYTES = 2048

# Enough of the file to hold an ID3v2 tag plus the first frame. Files
# with cover art need a second, larger read — see `inspect_file`.
PROBE_BYTES = 16384


def id3v2_size(head: bytes) -> int:
    """Bytes occupied by a leading ID3v2 tag (0 when absent).

    The length is 'syncsafe' — 7 bits per byte, so the tag can never
    contain a false frame sync. Parsing it as plain big-endian
    under-reads the tag and shifts every later offset.
    """
    if len(head) < 10 or head[:3] != b"ID3":
        return 0
    flags = head[5]
    size = ((head[6] & 0x7F) << 21 | (head[7] & 0x7F) << 14
            | (head[8] & 0x7F) << 7 | (head[9] & 0x7F))
    total = 10 + size
    if flags & 0x10:      # footer present
        total += 10
    return total


@dataclass
class FrameInfo:
    version: int          # 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
    sample_rate: int
    bitrate_kbps: int
    channels: int
    samples_per_frame: int
    frame_offset: int


def parse_frame_header(buf: bytes, offset: int) -> FrameInfo | None:
    """Parse an MPEG audio frame header, or None if `offset` isn't one."""
    if offset + 4 > len(buf):
        return None
    b0, b1, b2, b3 = buf[offset:offset + 4]
    if b0 != 0xFF or (b1 & 0xE0) != 0xE0:
        return None
    version = (b1 >> 3) & 0x03
    layer = (b1 >> 1) & 0x03          # 1 = Layer III
    if layer != 1 or version == 1:    # version 1 is reserved
        return None
    bitrate_idx = (b2 >> 4) & 0x0F
    sr_idx = (b2 >> 2) & 0x03
    if bitrate_idx in (0, 15) or sr_idx == 3:
        return None
    sample_rate = _SAMPLE_RATES[version][sr_idx]
    if not sample_rate:
        return None
    table = _BITRATES_V1_L3 if version == 3 else _BITRATES_V2_L3
    return FrameInfo(
        version=version,
        sample_rate=sample_rate,
        bitrate_kbps=table[bitrate_idx],
        channels=1 if ((b3 >> 6) & 0x03) == 3 else 2,
        samples_per_frame=1152 if version == 3 else 576,
        frame_offset=offset,
    )


def find_first_frame(buf: bytes, start: int) -> FrameInfo | None:
    """First valid Layer III frame at or after `start`.

    Scans rather than trusting `start`, because a tag's declared size
    can be off and some files pad between the tag and the audio.
    """
    for off in range(start, max(start, min(len(buf) - 4, start + 65536))):
        fi = parse_frame_header(buf, off)
        if fi is not None:
            return fi
    return None


@dataclass
class XingHeader:
    frames: int | None
    byte_count: int | None
    tag: str


def parse_xing(buf: bytes, frame: FrameInfo) -> XingHeader | None:
    """Read the Xing/Info tag inside the first frame.

    Its distance from the frame header depends on MPEG version and
    channel mode, because the side-information block between them
    varies in length — hence the table rather than a constant.
    """
    if frame.version == 3:                      # MPEG 1
        side_info = 17 if frame.channels == 1 else 32
    else:                                       # MPEG 2 / 2.5
        side_info = 9 if frame.channels == 1 else 17
    pos = frame.frame_offset + 4 + side_info
    if pos + 8 > len(buf):
        return None
    tag = buf[pos:pos + 4]
    if tag not in (b"Xing", b"Info"):
        return None
    flags = int.from_bytes(buf[pos + 4:pos + 8], "big")
    cur = pos + 8
    frames = byte_count = None
    if flags & 0x1:
        if cur + 4 > len(buf):
            return None
        frames = int.from_bytes(buf[cur:cur + 4], "big")
        cur += 4
    if flags & 0x2:
        if cur + 4 > len(buf):
            return None
        byte_count = int.from_bytes(buf[cur:cur + 4], "big")
        cur += 4
    return XingHeader(frames, byte_count, tag.decode())


def declared_duration_secs(frame: FrameInfo, xing: XingHeader) -> float | None:
    if not xing.frames:
        return None
    return xing.frames * frame.samples_per_frame / frame.sample_rate


# ---------------------------------------------------------------------------
# Verdict
# ---------------------------------------------------------------------------

OK = "ok"              # verified complete against its own header
UNKNOWN = "unknown"    # nothing to verify against; not proof of a problem
BAD = "truncated"      # definitely shorter than it declares


@dataclass
class Integrity:
    status: str                        # OK | UNKNOWN | BAD
    reason: str = ""
    declared_bytes: int | None = None
    actual_audio_bytes: int | None = None
    missing_bytes: int | None = None
    duration_secs: float | None = None
    bitrate_kbps: int | None = None

    @property
    def is_truncated(self) -> bool:
        return self.status == BAD


def inspect_bytes(head: bytes, total_size: int) -> Integrity:
    """Judge a file from its opening bytes plus its true total size.

    `head` must reach past the ID3 tag into the first audio frame;
    `inspect_file` handles re-reading when a large tag pushes it out.
    """
    tag_len = id3v2_size(head)
    frame = find_first_frame(head, tag_len)
    if frame is None:
        return Integrity(UNKNOWN, "no MPEG frame found in header window")

    xing = parse_xing(head, frame)
    audio_bytes = total_size - frame.frame_offset
    if xing is None or not xing.byte_count:
        # No self-declared length. Derive duration from the bitrate so
        # callers still get something usable, but don't claim a verdict
        # the file can't support.
        secs = (audio_bytes / (frame.bitrate_kbps * 1000 / 8)
                if frame.bitrate_kbps else None)
        return Integrity(
            UNKNOWN, "no Xing/Info header to verify against",
            actual_audio_bytes=audio_bytes, duration_secs=secs,
            bitrate_kbps=frame.bitrate_kbps,
        )

    missing = xing.byte_count - audio_bytes
    secs = declared_duration_secs(frame, xing)
    if missing > SLACK_BYTES:
        pct = 100.0 * missing / xing.byte_count
        return Integrity(
            BAD,
            f"{missing:,} bytes short of the {xing.byte_count:,} its header "
            f"declares ({pct:.1f}% missing)",
            declared_bytes=xing.byte_count, actual_audio_bytes=audio_bytes,
            missing_bytes=missing, duration_secs=secs,
            bitrate_kbps=frame.bitrate_kbps,
        )
    return Integrity(
        OK, "", declared_bytes=xing.byte_count,
        actual_audio_bytes=audio_bytes, duration_secs=secs,
        bitrate_kbps=frame.bitrate_kbps,
    )


def inspect_file(path: str | Path) -> Integrity:
    """Judge a file on disk. Reads only its opening bytes."""
    p = Path(path)
    try:
        total = p.stat().st_size
        with p.open("rb") as fh:
            head = fh.read(PROBE_BYTES)
            tag_len = id3v2_size(head)
            if tag_len >= len(head):
                # Cover art pushed the first frame past the probe
                # window; re-read a window that reaches the audio.
                fh.seek(0)
                head = fh.read(tag_len + 4096)
    except OSError as exc:
        return Integrity(UNKNOWN, f"unreadable: {exc}")
    if total == 0:
        return Integrity(BAD, "file is empty")
    return inspect_bytes(head, total)
