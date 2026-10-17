#!/usr/bin/env python3
"""Turn a pinned ImageForge checkout into the Night browser-local patcher."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


KMIS = (
    "android12-5.10",
    "android13-5.10",
    "android13-5.15",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
    "android17-6.18",
)


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"expected exactly one customization marker in {path}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--imageforge", required=True, type=Path)
    parser.add_argument("--kmi-root", required=True, type=Path)
    parser.add_argument("--ksuinit", required=True, type=Path)
    parser.add_argument("--night-commit", required=True)
    parser.add_argument("--released-at", required=True)
    args = parser.parse_args()

    forge = args.imageforge.resolve()
    destination = forge / "public" / "artifacts" / "kernelsu" / "night"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.ksuinit, destination / "ksuinit")

    modules: list[dict[str, object]] = []
    for kmi in KMIS:
        source = args.kmi_root / f"aarch64-{kmi}-lkm" / f"{kmi}_kernelsu.ko"
        if not source.is_file():
            raise FileNotFoundError(source)
        target = destination / f"lkm-aarch64-{kmi}_kernelsu.ko"
        shutil.copy2(source, target)
        modules.append({"kmi": kmi, "sha256": digest(target), "sizeBytes": target.stat().st_size})

    wrapper = destination / "ksuinit"
    module_rows = "\n".join(
        f'      {{ kmi: "{item["kmi"]}", sha256: "{item["sha256"]}", sizeBytes: {item["sizeBytes"]} }},'
        for item in modules
    )
    member = f'''  {{
    flavor: "night",
    release: "night-{args.night_commit[:12]}",
    releasedAt: "{args.released_at}",
    managerPackage: "com.Night.night",
    ksuinit: {{ sha256: "{digest(wrapper)}", sizeBytes: {wrapper.stat().st_size} }},
    modules: [
{module_rows}
    ],
    notes:
      "Night build from wutong7891/KernelSU commit {args.night_commit}. The wrapper and per-KMI modules are bundled unmodified from that build and are certificate-bound to com.Night.night. They retain their GPL licences; complete corresponding source and the exact customization recipe are published with this build.",
  }},
'''
    catalog = forge / "src" / "core" / "artifacts" / "catalog.ts"
    replace_once(
        catalog,
        "const KERNELSU_FAMILY: KernelsuFamilyMember[] = [\n",
        "const KERNELSU_FAMILY: KernelsuFamilyMember[] = [\n" + member,
    )

    flavor = '''  {
    id: "night",
    label: "Night",
    managerPackage: "com.Night.night",
    ksuinitArtifactId: kernelsuFamilyKsuinitId("night"),
    lkmArtifactId: (kmi) => kernelsuFamilyLkmId("night", kmi),
    source: "wutong7891/KernelSU Night build",
    bundledModuleConfig: [],
    legacyEntry: "allow_shell",
    injectModuleConfig: false,
  },
'''
    config = forge / "src" / "core" / "patch" / "providers" / "kernelsu-config.ts"
    replace_once(
        config,
        "export const KERNELSU_FLAVORS: KernelsuFlavor[] = [\n",
        "export const KERNELSU_FLAVORS: KernelsuFlavor[] = [\n" + flavor,
    )
    replace_once(config, 'export const KERNELSU_DEFAULT_FLAVOR = "kernelsu";', 'export const KERNELSU_DEFAULT_FLAVOR = "night";')

    manager = '''  {
    packageName: "com.Night.night",
    name: "Night",
    releaseUrl: "https://wtlyf-night-license.pages.dev/api/v1/night/latest-apk",
  },
'''
    manager_apps = forge / "src" / "core" / "patch" / "manager-apps.ts"
    replace_once(manager_apps, "export const MANAGER_APPS: ManagerApp[] = [\n", "export const MANAGER_APPS: ManagerApp[] = [\n" + manager)

    flavor_test = forge / "tests" / "unit" / "kernelsu-flavors.test.ts"
    replace_once(flavor_test, '      "kernelsu",\n', '      "night",\n      "kernelsu",\n')
    replace_once(flavor_test, 'expect(KERNELSU_DEFAULT_FLAVOR).toBe("kernelsu");', 'expect(KERNELSU_DEFAULT_FLAVOR).toBe("night");')
    replace_once(flavor_test, 'expect(kernelsuFlavor(undefined).id).toBe("kernelsu");', 'expect(kernelsuFlavor(undefined).id).toBe("night");')
    replace_once(flavor_test, 'expect(kernelsuFlavor("nope").id).toBe("kernelsu");', 'expect(kernelsuFlavor("nope").id).toBe("night");')

    default_module = next(item for item in modules if item["kmi"] == "android15-6.6")
    provider_test = forge / "tests" / "integration" / "kernelsu.test.ts"
    replace_once(
        provider_test,
        'expect(outcome.result.metadata.moduleOrigin).toBe("bundled (kernelsu-lkm-android15-6.6)");',
        'expect(outcome.result.metadata.moduleOrigin).toBe("bundled (night-lkm-android15-6.6)");',
    )
    replace_once(
        provider_test,
        '"c31d994aaf285e7bf4cf1ec38c2bbf2d7f303d1a4a7d616405bcd9f850d684e5",',
        f'"{default_module["sha256"]}",',
    )
    replace_once(
        provider_test,
        'expect(plan?.configuration.moduleArtifact).toBe("kernelsu-lkm-" + kmi);',
        'expect(plan?.configuration.moduleArtifact).toBe("night-lkm-" + kmi);',
    )

    page_test = forge / "tests" / "integration" / "patch-page.test.tsx"
    replace_once(
        page_test,
        '      "KernelSU · official KernelSU release v3.3.0",\n',
        '      "Night · wutong7891/KernelSU Night build",\n      "KernelSU · official KernelSU release v3.3.0",\n',
    )
    replace_once(
        page_test,
        'expect(store().planResponse?.plan.artifact.id).toBe("kernelsu-ksuinit")',
        'expect(store().planResponse?.plan.artifact.id).toBe("night-ksuinit")',
    )

    manager_test = forge / "tests" / "unit" / "manager-apps.test.ts"
    replace_once(
        manager_test,
        'expect(entry.packageName).toMatch(/^[a-z][a-z0-9_.]+$/);',
        'expect(entry.packageName).toMatch(/^[A-Za-z][A-Za-z0-9_.]+$/);',
    )
    replace_once(
        manager_test,
        'expect(entry.releaseUrl).toMatch(/^https:\\/\\/github\\.com\\/.+\\/releases$/);',
        'expect(entry.releaseUrl).toMatch(/^https:\\/\\//);',
    )

    replace_once(forge / "index.html", '<title>ImageForge</title>', '<title>Night 本地镜像修补器</title>')
    replace_once(forge / "index.html", 'content="ImageForge"', 'content="Night 本地镜像修补器"')
    replace_once(
        forge / "src" / "i18n" / "messages" / "zh-Hans.ts",
        '"app.title": "ImageForge — Android 镜像修补工具",',
        '"app.title": "Night — 本地 Android 镜像修补工具",',
    )

    package_path = forge / "package.json"
    package_data = json.loads(package_path.read_text(encoding="utf-8"))
    package_data["name"] = "night-local-image-patcher"
    package_data["description"] = "Night browser-local Android boot and init_boot image patcher."
    package_data["repository"] = {"type": "git", "url": "git+https://github.com/wutong7891/KernelSU.git"}
    package_data["homepage"] = "https://github.com/wutong7891/KernelSU/tree/night"
    package_path.write_text(json.dumps(package_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

    notice = forge / "THIRD_PARTY_LICENSES" / "kernelsu" / "NIGHT.md"
    notice.write_text(
        "# Night KernelSU build\n\n"
        f"- Source: https://github.com/wutong7891/KernelSU/tree/{args.night_commit}\n"
        f"- Commit: `{args.night_commit}`\n"
        "- Manager package: `com.Night.night`\n"
        "- `ksuinit`: GPL-3.0-or-later\n"
        "- Kernel modules: GPL-2.0-only\n\n"
        "The files in `public/artifacts/kernelsu/night/` are copied without modification from the Night build.\n",
        encoding="utf-8",
        newline="\n",
    )

    manifest = {
        "nightCommit": args.night_commit,
        "releasedAt": args.released_at,
        "managerPackage": "com.Night.night",
        "ksuinit": {"sha256": digest(wrapper), "sizeBytes": wrapper.stat().st_size},
        "modules": modules,
        "privacy": "Boot and init_boot images are processed in the browser and are not uploaded.",
    }
    (forge / "public" / "NIGHT-BUILD.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )


if __name__ == "__main__":
    main()
