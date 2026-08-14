#!/usr/bin/env python3
"""Idempotently store one MCP Bearer credential in a QwenPaw workspace.

The secret is accepted only on stdin so it never appears in argv or logs.
QwenPaw's own AsyncCredentialStore performs encryption, atomic replacement,
and owner-only file permission hardening.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from pathlib import Path

from qwenpaw.drivers.credentials import AsyncCredentialStore, CredentialRecord
from qwenpaw.drivers.errors import CredentialNotFoundError


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--credentials", required=True, type=Path)
    parser.add_argument("--server", required=True)
    parser.add_argument("--check-only", action="store_true")
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    if not args.server or any(char not in "abcdefghijklmnopqrstuvwxyz0123456789-" for char in args.server):
        raise SystemExit("--server must match [a-z0-9-]+")

    token = sys.stdin.read().rstrip("\r\n")
    if not token or "\n" in token or "\r" in token:
        raise SystemExit("exactly one non-empty token must be supplied on stdin")
    authorization = token if token.startswith("Bearer ") else f"Bearer {token}"
    ref = f"mcp/{args.server}"
    store = AsyncCredentialStore(args.credentials)

    changed = True
    created_at = time.time()
    try:
        current = await store.get(ref)
        changed = current.kind != "static" or current.secrets.get("authorization") != authorization
        created_at = current.meta.get("created_at", created_at)
    except CredentialNotFoundError:
        if args.check_only:
            print(f"[CREDENTIAL] MISSING {ref}")
            raise SystemExit(1)

    if args.check_only:
        print(f"[CREDENTIAL] {'DIFFER' if changed else 'MATCH'} {ref}")
        raise SystemExit(1 if changed else 0)

    if changed:
        now = time.time()
        await store.put(
            CredentialRecord(
                ref=ref,
                kind="static",
                secrets={"authorization": authorization},
                meta={
                    "created_at": created_at,
                    "updated_at": now,
                    "source": "chatflows-agentteams-apply",
                },
            ),
        )

    verified = await store.get(ref)
    if verified.kind != "static" or verified.secrets.get("authorization") != authorization:
        raise SystemExit(f"credential verification failed: {ref}")
    print(f"[CREDENTIAL] {'UPDATED' if changed else 'UNCHANGED'} {ref}")


if __name__ == "__main__":
    asyncio.run(main())
