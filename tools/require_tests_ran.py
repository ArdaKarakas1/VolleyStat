#!/usr/bin/env python3
"""Fail a CI job when the tests it exists for did not really run.

The end-to-end and device tests skip themselves when their backend or camera is missing.
That is right on a developer machine and wrong in CI, where a skipped test would show a green
job that proved nothing. Usage: require_tests_ran.py <junit-xml-dir> <minimum-test-count>
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    folder, minimum = Path(sys.argv[1]), int(sys.argv[2])
    ran = skipped = failed = 0
    for report in sorted(folder.rglob("*.xml")):
        root = ET.parse(report).getroot()
        for case in root.iter("testcase"):
            name = f"{case.get('classname')}.{case.get('name')}"
            if case.find("skipped") is not None:
                skipped += 1
                print(f"SKIPPED {name}")
            elif case.find("failure") is not None or case.find("error") is not None:
                failed += 1
                print(f"FAILED  {name}")
            else:
                ran += 1
                print(f"PASSED  {name}")
    print(f"{ran} passed, {failed} failed, {skipped} skipped (at least {minimum} must pass, none may be skipped)")
    return 0 if ran >= minimum and skipped == 0 and failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
