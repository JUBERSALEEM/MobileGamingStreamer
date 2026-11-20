#!/usr/bin/env python3
"""Fail CI when key release-readiness source declarations drift."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
policy = (root / "docs/privacy-policy.html").read_text(encoding="utf-8")

errors = []
def require(condition, message):
    if not condition:
        errors.append(message)

require('android:allowBackup="false"' in manifest, "App backup must remain disabled.")
require('android:foregroundServiceType="mediaProjection|microphone"' in manifest,
        "RTMP service must declare mediaProjection and microphone foreground-service types.")
require('android:name=".capture.ScreenCaptureService"' in manifest,
        "Screen capture test service declaration is missing.")
for permission in (
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.INTERNET",
):
    require(permission in manifest, f"Required permission declaration missing: {permission}")
require("AccessibilityService" not in manifest and "BIND_ACCESSIBILITY_SERVICE" not in manifest,
        "This build must not unexpectedly declare an AccessibilityService.")
require("Android Keystore" in policy and "AES-GCM" in policy,
        "Privacy policy must describe opt-in Keystore-backed stream-key encryption.")
require("No AccessibilityService is declared" in policy,
        "Privacy policy must state that no AccessibilityService is declared.")
require("not intentionally saved to app storage" not in policy,
        "Stale stream-key non-persistence disclosure remains in the policy.")

name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
code_match = re.search(r'versionCode\s*=\s*(\d+)', gradle)
require(name_match is not None, "Could not read versionName.")
require(code_match is not None, "Could not read versionCode.")
if name_match:
    version = name_match.group(1)
    require(re.search(rf"^## {re.escape(version)}-dev\b", changelog, re.M) is not None,
            f"CHANGELOG.md has no top-level {version}-dev section.")

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)
print("Release-readiness source audit passed.")
