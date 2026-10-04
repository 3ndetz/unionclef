"""Exercise the production shell freshness boundary without Docker or copying mods."""
import os
from pathlib import Path
import shutil
import tempfile
import unittest

from uctest import process


class DeployFreshnessTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="unionclef-deploy-freshness-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.jar = self.root / "versions/1.21.11/build/libs/unionclef-1.21.11-test.jar"
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b"freshness fixture; nested packaging is tested separately")
        os.utime(self.jar, (2000, 2000))
        script = Path(__file__).resolve().parents[1] / "deploy_jar.sh"
        boundary = script.read_text(encoding="utf-8").split("# ...AND THE SAME CHECK FOR THE JARS INSIDE IT.", 1)
        self.assertEqual(len(boundary), 2)
        self.script = self.root / "deploy/deploy_jar.sh"
        self.script.parent.mkdir()
        self.script.write_text(boundary[0] + "\necho FRESHNESS_BOUNDARY_PASSED\n", encoding="utf-8", newline="\n")
        self.shell = "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("sh")
        self.assertTrue(self.shell)

    def run_boundary(self, *, allow_stale=False, broken_find=False):
        environment = dict(os.environ, UCTEST_ALLOW_STALE="1" if allow_stale else "0", LC_ALL="C")
        if broken_find:
            binary = self.root / "tools"
            binary.mkdir(exist_ok=True)
            find = binary / "find"
            find.write_text("#!/bin/sh\necho unreadable-fixture >&2\nexit 2\n", newline="\n")
            find.chmod(0o755)
            # Convert the fixture PATH inside the shell on Windows.
            command = 'PATH="$PWD/tools:$PATH" sh deploy/deploy_jar.sh'
        else:
            command = "sh deploy/deploy_jar.sh"
        return process.run([self.shell, "-c", command], cwd=self.root, env=environment,
                           capture_output=True, text=True, encoding="utf-8", timeout=30)

    def put_class(self, directory, modified):
        path = self.root / directory / "example/Current.class"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"class fixture")
        os.utime(path, (modified, modified))

    def test_missing_outputs_pass_without_stale_override(self):
        result = self.run_boundary()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("FRESHNESS_BOUNDARY_PASSED", result.stdout)

    def test_scoped_old_bytecode_passes_with_other_outputs_absent(self):
        self.put_class("versions/1.21.11/build/classes", 1000)
        self.put_class("tungsten/build/classes", 1000)
        result = self.run_boundary()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_newer_bytecode_in_every_supported_root_is_rejected(self):
        for directory in ("versions/1.21.11/build/classes", "versions/1.21.1/build/classes",
                          "build/classes", "tungsten/build/classes", "shredder/build/classes"):
            with self.subTest(directory=directory):
                self.put_class(directory, 3000)
                result = self.run_boundary()
                self.assertEqual(result.returncode, 1)
                self.assertIn("STALE JAR:", result.stderr)
                self.assertNotIn("FRESHNESS_BOUNDARY_PASSED", result.stdout)
                os.utime(self.root / directory / "example/Current.class", (1000, 1000))

    def test_deliberate_stale_replay_remains_explicit(self):
        self.put_class("tungsten/build/classes", 3000)
        result = self.run_boundary(allow_stale=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("UCTEST_ALLOW_STALE=1 -- continuing", result.stderr)

    def test_existing_root_read_error_cannot_be_bypassed(self):
        self.put_class("tungsten/build/classes", 1000)
        for allow_stale in (False, True):
            with self.subTest(allow_stale=allow_stale):
                result = self.run_boundary(allow_stale=allow_stale, broken_find=True)
                self.assertEqual(result.returncode, 1)
                self.assertIn("cannot check compiled bytecode", result.stderr)
                self.assertNotIn("FRESHNESS_BOUNDARY_PASSED", result.stdout)

    def test_missing_jar_still_fails_before_freshness(self):
        self.jar.unlink()
        result = self.run_boundary()
        self.assertEqual(result.returncode, 1)
        self.assertIn("no jar", result.stdout)


if __name__ == "__main__":
    unittest.main()
