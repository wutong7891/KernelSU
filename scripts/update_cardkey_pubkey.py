#!/usr/bin/env python3
"""Embed the XhovoSU card-key verification key into the Manager sources.

The Manager trusts exactly one ECDSA P-256 public key. Development builds keep
the public development key that ships with the repository; release builds call
this script with the base64 DER of the production public key so the compiled
Manager only accepts licences minted by the matching private key.

Usage:
    python3 scripts/update_cardkey_pubkey.py --key <base64 SPKI DER>
    python3 scripts/update_cardkey_pubkey.py --key-file pub.der --der
"""

from __future__ import annotations

import argparse
import base64
import binascii
import pathlib
import re
import sys

TARGET = pathlib.Path("manager/app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/CardKeyKeys.kt")
CONSTANT = re.compile(r'(const val PUBLIC_KEY_DER_BASE64: String =\s*)"[^"]*"')
P256_SPKI_LENGTH = 91


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def decode_key(raw: str) -> bytes:
    try:
        der = base64.b64decode(raw.strip(), validate=True)
    except (binascii.Error, ValueError) as error:
        fail(f"key is not valid base64: {error}")
    if len(der) != P256_SPKI_LENGTH:
        fail(f"expected a {P256_SPKI_LENGTH} byte P-256 SPKI key, got {len(der)}")
    if der[0] != 0x30 or der[1] != 0x59:
        fail("key is not a DER sequence of the expected size")
    return der


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", help="base64 encoded SubjectPublicKeyInfo DER")
    parser.add_argument("--key-file", help="file holding the key", type=pathlib.Path)
    parser.add_argument("--der", action="store_true", help="--key-file is raw DER instead of base64")
    args = parser.parse_args()

    if args.key:
        encoded = args.key
        der = decode_key(encoded)
    elif args.key_file:
        data = args.key_file.read_bytes()
        der = data if args.der else decode_key(data.decode("ascii", "strict"))
        encoded = base64.b64encode(der).decode("ascii")
    else:
        fail("provide --key or --key-file")

    if not TARGET.is_file():
        fail(f"cannot find {TARGET} (run from the repository root)")

    source = TARGET.read_text(encoding="utf-8")
    updated, replacements = CONSTANT.subn(lambda match: f'{match.group(1)}"{encoded}"', source)
    if replacements != 1:
        fail(f"expected exactly one public key constant in {TARGET}, found {replacements}")

    TARGET.write_text(updated, encoding="utf-8")
    print(f"embedded XhovoSU card-key public key in {TARGET}")
    print(f"fingerprint: {__import__('hashlib').sha256(der).hexdigest()[:16].upper()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
