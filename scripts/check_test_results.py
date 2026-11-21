#!/usr/bin/env python3
"""Fail CI when Gradle reports zero executed unit tests.

Inspects the JUnit XML reports Gradle writes (one TEST-*.xml per test
class) using standard-library XML parsing only. Counts actual testcase
elements: nested testsuite wrappers can never double-count a case, and a
case carrying a <skipped/> child counts as skipped rather than executed.

Fails when the results directory or report files are missing, when a report
is unparsable, when zero cases execute, or when every case is skipped.
Ordinary test failures remain Gradle's own failure (the test task fails
before this gate runs); this gate exists strictly to catch a silently
neutered suite. Usage: check_test_results.py [results-dir]
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_RESULTS_DIR = "app/build/test-results/testDebugUnitTest"


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RESULTS_DIR)
    files = sorted(root.glob("TEST-*.xml")) if root.is_dir() else []
    if not files:
        print(f"ERROR: no TEST-*.xml reports under {root}")
        return 1
    total = skipped = failed = errors = 0
    for report in files:
        try:
            tree = ET.parse(report)
        except ET.ParseError as exc:
            print(f"ERROR: unparsable report {report}: {exc}")
            return 1
        # Every testcase element is counted exactly once however deeply the
        # testsuite wrappers nest; suite-level attributes are never summed.
        for case in tree.getroot().iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped += 1
            if case.find("failure") is not None:
                failed += 1
            if case.find("error") is not None:
                errors += 1
    executed = total - skipped
    print(
        f"test files: {len(files)}, cases: {total}, executed: {executed}, "
        f"failed: {failed}, errors: {errors}, skipped: {skipped}"
    )
    if executed == 0:
        print("ERROR: zero executed test cases")
        return 1
    print("Zero-test gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
