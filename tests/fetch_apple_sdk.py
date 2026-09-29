#!/usr/bin/env python3
"""Offline regressions for the published Apple SDK downloader."""
import importlib.util
import io
import plistlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fetch_sdk', Path(__file__).resolve().parents[1] / 'scripts/fetch-apple-sdk.py')
sdk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sdk)
NAME = 'GLMapCore'
PREFIX = NAME + '.xcframework/'


def archive(extra=None, simulator=True, identifier=None):
    data = io.BytesIO()
    libraries = [{'LibraryIdentifier': identifier or 'ios-arm64', 'SupportedPlatform': 'ios',
                  'SupportedArchitectures': ['arm64'], 'LibraryPath': NAME + '.framework'}]
    if simulator:
        libraries.append(dict(libraries[0], LibraryIdentifier='ios-arm64_x86_64-simulator',
                              SupportedPlatformVariant='simulator'))
    libraries.append(dict(libraries[0], LibraryIdentifier='macos-arm64_x86_64', SupportedPlatform='macos'))
    with zipfile.ZipFile(data, 'w') as package:
        package.writestr(PREFIX + 'Info.plist', plistlib.dumps({'AvailableLibraries': libraries}))
        for library in libraries:
            package.writestr(PREFIX + library['LibraryIdentifier'] + f'/{NAME}.framework/{NAME}', b'binary')
        if extra:
            package.writestr(*extra)
    data.seek(0)
    return data


class FetchTests(unittest.TestCase):
    def extract(self, package):
        with tempfile.TemporaryDirectory() as directory:
            return sdk.extract_ios_framework(package, directory, NAME)

    def test_ios_slices_ignore_macos_symlinks(self):
        link = zipfile.ZipInfo(PREFIX + 'macos-arm64_x86_64/GLMapCore.framework/Versions/Current')
        link.external_attr = 0o120777 << 16
        with tempfile.TemporaryDirectory() as directory:
            # Exercise a symlinked extraction root, as with /var on macOS.
            root = Path(directory)
            (root / 'real').mkdir()
            (root / 'alias').symlink_to(root / 'real', target_is_directory=True)
            result = sdk.extract_ios_framework(archive((link, 'A')), root / 'alias', NAME)
            self.assertFalse((result / 'macos-arm64_x86_64').exists())
            info = plistlib.loads((result / 'Info.plist').read_bytes())
            self.assertEqual(2, len(info['AvailableLibraries']))
            for library in info['AvailableLibraries']:
                self.assertEqual(b'binary', (result / library['LibraryIdentifier'] / f'{NAME}.framework/{NAME}').read_bytes())

    def test_reject_traversal_and_absolute_paths(self):
        for path in ('../escape', '/escape', PREFIX + '../escape', 'bad\\path'):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'Unsafe archive path'):
                self.extract(archive((path, 'bad')))

    def test_reject_ios_symlink(self):
        link = zipfile.ZipInfo(PREFIX + 'ios-arm64/GLMapCore.framework/Headers')
        link.external_attr = 0o120777 << 16
        with self.assertRaisesRegex(ValueError, 'Symlink in iOS'):
            self.extract(archive((link, '/outside')))

    def test_require_simulator(self):
        with self.assertRaisesRegex(ValueError, 'lacks arm64'):
            self.extract(archive(simulator=False))

    def test_reject_unsafe_library_identifier(self):
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            self.extract(archive(identifier='../escape'))

    def test_downloader_identifies_itself_and_has_timeout(self):
        with patch.object(sdk.urllib.request, 'urlopen') as open_url:
            sdk.fetch('https://example.org/sdk.zip', '2.2.0')
            request = open_url.call_args.args[0]
            self.assertEqual('GLMap-KMP-SDK/2.2.0', request.get_header('User-agent'))
            self.assertEqual(120, open_url.call_args.kwargs['timeout'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
