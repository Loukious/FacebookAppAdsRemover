#!/usr/bin/env python3
"""Refresh the About page's contributor snapshot.

Writes app/contributors/contributors.json and one 96 px WebP avatar per
person into app/contributors/avatars/. The app ships these as assets, so it
needs no network access at runtime.

    python scripts/update_contributors.py          # uses GITHUB_TOKEN if set

Requires Pillow (pip install pillow).
"""
import io
import json
import os
import time
import urllib.request
from pathlib import Path

from PIL import Image

REPO = os.environ.get("FBAR_REPO", "Loukious/FacebookAppAdsRemover")
OUT = Path(__file__).resolve().parent.parent / "app" / "contributors"
AVATARS = OUT / "avatars"
SIZE = 96
# GitHub credits the Co-Authored-By trailer of AI-assisted commits to this
# account, which would duplicate the human author's line counts.
SKIP = {"claude"}


def fetch(url, accept="application/vnd.github+json"):
    request = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "fbar-contributors"})
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        request.add_header("Authorization", f"Bearer {token}")
    return urllib.request.urlopen(request, timeout=30)


def line_stats():
    """stats/contributors answers 202 while GitHub computes it; poll a bit."""
    url = f"https://api.github.com/repos/{REPO}/stats/contributors"
    for wait in (0, 2, 4, 8, 16):
        time.sleep(wait)
        with fetch(url) as response:
            if response.status == 200:
                return json.load(response)
    raise SystemExit("GitHub is still computing contributor stats; try again later")


def main():
    with fetch(f"https://api.github.com/repos/{REPO}") as response:
        owner = json.load(response)["owner"]["login"]

    people = []
    for entry in line_stats():
        user = entry.get("author")
        if not user or user.get("type") == "Bot" or user["login"].lower() in SKIP:
            continue
        people.append({
            "login": user["login"],
            "added": sum(week["a"] for week in entry["weeks"]),
            "removed": sum(week["d"] for week in entry["weeks"]),
            "url": user["html_url"],
            "avatar": f"avatars/{user['login']}.webp",
            "avatar_src": user["avatar_url"],
            "owner": user["login"] == owner,
        })
    if not people:
        raise SystemExit("no contributors returned; keeping the old snapshot")
    # Owner first, then by lines added.
    people.sort(key=lambda p: (not p["owner"], -p["added"]))

    AVATARS.mkdir(parents=True, exist_ok=True)
    for old in AVATARS.glob("*.webp"):
        old.unlink()
    for person in people:
        src = person.pop("avatar_src")
        with fetch(f"{src}&s={SIZE}" if "?" in src else f"{src}?s={SIZE}", accept="image/*") as response:
            image = Image.open(io.BytesIO(response.read())).convert("RGBA").resize((SIZE, SIZE))
        image.save(AVATARS / f"{person['login']}.webp", "WEBP", lossless=True)
        if not person["owner"]:
            del person["owner"]

    (OUT / "contributors.json").write_text(json.dumps(people, indent=2) + "\n", encoding="utf-8")
    print(f"{len(people)} contributors written to {OUT}")


if __name__ == "__main__":
    main()
