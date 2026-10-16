#!/usr/bin/env python3
"""Validate Android GKI boot/init_boot images before and after Night patching."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


BOOT_MAGIC = b"ANDROID!"
GKI_PAGE_SIZE = 4096


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def align(value: int, alignment: int) -> int:
    return (value + alignment - 1) // alignment * alignment


def inspect_image(path: Path) -> str:
    size = path.stat().st_size
    if size < GKI_PAGE_SIZE:
        raise ValueError("image is smaller than one GKI header page")

    with path.open("rb") as stream:
        header = stream.read(GKI_PAGE_SIZE)

    if header[:8] != BOOT_MAGIC:
        raise ValueError("not an Android boot image (ANDROID! magic is missing)")

    header_version = u32(header, 40)
    if header_version not in (3, 4):
        raise ValueError(
            f"unsupported boot header v{header_version}; Night web patch supports GKI v3/v4 only"
        )

    kernel_size = u32(header, 8)
    ramdisk_size = u32(header, 12)
    header_size = u32(header, 20)
    expected_header_size = 1580 if header_version == 3 else 1584
    if header_size != expected_header_size:
        raise ValueError(
            f"invalid boot header size {header_size} for v{header_version}"
        )
    if ramdisk_size == 0:
        raise ValueError(
            "this boot image has no ramdisk; patch init_boot.img instead"
        )

    kernel_end = GKI_PAGE_SIZE + kernel_size
    ramdisk_offset = align(kernel_end, GKI_PAGE_SIZE)
    ramdisk_end = ramdisk_offset + ramdisk_size
    if ramdisk_end > size:
        raise ValueError(
            f"ramdisk exceeds image boundary ({ramdisk_end} > {size})"
        )

    image_type = "init_boot" if kernel_size == 0 else "boot"
    print(
        f"{image_type}: header=v{header_version} kernel={kernel_size} "
        f"ramdisk={ramdisk_size} image={size}"
    )
    return image_type


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--expect", choices=("boot", "init_boot"))
    args = parser.parse_args()

    try:
        image_type = inspect_image(args.image)
        if args.expect and image_type != args.expect:
            raise ValueError(
                f"expected {args.expect}, but image structure is {image_type}"
            )
    except (OSError, ValueError, struct.error) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
