#!/usr/bin/env python3
"""Dump the full request + cookies the SPA uses, plus the response body."""
from __future__ import annotations
import json
import sys

from playwright.sync_api import sync_playwright


def main() -> int:
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(
            user_agent="Mozilla/5.0 (X11; Linux) Chrome/120 Safari/537.36",
            viewport={"width": 1280, "height": 1800},
        )
        page = ctx.new_page()
        page.set_default_timeout(45_000)

        captured_resp_body = {}

        def on_response(resp):
            url = resp.url
            if "contentgrouping/search" in url:
                try:
                    captured_resp_body[url] = resp.text()
                except Exception as e:
                    captured_resp_body[url] = f"<read error: {e}>"

        captured_req = []

        def on_request(req):
            if "contentgrouping/search" in req.url:
                captured_req.append({
                    "url": req.url,
                    "method": req.method,
                    "headers": dict(req.headers),
                    "body": req.post_data,
                })

        page.on("response", on_response)
        page.on("request", on_request)

        page.goto("https://app.adventuresinodyssey.com/albums", wait_until="networkidle", timeout=60_000)
        page.wait_for_timeout(8_000)

        # Cookies set on fotf.my.site.com
        cookies = ctx.cookies()
        site_cookies = [c for c in cookies if "fotf.my.site.com" in c.get("domain", "")]
        print(f"=== Cookies for fotf.my.site.com: {len(site_cookies)} ===")
        for c in site_cookies:
            print(f"  {c['name']}={(c.get('value') or '')[:60]} (domain={c['domain']})")

        for r in captured_req[:1]:
            print(f"\n=== Request ===")
            print(f"{r['method']} {r['url']}")
            for k, v in r['headers'].items():
                print(f"  {k}: {v[:120]}")
            print(f"  BODY: {r['body']}")

        for url, body in captured_resp_body.items():
            print(f"\n=== Response body for {url} ===")
            try:
                d = json.loads(body)
                print(f"  type: {type(d).__name__}")
                if isinstance(d, dict):
                    md = d.get("metadata", {})
                    cgs = d.get("contentGroupings", [])
                    print(f"  metadata: {md}")
                    print(f"  contentGroupings: {len(cgs)} items")
                    if cgs:
                        print(f"  first item keys: {list(cgs[0].keys())}")
                        print(json.dumps(cgs[0], indent=2)[:2000])
            except Exception as e:
                print(f"  parse fail: {e}")
                print(body[:500])

        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
