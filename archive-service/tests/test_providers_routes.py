"""Tests for the /providers/{provider}/... resource paths added in
step 11b. Mostly mirrors test_api.py but exercises the provider-scoped
endpoints + the YSH-shaped non-numeric external_id case.

Backward-compat invariant: legacy /episodes routes still work for
AIO clients (covered in test_api.py). These tests assert that the
new routes don't accidentally interfere with that surface and that
inserts via the new POST appear in legacy list responses too (since
the underlying table is shared, the legacy GET /episodes returns
all rows including any from new POSTs)."""
from __future__ import annotations

import io


def _upload_provider(client, headers, fake_mp3_bytes, provider, external_id, title, **extra):
    files = {"audio": (f"{external_id}.mp3", io.BytesIO(fake_mp3_bytes), "audio/mpeg")}
    data = {"external_id": external_id, "title": title}
    data.update({k: str(v) for k, v in extra.items() if v is not None})
    return client.post(f"/providers/{provider}/episodes", headers=headers, files=files, data=data)


def test_provider_routes_require_bearer_auth(client):
    r = client.get("/providers/aio/episodes")
    assert r.status_code == 401


def test_aio_upload_via_provider_route(client, auth_headers, fake_mp3_bytes):
    """AIO clients can use either POST /episodes (legacy) or
    POST /providers/aio/episodes — both write the same row shape."""
    r = _upload_provider(
        client, auth_headers, fake_mp3_bytes,
        provider="aio", external_id="1278294", title="Clutter", album="51",
    )
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["provider_id"] == "aio"
    assert body["external_id"] == "1278294"
    assert body["episode_id"] == 1278294
    assert body["title"] == "Clutter"
    assert body["album"] == "51"


def test_ysh_upload_with_non_numeric_external_id(client, auth_headers, fake_mp3_bytes):
    """YSH externalIds like "ysh-sku-1958" must round-trip — they're
    not parseable as int, so episode_id ends up null."""
    r = _upload_provider(
        client, auth_headers, fake_mp3_bytes,
        provider="ysh", external_id="ysh-sku-1958", title="Madeleine's Courage",
        album="Exciting Events - Volume 11",
    )
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["provider_id"] == "ysh"
    assert body["external_id"] == "ysh-sku-1958"
    # YSH non-numeric externalIds → SQLite auto-assigned rowid for
    # episode_id (we don't constrain it here; just verify it's set,
    # since INTEGER PRIMARY KEY is NOT NULL).
    assert body["episode_id"] is not None
    assert body["album"] == "Exciting Events - Volume 11"


def test_upload_idempotency_by_composite_key(client, auth_headers, fake_mp3_bytes):
    r1 = _upload_provider(
        client, auth_headers, fake_mp3_bytes,
        provider="ysh", external_id="ysh-sku-100", title="Madeleine",
    )
    r2 = _upload_provider(
        client, auth_headers, fake_mp3_bytes,
        provider="ysh", external_id="ysh-sku-100", title="Madeleine",
    )
    assert r1.status_code == 201
    assert r2.status_code in (200, 201)
    assert r1.json()["external_id"] == r2.json()["external_id"]


def test_provider_list_filters_by_provider(client, auth_headers, fake_mp3_bytes):
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="aio", external_id="1278294", title="Clutter")
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="ysh", external_id="ysh-sku-1958", title="Madeleine")

    aio_list = client.get("/providers/aio/episodes", headers=auth_headers).json()
    ysh_list = client.get("/providers/ysh/episodes", headers=auth_headers).json()
    assert {e["external_id"] for e in aio_list} == {"1278294"}
    assert {e["external_id"] for e in ysh_list} == {"ysh-sku-1958"}


def test_provider_get_one(client, auth_headers, fake_mp3_bytes):
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="ysh", external_id="ysh-sku-X", title="X")
    r = client.get("/providers/ysh/episodes/ysh-sku-X", headers=auth_headers)
    assert r.status_code == 200
    assert r.json()["title"] == "X"
    # Cross-provider miss — same external_id, different provider.
    r2 = client.get("/providers/aio/episodes/ysh-sku-X", headers=auth_headers)
    assert r2.status_code == 404


def test_provider_audio_streams(client, auth_headers, fake_mp3_bytes):
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="ysh", external_id="ysh-sku-7", title="Seven")
    r = client.get("/providers/ysh/episodes/ysh-sku-7/audio", headers=auth_headers)
    assert r.status_code == 200
    assert r.content == fake_mp3_bytes


def test_provider_audio_range_request(client, auth_headers, fake_mp3_bytes):
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="ysh", external_id="ysh-sku-r", title="Range")
    r = client.get(
        "/providers/ysh/episodes/ysh-sku-r/audio",
        headers={**auth_headers, "Range": "bytes=0-9"},
    )
    assert r.status_code == 206
    assert r.content == fake_mp3_bytes[:10]


