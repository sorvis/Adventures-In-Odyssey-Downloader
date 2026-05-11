"""CLI entry point for `scripts/refresh-ysh-catalog.sh`.

Walks yourstoryhour.org/crud/product/skus and writes the resulting
catalog JSON to the path given as argv[1]. Pure CLI shell around
`scrape_ysh.refresh_catalog`; the logic lives in the importable
module so it's testable.
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

from app.scrape_ysh import refresh_catalog


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    if len(sys.argv) != 2:
        print("usage: python -m app.scrape_ysh_cli <out_path>", file=sys.stderr)
        return 2
    out_path = Path(sys.argv[1])
    catalog = refresh_catalog(out_path)
    print(
        f"YSH catalog: {catalog['albumCount']} albums, "
        f"{sum(len(a['tracks']) for a in catalog['albums'])} tracks → {out_path}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
