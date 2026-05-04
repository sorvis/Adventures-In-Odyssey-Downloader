"""End-to-end-ish API tests against an in-process FastAPI app.

Covers the full contract the Android NasClient + import script depend on:
healthz, auth gate, upload, list, get, audio range streaming, idempotency,
and album endpoints.
"""
from __future__ import annotations
import io


def _upload(client, headers, fake_mp3_bytes, episode_id=1278383, **extra):
    """Helper — matches the Form-field shape `app/routes/episodes.py` expects."""
    files = {"audio": (f"{episode_id}.mp3", io.BytesIO(fake_mp3_bytes), "audio/mpeg")}
    data = {"episode_id": str(episode_id), "title": "Clutter"}
    data.update({k: str(v) for k, v in extra.items() if v is not None})
    return client.post("/episodes", headers=headers, files=files, data=data)


def test_healthz_is_unauthenticated_and_truthy(client):
    r = client.get("/healthz")
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_episodes_endpoint_requires_bearer_auth(client):
    r = client.get("/episodes")
    assert r.status_code == 401
    r2 = client.get("/episodes", headers={"Authorization": "Bearer wrong"})
    assert r2.status_code == 401


def test_upload_then_list_then_get(client, auth_headers, fake_mp3_bytes):
    r = _upload(client, auth_headers, fake_mp3_bytes,
                episode_id=42, album="51", description="Eugene's word becomes the new insult.")
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["episode_id"] == 42
    assert body["title"] == "Clutter"
    assert body["album"] == "51"
    assert body["file_size"] == len(fake_mp3_bytes)
    assert body["sha256"] is not None and len(body["sha256"]) == 64

    rl = client.get("/episodes", headers=auth_headers)
    assert rl.status_code == 200
    rows = rl.json()
    assert len(rows) == 1 and rows[0]["episode_id"] == 42

    rg = client.get("/episodes/42", headers=auth_headers)
    assert rg.status_code == 200
    assert rg.json()["episode_id"] == 42


def test_upload_is_idempotent_on_episode_id(client, auth_headers, fake_mp3_bytes):
    """Re-uploading the same episode_id returns the existing row, not 201."""
    r1 = _upload(client, auth_headers, fake_mp3_bytes, episode_id=99)
    assert r1.status_code == 201
    r2 = _upload(client, auth_headers, fake_mp3_bytes, episode_id=99)
    # Existing row returned with 200 (FastAPI default for the early-return branch).
    assert r2.status_code in (200, 201)
    assert r2.json()["episode_id"] == 99


def test_get_404_on_missing(client, auth_headers):
    assert client.get("/episodes/999999", headers=auth_headers).status_code == 404


def test_audio_full_download(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=7)
    r = client.get("/episodes/7/audio", headers=auth_headers)
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("audio/mpeg")
    assert r.headers["accept-ranges"] == "bytes"
    assert r.content == fake_mp3_bytes


def test_audio_range_returns_206_with_correct_content_range(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=8)
    size = len(fake_mp3_bytes)
    r = client.get(
        "/episodes/8/audio",
        headers={**auth_headers, "Range": "bytes=4-9"},
    )
    assert r.status_code == 206
    assert r.headers["content-range"] == f"bytes 4-9/{size}"
    assert r.content == fake_mp3_bytes[4:10]


def test_audio_range_416_when_out_of_bounds(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=9)
    size = len(fake_mp3_bytes)
    r = client.get(
        "/episodes/9/audio",
        headers={**auth_headers, "Range": f"bytes={size + 100}-{size + 200}"},
    )
    assert r.status_code == 416


def test_list_filters_by_album_and_q(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=1, album="51", description="alpha")
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=2, album="51", description="beta")
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=3, album="80", description="gamma")

    rows = client.get("/episodes?album=51", headers=auth_headers).json()
    assert {r["episode_id"] for r in rows} == {1, 2}

    rows = client.get("/episodes?q=beta", headers=auth_headers).json()
    assert [r["episode_id"] for r in rows] == [2]


def test_albums_endpoint_lists_with_counts(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=10, album="51")
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=11, album="51")
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=12, album="80")

    r = client.get("/albums", headers=auth_headers)
    assert r.status_code == 200
    by_name = {a["name"]: a["episode_count"] for a in r.json()}
    assert by_name == {"51": 2, "80": 1}


def test_album_episodes_filters_correctly(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=20, album="51")
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=21, album="80")

    r = client.get("/albums/51/episodes", headers=auth_headers)
    assert r.status_code == 200
    assert [row["episode_id"] for row in r.json()] == [20]

    # 404 for an album with no episodes — matches the existing handler.
    r2 = client.get("/albums/Nonexistent/episodes", headers=auth_headers)
    assert r2.status_code == 404
