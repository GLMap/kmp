#!/usr/bin/env python3
"""Fetch published SwiftPM iOS binary targets, checking release-manifest SHA-256 values."""
import argparse
import hashlib
import json
import plistlib
import re
import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]


def fetch(url, version):
    # The release CDN rejects Python's default user agent.
    request = urllib.request.Request(url, headers={'User-Agent': 'GLMap-KMP-SDK/' + version})
    return urllib.request.urlopen(request, timeout=120)


def extract_ios_framework(archive, directory, name):
    """Extract only arm64 iOS device/simulator slices, never macOS symlinks."""
    directory = Path(directory).resolve()  # macOS /var is itself a symlink.
    prefix = f'{name}.xcframework'
    info_path = f'{prefix}/Info.plist'
    with zipfile.ZipFile(archive) as package:
        for item in package.infolist():
            path = PurePosixPath(item.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in item.filename:
                raise ValueError('Unsafe archive path')
        info = plistlib.loads(package.read(info_path))
        libraries = [item for item in info['AvailableLibraries']
                     if item['SupportedPlatform'] == 'ios'
                     and item.get('SupportedPlatformVariant', '') in ('', 'simulator')
                     and 'arm64' in item['SupportedArchitectures']]
        if {item.get('SupportedPlatformVariant', '') for item in libraries} != {'', 'simulator'}:
            raise ValueError(f'{name} lacks arm64 iOS device/simulator slices')
        for item in libraries:
            identifier = item['LibraryIdentifier']
            if not re.fullmatch(r'[A-Za-z0-9_-]+', identifier):
                raise ValueError('Unsafe library identifier')
        slices = tuple(f'{prefix}/{item["LibraryIdentifier"]}/' for item in libraries)
        for item in package.infolist():
            if item.filename != info_path and not item.filename.startswith(slices):
                continue
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Symlink in iOS SDK slice')
            if not (directory / item.filename).resolve().is_relative_to(directory):
                raise ValueError('Unsafe archive path')
            package.extract(item, directory)
    framework = directory / prefix
    for item in libraries:
        if not (framework / item['LibraryIdentifier'] / f'{name}.framework' / name).is_file():
            raise ValueError(f'Missing iOS framework binary: {name}')
    # Describe only the slices actually installed; macOS/Catalyst/watchOS are unused.
    info['AvailableLibraries'] = libraries
    (framework / 'Info.plist').write_bytes(plistlib.dumps(info))
    return framework


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default=json.loads((ROOT / 'native-sdk.json').read_text())['releaseVersion'])
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]*', args.version):
        parser.error('Invalid release version')
    manifest_url = f'https://raw.githubusercontent.com/GLMap/GLMapSwift/{args.version}/Package.swift'
    with fetch(manifest_url, args.version) as response:
        manifest = response.read().decode()
    entries = re.findall(r'\.binaryTarget\(\s*name:\s*"([^"]+)"\s*,\s*url:\s*"([^"]+)"\s*,\s*checksum:\s*"([0-9a-f]{64})"', manifest)
    expected = {'GLMap', 'GLMapCore', 'GLSearch', 'GLRoute'}
    entries = [entry for entry in entries if entry[0] in expected]
    if len(entries) != len(expected) or {entry[0] for entry in entries} != expected:
        raise SystemExit('Release manifest does not contain all four expected binary targets')
    dest = ROOT / '.local-sdk/ios'
    dest.mkdir(parents=True, exist_ok=True)
    for name, url, digest in entries:
        if not url.startswith('https://'):
            raise SystemExit('Binary targets must use HTTPS')
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            archive = directory / 'artifact.zip'
            with fetch(url, args.version) as response, archive.open('wb') as output:
                shutil.copyfileobj(response, output)
            with archive.open('rb') as source:
                if hashlib.file_digest(source, 'sha256').hexdigest() != digest:
                    raise SystemExit(f'Checksum mismatch: {name}')
            framework = extract_ios_framework(archive, directory, name)
            target = dest / f'{name}.xcframework'
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(framework, target)
    (dest / 'release.json').write_text(json.dumps({
        'version': args.version, 'manifestURL': manifest_url, 'artifacts': entries,
    }, indent=2) + '\n')
    print(f'Verified Apple headers/binaries for {args.version}: {dest}')


if __name__ == '__main__':
    main()
