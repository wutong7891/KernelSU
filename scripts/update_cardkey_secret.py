#!/usr/bin/env python3
"""Embed the XhovoSU card-key secret into the Manager sources.

The 14-character code format uses a truncated HMAC, so the Manager and the
issuer share one secret. Release builds call this script with the secret from
the XHOVO_CARDKEY_SECRET_B64 repository secret so the shipped Manager only
accepts keys printed by the matching issuer.

Usage:
    python3 scripts/update_cardkey_secret.py --secret <base64>
    python3 scripts/update_cardkey_secret.py --secret-file secret.b64
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import pathlib
import re
import sys

TARGET = pathlib.Path("manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyKeys.kt")
CONSTANT = re.compile(r'(const val SECRET_BASE64: String =\s*)"[^"]*"')
MIN_SECRET_BYTES = 16


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--secret", help="base64 encoded secret")
    parser.add_argument("--secret-file", type=pathlib.Path, help="file holding the base64 secret")
    args = parser.parse_args()

    if args.secret:
        raw = args.secret.strip()
    elif args.secret_file:
        raw = args.secret_file.read_text(encoding="ascii").strip()
    else:
        fail("provide --secret or --secret-file")

    try:
        secret = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError) as error:
        fail(f"secret is not valid base64: {error}")
    if len(secret) < MIN_SECRET_BYTES:
        fail(f"secret must be at least {MIN_SECRET_BYTES} bytes, got {len(secret)}")

    if not TARGET.is_file():
        fail(f"cannot find {TARGET} (run from the repository root)")

    source = TARGET.read_text(encoding="utf-8")
    updated, replacements = CONSTANT.subn(lambda match: f'{match.group(1)}"{raw}"', source)
    if replacements != 1:
        fail(f"expected exactly one secret constant in {TARGET}, found {replacements}")

    TARGET.write_text(updated, encoding="utf-8")
    print(f"embedded XhovoSU card-key secret in {TARGET}")
    print(f"fingerprint: {hashlib.sha256(secret).hexdigest()[:16].upper()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
