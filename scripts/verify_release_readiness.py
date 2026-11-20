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
studio_status = (root / "app/src/main/java/com/jubersaleem/mobilegamingstreamer/StudioStatus.kt").read_text(encoding="utf-8")

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
require('android:name=".capture.MgsMicAccessibilityService"' in manifest,
        "Glip-style microphone AccessibilityService declaration is missing.")
require("android.permission.BIND_ACCESSIBILITY_SERVICE" in manifest,
        "Accessibility service binding permission is missing.")
require("@xml/mgs_mic_accessibility_service" in manifest,
        "Microphone accessibility-service configuration is missing.")
require("does not retrieve window content" in policy and "microphone compatibility" in policy.lower(),
        "Privacy policy must disclose the optional microphone accessibility service and its limited scope.")
require("Android Keystore" in policy and "AES-GCM" in policy,
        "Privacy policy must describe opt-in Keystore-backed stream-key encryption.")
require("AccessibilityService" in policy and "window-state changes only" in policy,
        "Privacy policy must accurately describe the optional accessibility microphone bridge.")
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
    const_match = re.search(r'const val MgsVersionName\s*=\s*"([^"]+)"', studio_status)
    require(const_match is not None, "MgsVersionName constant is missing from StudioStatus.kt.")
    if const_match and name_match:
        require(const_match.group(1) == name_match.group(1),
                f"In-app MgsVersionName {const_match.group(1)} drifts from gradle versionName {name_match.group(1)}.")

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)
print("Release-readiness source audit passed.")
