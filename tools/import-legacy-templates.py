#!/usr/bin/env python3
"""Install original CivCraft .def building assets without committing a 600 MB asset pack.

Usage:
  python tools/import-legacy-templates.py --scope capitals
  python tools/import-legacy-templates.py --scope theme --theme default
  python tools/import-legacy-templates.py --scope all --destination ./plugins/CivCraft/legacy-templates

The upstream revision is deliberately pinned; every downloaded file is verified
against the Git blob SHA-1 in the revision tree. No files are downloaded during
plugin startup. Existing customized templates are never overwritten by default.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

SOURCE = "Ichi667/civcraft"
COMMIT = "1ee4a0f180aaa5dd230200f70e8ae6a32eb9bc9c"
PREFIX = PurePosixPath("civcraft_data/templates")


def download(url: str) -> bytes:
    req = Request(url, headers={"User-Agent": "CivCraft-1.21.11-asset-importer"})
    with urlopen(req, timeout=120) as response:
        return response.read()


def git_blob_sha(content: bytes) -> str:
    header = f"blob {len(content)}\0".encode("ascii")
    return hashlib.sha1(header + content).hexdigest()


def available_entries() -> list[dict]:
    url = f"https://api.github.com/repos/{SOURCE}/git/trees/{COMMIT}?recursive=1"
    tree = json.loads(download(url))
    if tree.get("truncated"):
        raise RuntimeError("GitHub tree response was truncated; refusing partial import")
    return [entry for entry in tree.get("tree", [])
            if entry.get("type") == "blob"
            and entry["path"].startswith(f"{PREFIX}/themes/")
            and entry["path"].endswith(".def")]


def select(entries: list[dict], scope: str, theme: str) -> list[dict]:
    result = []
    for entry in entries:
        relative = PurePosixPath(entry["path"]).relative_to(PREFIX)
        components = relative.parts
        if len(components) < 5 or components[0] != "themes":
            continue
        if scope == "capitals":
            if (components[1] == theme and components[2] == "structures"
                    and components[3] == "capitol"
                    and components[-1].startswith("capitol_")):
                result.append(entry)
        elif scope == "theme":
            if components[1] == theme:
                result.append(entry)
        else:
            result.append(entry)
    return sorted(result, key=lambda x: x["path"])


def install(entry: dict, destination: Path, overwrite: bool) -> str:
    relative = PurePosixPath(entry["path"]).relative_to(PREFIX)
    if any(part in ("", ".", "..") for part in relative.parts):
        raise RuntimeError(f"Unsafe upstream path: {relative}")
    target = destination.joinpath(*relative.parts)
    if target.is_file():
        existing = target.read_bytes()
        if git_blob_sha(existing) == entry["sha"]:
            return "unchanged"
        if not overwrite:
            return "customized"
    target.parent.mkdir(parents=True, exist_ok=True)
    url = f"https://raw.githubusercontent.com/{SOURCE}/{COMMIT}/{quote(entry['path'], safe='/')}"
    content = download(url)
    if git_blob_sha(content) != entry["sha"]:
        raise RuntimeError(f"SHA verification failed for {entry['path']}")
    if target.exists() and not target.is_file():
        raise RuntimeError(f"Destination is not a regular file: {target}")
    with tempfile.NamedTemporaryFile(mode="wb", dir=target.parent, delete=False,
                                     prefix=".civcraft-", suffix=".tmp") as temp:
        temp.write(content)
        temp_path = Path(temp.name)
    try:
        os.replace(temp_path, target)
    finally:
        temp_path.unlink(missing_ok=True)
    return "installed"


def main() -> int:
    parser = argparse.ArgumentParser(description="Download verified original CivCraft .def templates")
    parser.add_argument("--scope", choices=("capitals", "theme", "all"), default="capitals",
                        help="capitals=large Capitol structures; theme=one complete theme; all=every theme (~594 MB)")
    parser.add_argument("--theme", default="default", help="CivCraft legacy theme, default: default")
    parser.add_argument("--destination", type=Path,
                        default=Path("plugins/CivCraft/legacy-templates"),
                        help="Template root in a Paper server folder")
    parser.add_argument("--overwrite", action="store_true",
                        help="Explicitly replace existing customized template files")
    args = parser.parse_args()

    if not args.theme.replace("_", "").isalnum():
        parser.error("Invalid theme name")
    try:
        entries = select(available_entries(), args.scope, args.theme)
        if not entries:
            print("No matching templates in the pinned upstream revision", file=sys.stderr)
            return 1
        print(f"Source: {SOURCE} @ {COMMIT}")
        print(f"Destination: {args.destination.resolve()}")
        print(f"Selected files: {len(entries)}")
        totals = {"installed": 0, "unchanged": 0, "customized": 0, "failed": 0}
        for i, entry in enumerate(entries, 1):
            try:
                outcome = install(entry, args.destination, args.overwrite)
            except (OSError, ValueError, HTTPError, URLError, RuntimeError) as exc:
                outcome = "failed"
                print(f"[{i}/{len(entries)}] FAIL {entry['path']}: {exc}", file=sys.stderr)
            else:
                print(f"[{i}/{len(entries)}] {outcome}: {entry['path']}")
            totals[outcome] += 1
        print("Summary:", ", ".join(f"{key}={value}" for key, value in totals.items()))
        return 1 if totals["failed"] else 0
    except (OSError, ValueError, HTTPError, URLError, RuntimeError) as exc:
        print(f"Import failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
