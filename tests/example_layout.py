#!/usr/bin/env python3
"""Host-only checks for shared demo layout and native integration identifiers."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / "example"
PACKAGE = "software.globus.glmap.example"
APP_ID = "software.globus.glmap.demo"
FRAMEWORK = "GLMapDemoShared"


def read(path):
    return (ROOT / path).read_text()


class ExampleLayoutTests(unittest.TestCase):
    def test_gradle_layout(self):
        settings = read("settings.gradle.kts")
        for project in (":example:shared", ":example:androidApp"):
            self.assertIn(f'"{project}"', settings)
            self.assertTrue((ROOT / project[1:].replace(":", "/") / "build.gradle.kts").is_file())
        self.assertFalse((ROOT / "androidApp/build.gradle.kts").exists())
        self.assertFalse((ROOT / "iosApp/project.yml").exists())
        self.assertFalse((EXAMPLE / "build.gradle.kts").exists())
        self.assertIn('implementation(project(":example:shared"))', read("example/androidApp/build.gradle.kts"))
        for module in ("glmap-core", "glmap", "glsearch", "glroute"):
            self.assertNotIn('project(":example', read(f"{module}/build.gradle.kts"))

    def test_shared_entry_points(self):
        source = "example/shared/src"
        common = f"{source}/commonMain/kotlin/{PACKAGE.replace('.', '/')}/DemoApp.kt"
        ios = f"{source}/iosMain/kotlin/{PACKAGE.replace('.', '/')}/MainViewController.kt"
        android = f"example/androidApp/src/main/java/{APP_ID.replace('.', '/')}/MainActivity.kt"
        self.assertIn("@Composable fun DemoApp(", read(common))
        self.assertIn("ComposeUIViewController { DemoApp(apiKey) }", read(ios))
        self.assertIn(f"import {PACKAGE}.DemoApp", read(android))
        self.assertIn("setContent { DemoApp(BuildConfig.GLMAP_API_KEY) }", read(android))
        self.assertIn(f"import {FRAMEWORK}", read("example/iosApp/App.swift"))
        catalog = read(f"{source}/commonMain/kotlin/{PACKAGE.replace('.', '/')}/demo/Catalog.kt")
        self.assertEqual(20, len(re.findall(r'^\s*Demo\("', catalog, re.MULTILINE)))
        self.assertIn('Text("‹ Checks"', catalog)

    def test_kotlin_packages_match_directories(self):
        sources = list((EXAMPLE / "shared/src").glob("*/kotlin"))
        sources += list((EXAMPLE / "androidApp/src").glob("*/java"))
        self.assertEqual(5, len(sources))
        for source in sources:
            for path in source.rglob("*.kt"):
                with self.subTest(source=str(path.relative_to(ROOT))):
                    package = re.search(r"^package ([\w.]+)$", path.read_text(), re.MULTILINE)
                    self.assertIsNotNone(package)
                    self.assertEqual(package[1].replace(".", "/"), path.parent.relative_to(source).as_posix())
                    self.assertNotIn(".lab.", package[1])

    def test_platform_identities(self):
        android = read("example/androidApp/build.gradle.kts")
        self.assertIn(f'namespace = "{APP_ID}"', android)
        self.assertIn(f'applicationId = "{APP_ID}"', android)
        manifest = ET.parse(EXAMPLE / "androidApp/src/main/AndroidManifest.xml")
        self.assertEqual("GLMap Demo", manifest.find("application").attrib["{http://schemas.android.com/apk/res/android}label"])
        spec = read("example/iosApp/project.yml")
        self.assertTrue(spec.startswith("name: GLMapDemo\n"))
        self.assertIn(f"PRODUCT_BUNDLE_IDENTIFIER: {APP_ID}\n", spec)
        self.assertIn("INFOPLIST_KEY_CFBundleDisplayName: GLMap Demo\n", spec)
        self.assertIn(f"../shared/build/bin/$(KOTLIN_TARGET)/releaseFramework/{FRAMEWORK}.framework", spec)
        self.assertIn(f'baseName = "{FRAMEWORK}"', read("example/shared/build.gradle.kts"))
        ui_spec = read("example/iosApp/tests/project.yml")
        self.assertTrue(ui_spec.startswith("name: GLMapDemoUITests\n"))
        self.assertIn('iOS: "16.4"', ui_spec)
        self.assertIn(f"PRODUCT_BUNDLE_IDENTIFIER: {APP_ID}.uitests", ui_spec)
        self.assertIn(f'XCUIApplication(bundleIdentifier: "{APP_ID}")', read("example/iosApp/tests/MapUiTests.swift"))

    def test_shared_assets_and_config_paths(self):
        android = read("example/androidApp/build.gradle.kts")
        ios = read("example/iosApp/project.yml")
        for host in ("androidApp", "iosApp"):
            assets = (EXAMPLE / host / "../assets").resolve()
            self.assertEqual(EXAMPLE / "assets", assets)
            self.assertTrue((assets / "Montenegro.vm").is_file())
            self.assertTrue((assets / "valhalla.json").is_file())
        self.assertIn('assets.srcDirs("../assets")', android)
        self.assertIn("- path: ../assets", ios)
        self.assertIn('rootProject.file("example/config/local.json")', android)
        self.assertIn('CONFIG="${SRCROOT}/../config/local.json"', ios)
        self.assertIn('assets.srcDirs("../example/assets")', read("headless-app/build.gradle.kts"))
        self.assertIn("root/'example/assets/Montenegro.vm'", read("scripts/test-headless.py"))

    def test_generator_and_documented_commands(self):
        generator = read("scripts/generate-ios-project.py")
        self.assertIn("root/'example/iosApp/project.yml'", generator)
        self.assertIn("root/'example/iosApp/project.local.yml'", generator)
        readme = read("README.md")
        for command in (":example:androidApp:installDebug", ":example:shared:linkReleaseFrameworkIosArm64",
                        ":example:shared:linkReleaseFrameworkIosSimulatorArm64", "open example/iosApp/GLMapDemo.xcodeproj"):
            self.assertIn(command, readme)


if __name__ == "__main__":
    unittest.main(verbosity=2)
