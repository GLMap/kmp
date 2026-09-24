#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
for name in ('glmap-core','glmap','glsearch','glroute'):
 text=(root/name/'build.gradle.kts').read_text()
 assert 'group="globus"' in text
 assert f'artifactId="{name}-kmp"' in text
 if name!='glmap-core':assert 'api(project(":glmap-core-kmp"))' in text
 if name!='glmap':assert 'compose' not in text.lower() and 'globus:glmap:' not in text
 print('PASS','globus:'+name+'-kmp')
