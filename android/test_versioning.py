"""Check Android release version ordering."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
manifest = ET.parse(root / 'android/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
version = manifest.get(android + 'versionName')
code = int(manifest.get(android + 'versionCode'))
resources = ET.parse(root / 'android/res/values/strings.xml').getroot()
app_name = next(x.text for x in resources if x.tag == 'string' and x.get('name') == 'app_name')
assert app_name == 'Apple Eloquence', app_name
application = manifest.find('application')
assert application is not None
assert application.get(android + 'label') == '@string/app_name'
workflow = (root / '.github/workflows/android.yml').read_text()
build_script = (root / 'android/build.py').read_text()
assert 'apple-eloquence-android-arm64-${VERSION}.apk' in workflow
assert 'apple-eloquence-android-arm64-1.2.6.apk' in build_script
assert workflow.count('gh release delete-asset') == 2
assert 'reloq-c-android-arm64-*.apk' in workflow
match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version or '')
development = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)-(?:dev|rc)\d+', version or '')
if match:
    major, minor, patch = map(int, match.groups())
    expected = major * 1_000_000 + minor * 10_000 + patch
    if major > 1 or (major == 1 and minor >= 1):
        expected += 10_000_000
    assert code == expected, (version, code, expected)
    if (major, minor, patch) >= (1, 1, 0):
        assert code > 1_090_027, 'stable version code must retain release ordering'
elif development:
    major, minor, patch = map(int, development.groups())
    expected = major * 1_000_000 + minor * 10_000 + patch
    if major > 1 or (major == 1 and minor >= 1):
        expected += 10_000_000
    assert code == expected - 1, (version, code, expected)
print(f'Android version {version}: code {code}; release ordering checked')
