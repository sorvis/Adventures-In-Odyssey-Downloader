# YSH probe artifacts (2026-05-10)

Captured during the design exploration that produced
[`../ysh-design.md`](../ysh-design.md). Snapshots of the upstream data
shapes the design depends on.

| File | Source | Purpose |
|------|--------|---------|
| `yourstoryhour-catalog.json` | `GET /crud/product/skus?page=1..5` aggregated by id | Full 88-album paid catalog with all SKUs (digital_album bundles, physical CDs, and digital_track per-story entries) |
| `yourstoryhour-tracks-flat.json` | derived from `yourstoryhour-catalog.json` | All 1055 digital_track entries flattened to `{sku_id, title, album_id, album_title, album_slug, album_image, order_index}` for cross-match testing |
| `yourstoryhour-free-streaming.json` | `GET /crud/free-streaming` | Currently-rotating free-sample pool — 6 albums × 1 free track each |
| `oneplace-ysh-archive.json` | Walked `GET /api/related-episodes?eid=…` backward from latest YSH episodeId | Full set of YSH episodes accessible via oneplace.com — only ~9 deep |

Title-normalized cross-match (lowercase, punctuation stripped) of
`oneplace-ysh-archive.json` against `yourstoryhour-tracks-flat.json`
hits 9/9 (100%). See design doc for analysis.
