"""Test for the `python -m app.scrape_ysh_cli <out_path>` wrapper.

Mocks `refresh_catalog` (the heavy lifting lives + is tested in
test_scrape_ysh.py) and asserts the CLI:
  - exits 0 on success with a valid arg
  - exits 2 when invoked without an out_path arg
  - prints a one-line summary to stdout
"""
from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import patch

import pytest


def test_cli_writes_summary_and_exits_zero(tmp_path: Path, capsys, monkeypatch):
    out_path = tmp_path / "ysh_catalog.json"
    fake_catalog = {
        "scrapedAtMs": 1,
        "albumCount": 2,
        "albums": [
            {"tracks": [{}, {}]},
            {"tracks": [{}, {}, {}]},
        ],
    }
    monkeypatch.setattr(sys, "argv", ["scrape_ysh_cli", str(out_path)])
    with patch("app.scrape_ysh_cli.refresh_catalog", return_value=fake_catalog) as m:
        from app.scrape_ysh_cli import main
        rc = main()
    assert rc == 0
    m.assert_called_once_with(out_path)
    out = capsys.readouterr().out
    assert "2 albums, 5 tracks" in out
    assert str(out_path) in out


def test_cli_exits_two_when_called_without_args(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["scrape_ysh_cli"])
    from app.scrape_ysh_cli import main
    assert main() == 2
    assert "usage:" in capsys.readouterr().err
