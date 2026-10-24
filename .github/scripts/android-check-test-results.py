"""Reject empty, incomplete or failed device reports, including UTP false greens."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
report_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/build/outputs/androidTest-results/connected/debug"
expected = sum(len(re.findall(r"@Test\b", p.read_text())) for p in (root / "app/src/androidTest").rglob("*.kt"))
reports = list(report_dir.glob("TEST-*.xml"))
if not reports:
    sys.exit("No device test reports were produced")
for report in reports:
    result = ET.parse(report).getroot()
    tests = int(result.get("tests", 0))
    failed = sum(int(result.get(k, 0)) for k in ("failures", "errors", "skipped"))
    cases = len(list(result.iter("testcase")))
    if tests < max(expected, 1) or cases != tests or failed:
        sys.exit(f"Incomplete device validation: {report.name}: {tests} tests, {cases} cases, {failed} failed/skipped; expected at least {expected}")
    print(f"Verified {report.name}: {tests} tests, no failures/errors/skips")