def test_provider_albums_list_scoped(client, auth_headers, fake_mp3_bytes):
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="aio", external_id="100", title="A", album="AlbumA")
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="ysh", external_id="ysh-sku-200", title="B",
                     album="AlbumB")

    aio_albums = client.get("/providers/aio/albums", headers=auth_headers).json()
    ysh_albums = client.get("/providers/ysh/albums", headers=auth_headers).json()
    assert {a["name"] for a in aio_albums} == {"AlbumA"}
    assert {a["name"] for a in ysh_albums} == {"AlbumB"}


def test_provider_album_episodes(client, auth_headers, fake_mp3_bytes):
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="ysh", external_id="ysh-sku-A",
                     title="One", album="EE-11")
    _upload_provider(client, auth_headers, fake_mp3_bytes,
                     provider="ysh", external_id="ysh-sku-B",
                     title="Two", album="EE-11")
    r = client.get("/providers/ysh/albums/EE-11/episodes", headers=auth_headers)
    assert r.status_code == 200
    titles = {e["title"] for e in r.json()}
    assert titles == {"One", "Two"}


def test_head_episode_returns_200_when_ysh_row_and_file_present(client, auth_headers, fake_mp3_bytes):
    """HEAD /providers/ysh/episodes/{eid} — v0.1.72 verify-before-prune
    for YSH. Existing legacy HEAD only handles AIO (integer episode_id);
    the v2 path needs its own HEAD for YSH's sku-string ids."""
    _upload_provider(
        client, auth_headers, fake_mp3_bytes,
        provider="ysh", external_id="ysh-sku-1958", title="Madeleine's Courage",
    )
    r = client.head(
        "/providers/ysh/episodes/ysh-sku-1958",
        headers=auth_headers,
    )
    assert r.status_code == 200
    assert r.headers.get("X-File-Size") == str(len(fake_mp3_bytes))


def test_head_episode_returns_404_for_missing_provider_row(client, auth_headers):
    r = client.head("/providers/ysh/episodes/ysh-sku-nonexistent", headers=auth_headers)
    assert r.status_code == 404


def test_head_episode_returns_410_when_row_present_but_file_missing(
    client, auth_headers, fake_mp3_bytes
):
    """Phantom row safety: row exists in index, on-disk file gone.
    Client must NOT prune its local copy in that case."""
    import os
    _upload_provider(
        client, auth_headers, fake_mp3_bytes,
        provider="ysh", external_id="ysh-sku-555", title="Phantom",
    )
    from app import db
    with db.connect() as c:
        path = c.execute(
            "SELECT file_path FROM episodes WHERE provider_id='ysh' AND external_id='ysh-sku-555'"
        ).fetchone()["file_path"]
    os.remove(path)
    r = client.head("/providers/ysh/episodes/ysh-sku-555", headers=auth_headers)
    assert r.status_code == 410


def test_head_episode_requires_auth(client):
    r = client.head("/providers/ysh/episodes/ysh-sku-1958")
    assert r.status_code == 401


def test_head_episode_works_for_aio_via_v2_path_too(client, auth_headers, fake_mp3_bytes):
    """Symmetry: the v2 HEAD endpoint accepts provider='aio' too, so
    the Android client can use a single code path for both shows
    instead of branching legacy-vs-v2 by provider."""
    _upload_provider(
        client, auth_headers, fake_mp3_bytes,
        provider="aio", external_id="1278294", title="Clutter", album="51",
    )
    r = client.head("/providers/aio/episodes/1278294", headers=auth_headers)
    assert r.status_code == 200


def test_legacy_route_still_works_after_new_route_added(client, auth_headers, fake_mp3_bytes):
    """Critical back-compat: Android v0.1.37 hits POST /episodes
    (no provider field). The new providers router must not shadow
    or break it."""
    files = {"audio": ("1.mp3", io.BytesIO(fake_mp3_bytes), "audio/mpeg")}
    data = {"episode_id": "1", "title": "Legacy"}
    r = client.post("/episodes", headers=auth_headers, files=files, data=data)
    assert r.status_code == 201
    body = r.json()
    # Legacy shape — episode_id still present. provider_id is now
    # surfaced on EpisodeOut too (added so scripts/whisper_titles.py
    # can dispatch tail-vs-head extraction per provider); the legacy
    # POST handler defaults it to "aio".
    assert body["episode_id"] == 1
    assert body["provider_id"] == "aio"

    # And the new provider route can see the same row.
    r2 = client.get("/providers/aio/episodes/1", headers=auth_headers)
    assert r2.status_code == 200
    assert r2.json()["provider_id"] == "aio"
