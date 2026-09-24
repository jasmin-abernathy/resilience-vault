#!/usr/bin/env python3
"""Fail CI if bootstrap security gates are weakened accidentally."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def fail(message: str) -> None:
    print(f"SECURITY INVARIANT FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def require_build_flag_false(build_text: str, flag: str) -> None:
    pattern = re.compile(
        rf'buildConfigField\(\s*"boolean"\s*,\s*"{re.escape(flag)}"\s*,\s*"false"\s*\)',
        re.MULTILINE,
    )
    if not pattern.search(build_text):
        fail(f"{flag} must remain false until its dedicated security review")


def require_excludes(path: Path, parent_tag: str | None, domains: set[str]) -> None:
    root = ET.parse(path).getroot()
    parent = root if parent_tag is None else root.find(parent_tag)
    if parent is None:
        fail(f"{path}: missing <{parent_tag}>")

    excluded = {
        node.attrib.get("domain")
        for node in parent.findall("exclude")
        if node.attrib.get("path") == "."
    }
    missing = domains - excluded
    if missing:
        fail(f"{path}: missing full exclusions for {sorted(missing)} in {parent.tag}")


manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
manifest = ET.parse(manifest_path).getroot()
application = manifest.find("application")
if application is None:
    fail("AndroidManifest.xml has no application element")

if application.attrib.get(ANDROID + "allowBackup") != "false":
    fail("android:allowBackup must stay false")
if application.attrib.get(ANDROID + "usesCleartextTraffic") != "false":
    fail("android:usesCleartextTraffic must stay false")

permissions = {
    node.attrib.get(ANDROID + "name")
    for node in manifest.findall("uses-permission")
}
if "android.permission.RECEIVE_SMS" in permissions:
    fail("RECEIVE_SMS must not be present in the default/main manifest")

build_text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
require_build_flag_false(build_text, "PRODUCTION_CRYPTO_READY")
require_build_flag_false(build_text, "SMS_REMOTE_PANIC_READY")

domains = {"root", "file", "database", "sharedpref", "external"}
require_excludes(ROOT / "app/src/main/res/xml/backup_rules.xml", None, domains)
require_excludes(
    ROOT / "app/src/main/res/xml/data_extraction_rules.xml",
    "cloud-backup",
    domains,
)
require_excludes(
    ROOT / "app/src/main/res/xml/data_extraction_rules.xml",
    "device-transfer",
    domains,
)

remote_port = (
    ROOT / "app/src/main/java/org/lepotager/resiliencevault/cloud/RemoteVaultPort.kt"
).read_text(encoding="utf-8")
if "uploadCiphertext" not in remote_port:
    fail("RemoteVaultPort must expose an explicitly ciphertext-only upload boundary")
if "uploadPlaintext" in remote_port:
    fail("RemoteVaultPort must never expose uploadPlaintext")

print("Security invariants: OK")
