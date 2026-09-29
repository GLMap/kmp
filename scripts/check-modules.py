#!/usr/bin/env python3
from pathlib import Path
import json
root=Path(__file__).resolve().parents[1]
pin=json.loads((root/'native-sdk.json').read_text())
version=pin['releaseVersion']
for name in ('glmap-core','glmap','glsearch','glroute'):
 text=(root/name/'build.gradle.kts').read_text()
 assert 'group="globus"' in text
 assert f'artifactId="{name}-kmp"' in text
 if name!='glmap-core':assert 'api(project(":glmap-core-kmp"))' in text
 if name!='glmap':assert 'compose' not in text.lower() and 'globus:glmap:' not in text
 assert f'?: "{version}"' in text, f'{name}: Android SDK pin differs from native-sdk.json'
 print('PASS','globus:'+name+'-kmp','native SDK',version)
apple=(root/'example/iosApp/project.yml').read_text()
assert f'url: {pin["swiftPackage"]}\n    exactVersion: {version}\n' in apple, 'Demo SwiftPM pin differs from native-sdk.json'
assert pin['androidRepository'] in (root/'settings.gradle.kts').read_text()
print('PASS demo SwiftPM pin',version)
