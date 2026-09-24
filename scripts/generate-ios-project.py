#!/usr/bin/env python3
import os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'example/iosApp/project.yml').read_text()
sdk=os.environ.get('GLMAP_SDK_DIR')
if sdk:
    source=source.replace('    url: https://github.com/GLMap/GLMapSwift.git\n    exactVersion: 2.2.0', '    path: '+str(Path(sdk).resolve()/'ios'))
path=root/'example/iosApp/project.local.yml'
path.write_text(source)
subprocess.run(['xcodegen','generate','--spec',str(path)],check=True)
