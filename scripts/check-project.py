"""Offline manifest/source/configuration checks; not Android runtime tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
import yaml

ROOT = Path(__file__).resolve().parent.parent
A = "{http://schemas.android.com/apk/res/android}"
controller = ET.parse(ROOT / "android/controller/src/main/AndroidManifest.xml").getroot()
sandbox = ET.parse(ROOT / "android/sandbox/src/main/AndroidManifest.xml").getroot()
permissions = {p.attrib[A + "name"] for p in controller.findall("uses-permission")}
assert permissions == {"android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS"}
assert not sandbox.findall("uses-permission")
assert controller.find("application").attrib[A + "allowBackup"] == "false"
access = ET.parse(ROOT / "android/controller/src/main/res/xml/accessibility.xml").getroot()
assert access.attrib[A + "isAccessibilityTool"] == "false"
assert "flagRequestTouchExplorationMode" not in access.attrib[A + "accessibilityFlags"]
source = (ROOT / "android/controller/src/main/java/org/phonebridge/controller/ControllerService.java").read_text()
assert "takeScreenshotOfWindow(" in source and "takeScreenshot(" not in source
assert source.index("if (node.isPassword()") < source.index("safe(node.getText())")
assert 'lease = ""; // Single-use' in source
assert 'GuardPolicy.checkLease(' in source
assert 'GuardPolicy.checkPoint(' in source
assert 'GuardPolicy.checkText(' in source
assert "registerReceiver" not in source  # no externally spoofable stop/enable broadcast
assert '"audit-unavailable"' in source
ci = yaml.safe_load((ROOT / ".github/workflows/build.yml").read_text())
for job in ci["jobs"].values():
    assert job["runs-on"] == "ubuntu-24.04"
    assert "repository_visibility == 'public'" in job["if"]
print("Offline manifest/policy/CI checks passed")
