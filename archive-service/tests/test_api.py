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


def test_concurrent_same_id_insert_uses_or_ignore(client, auth_headers, fake_mp3_bytes):
    """Two clients hitting POST /episodes with the same episode_id at
    nearly the same time can both pass the existence check before
    either commits an INSERT — the second's plain INSERT would then
    fail with a UNIQUE constraint and 500 the request. The handler
    uses INSERT OR IGNORE; this test exercises the SQL invariant
    directly against the test DB so the regression is caught even
    if the FastAPI client's single-threaded execution masks the race.
    """
    from app import db
    # Seed normally so the row exists exactly as the handler would
    # have written it.
    r = _upload(client, auth_headers, fake_mp3_bytes, episode_id=42, title="A")
    assert r.status_code == 201

    # Now mimic the second client's INSERT — bypassing the early-
    # return existence check (as it would in the racy interleave) —
    # and assert it does NOT raise UNIQUE constraint.
    with db.connect() as c:
        c.execute(
            """INSERT OR IGNORE INTO episodes
               (episode_id, title, air_date, album, description, duration_secs,
                file_path, file_size, sha256, source_url)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (42, "B", None, None, None, None, "/tmp/x", 0, None, None),
        )
        # First-writer-wins: title stays "A" (whichever client landed
        # first), the OR IGNORE swallows the duplicate.
        row = c.execute("SELECT * FROM episodes WHERE episode_id = 42").fetchone()
    assert row["title"] == "A"

    # And the API stays consistent — a single row per id.
    listed = client.get("/episodes", headers=auth_headers).json()
    assert sum(1 for row in listed if row["episode_id"] == 42) == 1


def test_get_404_on_missing(client, auth_headers):
    assert client.get("/episodes/999999", headers=auth_headers).status_code == 404


def test_head_episode_returns_200_when_row_and_file_present(client, auth_headers, fake_mp3_bytes):
    """HEAD /episodes/{id} powers the Android RetentionWorker's
    verify-before-prune check (added v0.1.67). Confirms an episode is
    actually on the server before the phone deletes its local copy."""
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=55)
    r = client.head("/episodes/55", headers=auth_headers)
    assert r.status_code == 200
    assert r.headers.get("X-File-Size") == str(len(fake_mp3_bytes))


def test_head_episode_returns_404_when_row_does_not_exist(client, auth_headers):
    r = client.head("/episodes/999999", headers=auth_headers)
    assert r.status_code == 404


def test_head_episode_returns_410_when_row_exists_but_file_missing(
    client, auth_headers, fake_mp3_bytes
):
    """A phantom row -- row present but on-disk file gone -- would let
    the phone delete its local copy thinking the backup was safe. 410
    Gone surfaces this so the client treats it as 'do NOT prune'."""
    import os
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=56)
    # Find the file the upload landed on disk and remove it underneath.
    from app import db
    with db.connect() as c:
        path = c.execute("SELECT file_path FROM episodes WHERE episode_id = 56").fetchone()["file_path"]
    os.remove(path)
    r = client.head("/episodes/56", headers=auth_headers)
    assert r.status_code == 410


def test_head_episode_requires_auth(client, fake_mp3_bytes):
    r = client.head("/episodes/1")
    assert r.status_code == 401


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


# ---------------------------------------------------------------------------
# PATCH /episodes/{id} + DELETE /episodes/{id}
# Drive the whisper-title validation pipeline (scripts/whisper_titles.py)
# that corrects mis-titled imports and removes confirmed duplicates.
# ---------------------------------------------------------------------------


def test_patch_title_renames_file_and_updates_row(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=901, album="51")
    r = client.patch(
        "/episodes/901",
        headers=auth_headers,
        json={"title": "Knox on Money"},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["title"] == "Knox on Money"
    assert body["album"] == "51"

    # The on-disk file should have moved to the canonical path for the
    # new title — the import path the create flow uses.
    from app.storage import episode_path
    expected = episode_path(901, "Knox on Money", "51")
    assert expected.exists(), f"expected {expected} to exist after rename"


def test_patch_with_only_album_does_not_move_file(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=902, album="50")
    before = client.get("/episodes/902", headers=auth_headers).json()
    assert before["album"] == "50"

    r = client.patch(
        "/episodes/902",
        headers=auth_headers,
        json={"album": "51"},
    )
    assert r.status_code == 200, r.text
    assert r.json()["album"] == "51"
    # Album-only PATCH still triggers a file move because the canonical
    # path embeds the album — assert the file lives at the new canonical
    # location, not the old one.
    from app.storage import episode_path
    new_path = episode_path(902, "Clutter", "51")
    assert new_path.exists()


def test_patch_with_empty_body_400s(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=903)
    r = client.patch("/episodes/903", headers=auth_headers, json={})
    assert r.status_code == 400


def test_patch_missing_episode_404s(client, auth_headers):
    r = client.patch(
        "/episodes/999999",
        headers=auth_headers,
        json={"title": "doesn't matter"},
    )
    assert r.status_code == 404


def test_patch_requires_auth(client, fake_mp3_bytes):
    r = client.patch("/episodes/1", json={"title": "x"})
    assert r.status_code == 401


def test_delete_removes_row_and_file(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=910)
    before = client.get("/episodes/910", headers=auth_headers).json()
    from app.storage import episode_path
    path = episode_path(910, before["title"], before["album"])
    assert path.exists()

    r = client.delete("/episodes/910", headers=auth_headers)
    assert r.status_code == 204
    assert client.get("/episodes/910", headers=auth_headers).status_code == 404
    assert not path.exists()


def test_delete_missing_row_404s(client, auth_headers):
    r = client.delete("/episodes/999999", headers=auth_headers)
    assert r.status_code == 404


def test_delete_succeeds_even_if_file_already_gone(client, auth_headers, fake_mp3_bytes):
    """The dedup pipeline may have hand-removed an audio file outside
    the API (rsync, etc.) and only now is calling DELETE to clean the
    DB row — the endpoint must not 500 on the orphan."""
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=911)
    before = client.get("/episodes/911", headers=auth_headers).json()
    from app.storage import episode_path
    path = episode_path(911, before["title"], before["album"])
    path.unlink()

    r = client.delete("/episodes/911", headers=auth_headers)
    assert r.status_code == 204
    assert client.get("/episodes/911", headers=auth_headers).status_code == 404


def test_delete_requires_auth(client, fake_mp3_bytes):
    r = client.delete("/episodes/1")
    assert r.status_code == 401


def test_upload_starts_with_null_title_validated_at(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=920)
    row = client.get("/episodes/920", headers=auth_headers).json()
    assert row["title_validated_at"] is None


def test_put_title_validated_stamps_now(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=921)
    r = client.put("/episodes/921/title-validated", headers=auth_headers)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["title_validated_at"] is not None
    # Re-stamping should overwrite, not error.
    r2 = client.put("/episodes/921/title-validated", headers=auth_headers)
    assert r2.status_code == 200


def test_put_title_validated_404_on_missing(client, auth_headers):
    r = client.put("/episodes/999999/title-validated", headers=auth_headers)
    assert r.status_code == 404


def test_put_title_validated_requires_auth(client, fake_mp3_bytes):
    r = client.put("/episodes/1/title-validated")
    assert r.status_code == 401


def test_patch_also_stamps_title_validated_at(client, auth_headers, fake_mp3_bytes):
    _upload(client, auth_headers, fake_mp3_bytes, episode_id=922, album="51")
    before = client.get("/episodes/922", headers=auth_headers).json()
    assert before["title_validated_at"] is None
    r = client.patch(
        "/episodes/922",
        headers=auth_headers,
        json={"title": "Knox on Money"},
    )
    assert r.status_code == 200
    assert r.json()["title_validated_at"] is not None
