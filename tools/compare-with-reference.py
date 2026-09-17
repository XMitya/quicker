#!/usr/bin/env python3
"""
Coverage oracle for the endpoint resolver.

Diffs the plugin's headless dump against `endpoints_owners.json`, produced by the in-repo
`EndpointOwnerParser` Gradle plugin, which resolves the same endpoints by reflection at build time
and runs in CI. It is the only ground truth available for this codebase.

The reference only covers modules whose report has actually been generated, so "extra" findings are
expected and mostly uninteresting; "missing" ones are the real signal.

  tools/compare-with-reference.py <dump.tsv> <monoRepoRoot>
"""
import json
import pathlib
import sys
from collections import defaultdict


def load_reference(root: pathlib.Path):
    """-> {(verb, path)}, plus the set of service names covered."""
    found, services = set(), set()
    for f in root.glob("**/build/reports/owners/endpoints_owners.json"):
        try:
            doc = json.loads(f.read_text())
        except Exception as exc:  # a stale or partial report should not abort the whole diff
            print(f"  ! unreadable {f}: {exc}", file=sys.stderr)
            continue
        services.add(doc.get("serviceName") or f.parts[-5])
        for e in doc.get("endpoints", []):
            raw = (e.get("path") or "").strip()
            if not raw:
                continue
            verb, _, path = raw.partition(" ")
            found.add((verb.strip().upper(), normalise(path)))
    return found, services


def load_dump(path: pathlib.Path):
    """-> {(verb, path)} and {(verb, path): [owner strings]}"""
    found = set()
    owners = defaultdict(list)
    for line in path.read_text().splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        key = (parts[0].strip().upper(), normalise(parts[1]))
        found.add(key)
        owners[key].append(parts[2])
    return found, owners


def normalise(path: str) -> str:
    """Path-variable names and inline regexes are not part of the identity of an endpoint."""
    out, depth, buf = [], 0, []
    for ch in path.strip():
        if ch == "{":
            depth += 1
            if depth == 1:
                buf = []
                continue
        if ch == "}":
            depth -= 1
            if depth == 0:
                out.append("{}")
                continue
        if depth == 0:
            out.append(ch)
    text = "".join(out).rstrip("/")
    return text if text.startswith("/") else "/" + text


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    dump_path, repo_root = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])

    reference, services = load_reference(repo_root)
    mine, owners = load_dump(dump_path)

    print(f"reference : {len(reference):5} endpoints across {len(services)} services")
    print(f"plugin    : {len(mine):5} endpoints (whole project)")

    # Only compare within services the reference actually covers.
    missing = sorted(reference - mine)
    print(f"\nagreed    : {len(reference & mine):5}")
    print(f"MISSING   : {len(missing):5}  (in reference, not found by the plugin)")
    for verb, path in missing[:40]:
        print(f"    - {verb} {path}")
    if len(missing) > 40:
        print(f"    ... and {len(missing) - 40} more")

    extra = sorted(mine - reference)
    print(f"\nextra     : {len(extra):5}  (plugin only - expected, reference covers 18 modules)")
    for verb, path in extra[:15]:
        print(f"    + {verb} {path}   [{owners[(verb, path)][0]}]")

    pct = 100.0 * len(reference & mine) / len(reference) if reference else 0.0
    print(f"\ncoverage of reference: {pct:.1f}%")
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main())
