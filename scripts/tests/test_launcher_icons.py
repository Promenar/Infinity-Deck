#!/usr/bin/env python3
"""在隔离的 Gradle 项目中验证应用图标生成与覆盖防护。"""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TASK_SOURCE = ROOT / "native-ui/buildSrc"
GENERATOR = ROOT / "scripts/branding/infinity_icon.py"
RASTERS = ROOT / "assets/branding/launcher-foreground"
DENSITIES = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
AAPT = "{http://schemas.android.com/aapt}"
SVG = '''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
<title>测试母版</title>
<defs><radialGradient id="bg" cx="54" cy="46" r="76" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#18204A" stop-opacity="1"/><stop offset="1" stop-color="#04050C" stop-opacity="1"/></radialGradient><radialGradient id="h0" cx="40" cy="60" r="3" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#BEEEFF" stop-opacity="0.55"/><stop offset="1" stop-color="#BEEEFF" stop-opacity="0"/></radialGradient></defs>
<g id="background">
<path d="M0 0H108V108H0Z" fill="url(#bg)"/>
</g>
<g id="foreground">
<path d="M37.00 60.00a3.00 3.00 0 1 0 6.00 0a3.00 3.00 0 1 0 -6.00 0Z" fill="url(#h0)"/>
<path d="M39.00 60.00a1.00 1.00 0 1 0 2.00 0a1.00 1.00 0 1 0 -2.00 0Z" fill="#BEEEFF" fill-opacity="0.95"/>
</g>
</svg>'''
MONO = '''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
<g id="foreground"><path d="M39.00 60.00a1.00 1.00 0 1 0 2.00 0a1.00 1.00 0 1 0 -2.00 0Z" fill="#000000"/></g>
</svg>'''
NOTIFY = '''<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
<g id="foreground"><path d="M4 12L12 8L20 12L12 16Z" fill="none" stroke="#000000" stroke-width="2" stroke-linejoin="round"/></g>
</svg>'''


class LauncherIconGenerationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="infinity-icon-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / "native-ui"
        self.project.mkdir(parents=True)
        self.assets = self.root / "assets/branding"
        self.assets.mkdir(parents=True)
        self.master = self.assets / "infinity-dots-master.svg"
        self.mono = self.assets / "infinity-dots-monochrome.svg"
        self.notify = self.assets / "infinity-notification.svg"
        self.master.write_text(SVG, encoding="utf-8")
        self.mono.write_text(MONO, encoding="utf-8")
        self.notify.write_text(NOTIFY, encoding="utf-8")
        self.rasters = self.assets / "launcher-foreground"
        shutil.copytree(RASTERS, self.rasters)
        (self.project / "settings.gradle.kts").write_text('rootProject.name = "icon-contract"\n')
        (self.project / "build.gradle.kts").write_text('''import com.promenar.infinity.buildlogic.GenerateLauncherIcons
tasks.register<GenerateLauncherIcons>("generateInfinityLauncherIcons") {
    masterSvg.set(layout.projectDirectory.file("../assets/branding/infinity-dots-master.svg"))
    foregroundRasters.set(layout.projectDirectory.dir("../assets/branding/launcher-foreground"))
    monochromeSvg.set(layout.projectDirectory.file("../assets/branding/infinity-dots-monochrome.svg"))
    notificationSvg.set(layout.projectDirectory.file("../assets/branding/infinity-notification.svg"))
    sourceRoots.from(layout.projectDirectory.dir("app/src"))
    outputDirectory.set(layout.buildDirectory.dir("generated/infinityLauncherIcons/res"))
}
''')
        shutil.copytree(TASK_SOURCE, self.project / "buildSrc", ignore=shutil.ignore_patterns("build", ".gradle"))
        self.resources = self.project / "build/generated/infinityLauncherIcons/res"

    def run_generator(self, *, expect_success: bool = True) -> subprocess.CompletedProcess:
        env = {key: value for key, value in os.environ.items() if not key.startswith("INFINITY_")}
        result = subprocess.run(
            [str(ROOT / "native-ui/gradlew"), "-p", str(self.project), "--offline",
             "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx256m",
             "generateInfinityLauncherIcons"],
            env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180,
        )
        if expect_success:
            self.assertEqual(result.returncode, 0, result.stdout[-6000:])
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout[-6000:])
        return result

    def xml(self, name: str) -> ET.Element:
        return ET.parse(self.resources / name).getroot()

    def test_adaptive_layers_keep_geometry_gradients_and_opacity(self) -> None:
        self.run_generator()
        for api in (26, 33):
            for name in ("ic_launcher", "ic_launcher_round"):
                icon = self.xml(f"mipmap-anydpi-v{api}/{name}.xml")
                self.assertEqual(icon.tag, "adaptive-icon")
                self.assertEqual(icon.find("background").get(ANDROID + "drawable"), "@drawable/ic_launcher_background")
                self.assertEqual(icon.find("foreground").get(ANDROID + "drawable"), "@drawable/ic_launcher_foreground")
                mono = icon.find("monochrome")
                if api == 33:
                    self.assertEqual(mono.get(ANDROID + "drawable"), "@drawable/ic_launcher_monochrome")
                else:
                    self.assertIsNone(mono)
        self.assertEqual(sorted(p.relative_to(self.resources).as_posix() for p in self.resources.rglob("*.xml")), sorted([
            "drawable/ic_launcher_background.xml",
            "drawable/ic_launcher_monochrome.xml", "drawable/ic_notification.xml",
            "mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml",
            "mipmap-anydpi-v33/ic_launcher.xml", "mipmap-anydpi-v33/ic_launcher_round.xml"]))

        source = {g.get("id"): [p.get("d") for p in g] for g in ET.fromstring(SVG) if g.tag.endswith("}g")}
        background = self.xml("drawable/ic_launcher_background.xml")
        self.assertEqual(background.get(ANDROID + "viewportWidth"), "108")
        self.assertEqual([p.get(ANDROID + "pathData") for p in background], source["background"])

    def test_foreground_is_density_raster_copied_from_branding(self) -> None:
        self.run_generator()
        for density in DENSITIES:
            generated = self.resources / f"drawable-{density}/ic_launcher_foreground.png"
            self.assertEqual(generated.read_bytes(), (self.rasters / f"{density}.png").read_bytes())

    def test_monochrome_and_notification_are_solid_silhouettes(self) -> None:
        self.run_generator()
        mono = self.xml("drawable/ic_launcher_monochrome.xml")
        self.assertEqual([p.get(ANDROID + "fillColor") for p in mono], ["#000000"])
        notify = self.xml("drawable/ic_notification.xml")
        self.assertEqual((notify.get(ANDROID + "width"), notify.get(ANDROID + "viewportWidth")), ("24dp", "24"))
        path, = list(notify)
        self.assertIsNone(path.get(ANDROID + "fillColor"))
        self.assertEqual((path.get(ANDROID + "strokeColor"), path.get(ANDROID + "strokeWidth"), path.get(ANDROID + "strokeLineJoin")),
                         ("#000000", "2", "round"))

    def test_rebuild_consumes_changed_master_without_stale_outputs(self) -> None:
        self.run_generator()
        stale = self.resources / "drawable/ic_launcher_foreground.xml"
        stale.write_text("<vector/>", encoding="utf-8")
        self.master.write_text(SVG.replace("M0 0H108V108H0Z", "M0 0H108V107H0Z"), encoding="utf-8")
        self.run_generator()
        paths = [p.get(ANDROID + "pathData") for p in self.xml("drawable/ic_launcher_background.xml")]
        self.assertEqual(paths, ["M0 0H108V107H0Z"])
        self.assertFalse(stale.exists())

    def test_rejects_missing_or_wrong_size_raster(self) -> None:
        shutil.copyfile(self.rasters / "mdpi.png", self.rasters / "hdpi.png")
        self.assertIn("像素见方的透明 PNG", self.run_generator(expect_success=False).stdout)
        (self.rasters / "hdpi.png").unlink()
        self.assertIn("缺少前景位图", self.run_generator(expect_success=False).stdout)

    def test_rejects_legacy_and_variant_resource_overrides(self) -> None:
        for relative in ("app/src/release/res/mipmap-xxxhdpi/ic_launcher.png", "app/src/main/res/drawable/ic_notification.xml"):
            with self.subTest(relative):
                override = self.project / relative
                override.parent.mkdir(parents=True, exist_ok=True)
                override.write_bytes(b"stale icon")
                result = self.run_generator(expect_success=False)
                self.assertIn("图标资源必须由母版生成", result.stdout)
                override.unlink()

    def test_rejects_custom_resource_root_without_res_in_its_path(self) -> None:
        build = self.project / "build.gradle.kts"
        build.write_text(build.read_text().replace('sourceRoots.from(layout.projectDirectory.dir("app/src"))',
                                                  'sourceRoots.from(layout.projectDirectory.dir("customResources"))'))
        override = self.project / "customResources/mipmap-anydpi-night-v26/ic_launcher.xml"
        override.parent.mkdir(parents=True)
        override.write_text('<adaptive-icon/>')
        result = self.run_generator(expect_success=False)
        self.assertIn("图标资源必须由母版生成", result.stdout)

    def test_rejects_manifest_icon_override(self) -> None:
        manifest = self.project / "app/src/release/AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:icon="@drawable/old_logo"/></manifest>')
        result = self.run_generator(expect_success=False)
        self.assertIn("Manifest 图标引用必须统一", result.stdout)

    def test_rejects_reserved_name_override_in_arbitrary_values_file(self) -> None:
        override = self.project / "app/src/main/res/values-night/old_palette.xml"
        override.parent.mkdir(parents=True)
        override.write_text('<resources><color name="infinity_launcher_foreground">#6366F1</color></resources>')
        result = self.run_generator(expect_success=False)
        self.assertIn("图标资源必须由母版生成", result.stdout)

    def test_rejects_raster_or_transformed_master(self) -> None:
        self.master.write_text(SVG.replace('<g id="background">', '<image href="photo.png"/><g id="background">'))
        self.assertIn("母版", self.run_generator(expect_success=False).stdout)
        self.master.write_text(SVG.replace('<g id="foreground">', '<g id="foreground" transform="scale(2)">'))
        self.assertIn("母版仅允许", self.run_generator(expect_success=False).stdout)

    def test_rejects_nested_viewport_instead_of_silently_changing_geometry(self) -> None:
        nested = SVG.replace('<g id="foreground">', '<g id="foreground"><svg width="108" height="108" viewBox="0 0 216 216"></svg>')
        self.master.write_text(nested, encoding="utf-8")
        self.assertIn("直接路径", self.run_generator(expect_success=False).stdout)

    def test_rejects_undefined_gradient_and_translucent_silhouettes(self) -> None:
        self.master.write_text(SVG.replace('fill="url(#h0)"', 'fill="url(#missing)"'), encoding="utf-8")
        self.assertIn("已定义的径向渐变", self.run_generator(expect_success=False).stdout)
        self.master.write_text(SVG, encoding="utf-8")
        self.mono.write_text(MONO.replace('fill="#000000"', 'fill="#000000" fill-opacity="0.5"'), encoding="utf-8")
        self.assertIn("纯色不透明剪影", self.run_generator(expect_success=False).stdout)


class CommittedMasterTest(unittest.TestCase):
    def test_committed_masters_match_generator(self) -> None:
        result = subprocess.run([sys.executable, str(GENERATOR), "--check"], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
