#!/usr/bin/env python3
from pathlib import Path
import subprocess
from kotlin_runner import ROOT, OUT, declaration, run_kotlin
HERE=ROOT/'tests/downloads'
source=(HERE/'Harness.kt').read_text()
for platform in ['android','ios']:
    production=(ROOT/f'glmap-core/src/{platform}Main/kotlin/globus/glmap/core/CoreSdk.{platform}.kt').read_text()
    source=source.replace(f'/* KMP_{platform.upper()} */',declaration(production,'override suspend fun downloadArea('))
run_kotlin(source)
