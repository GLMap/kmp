#!/usr/bin/env python3
"""Fetch the published SwiftPM binary targets, checking their release-manifest SHA-256 values."""
import argparse, hashlib, json, plistlib, re, shutil, tempfile, urllib.request, zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version',default=json.loads((root/'native-sdk.json').read_text())['releaseVersion'])
args=parser.parse_args()
if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]*',args.version):raise SystemExit('Invalid release version')
manifest_url=f'https://raw.githubusercontent.com/GLMap/GLMapSwift/{args.version}/Package.swift'
with urllib.request.urlopen(manifest_url) as response:manifest=response.read().decode()
entries=re.findall(r'\.binaryTarget\(\s*name:\s*"([^"]+)"\s*,\s*url:\s*"([^"]+)"\s*,\s*checksum:\s*"([0-9a-f]{64})"',manifest)
expected={'GLMap','GLMapCore','GLSearch','GLRoute'}
entries=[entry for entry in entries if entry[0] in expected]
if {e[0] for e in entries}!=expected:raise SystemExit('Release manifest does not contain all four expected binary targets')
dest=root/'.local-sdk/ios';dest.mkdir(parents=True,exist_ok=True)
for name,url,digest in entries:
    if not url.startswith('https://'):raise SystemExit('Binary targets must use HTTPS')
    with tempfile.TemporaryDirectory() as directory:
        directory=Path(directory);archive=directory/'artifact.zip'
        with urllib.request.urlopen(url) as response,archive.open('wb') as output:shutil.copyfileobj(response,output)
        if hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()!=digest:raise SystemExit(f'Checksum mismatch: {name}')
        with zipfile.ZipFile(archive) as package:
            for item in package.infolist():
                if not (directory/item.filename).resolve().is_relative_to(directory):raise SystemExit('Unsafe archive path')
                if (item.external_attr>>16)&0o170000==0o120000:raise SystemExit('Symlink in SDK archive')
            package.extractall(directory)
        frameworks=list(directory.rglob(f'{name}.xcframework'))
        if len(frameworks)!=1:raise SystemExit(f'Expected one {name}.xcframework')
        info=plistlib.loads((frameworks[0]/'Info.plist').read_bytes())
        platforms={(i['SupportedPlatform'],i.get('SupportedPlatformVariant','')) for i in info['AvailableLibraries'] if 'arm64' in i['SupportedArchitectures']}
        if not {('ios',''),('ios','simulator')} <= platforms:raise SystemExit(f'{name} lacks arm64 iOS device/simulator slices')
        target=dest/f'{name}.xcframework'
        if target.exists():shutil.rmtree(target)
        shutil.copytree(frameworks[0],target)
(dest/'release.json').write_text(json.dumps({'version':args.version,'manifestURL':manifest_url,'artifacts':entries},indent=2)+'\n')
print(f'Verified Apple headers/binaries for {args.version}: {dest}')
