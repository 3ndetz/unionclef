"""Regression checks for quiet bench children; runs without Minecraft or Docker.

    python deploy/runner/test_process.py
"""
import ast
import inspect
import json
import os
from pathlib import Path
import subprocess as stdlib
import sys
import tempfile
import unittest
from unittest.mock import patch

from uctest import process


def python(code):
    return [sys.executable, "-c", code]


def hidden_console_parent(output):
    """Noninteractive fixture: own hidden console and real console output handles."""
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.windll.kernel32
    kernel.GetConsoleWindow.restype = wintypes.HWND
    kernel.GetStdHandle.argtypes = [wintypes.DWORD]
    kernel.GetStdHandle.restype = wintypes.HANDLE
    window = kernel.GetConsoleWindow()
    assert window and not ctypes.windll.user32.IsWindowVisible(window)
    codes = []
    for name in ("run", "call", "Popen"):
        command = python("import ctypes,sys;print(" + repr(name + "-out")
            + ",flush=True);print(" + repr(name + "-err")
            + ",file=sys.stderr,flush=True);sys.exit(7 if ctypes.windll.kernel32.GetConsoleWindow()=="
            + str(window) + " else 9)")
        if name == "run":
            codes.append(process.run(command).returncode)
        elif name == "call":
            codes.append(process.call(command))
        else:
            with process.Popen(command) as child:
                codes.append(child.wait(timeout=10))
    partial = process.run(python("import sys;print('captured-out');print('partial-err',file=sys.stderr)"),
                          stdout=process.PIPE, text=True, check=True)
    class COORD(ctypes.Structure):
        _fields_ = [("X", wintypes.SHORT), ("Y", wintypes.SHORT)]
    kernel.ReadConsoleOutputCharacterW.argtypes = [wintypes.HANDLE, wintypes.LPWSTR,
        wintypes.DWORD, COORD, ctypes.POINTER(wintypes.DWORD)]
    buffer = ctypes.create_unicode_buffer(4096)
    count = wintypes.DWORD()
    assert kernel.ReadConsoleOutputCharacterW(kernel.GetStdHandle(-11), buffer, 4096,
                                             COORD(0, 0), ctypes.byref(count))
    output.write_text(json.dumps(dict(codes=codes, text=buffer.value[:count.value],
        captured=partial.stdout, window_visible=bool(ctypes.windll.user32.IsWindowVisible(window)))))


class ProcessTests(unittest.TestCase):
    def test_text_input_environment_cwd_and_separate_output(self):
        with tempfile.TemporaryDirectory() as directory:
            result = process.run(python(
                "import os,sys; print(os.getcwd()); print(os.environ['UCTEST_CHILD']);"
                "print(sys.stdin.read()); print('error',file=sys.stderr)"),
                input="input", text=True, capture_output=True, cwd=directory,
                env=dict(os.environ, UCTEST_CHILD="value"), check=True, timeout=10)
            self.assertEqual(result.stdout.splitlines(), [directory, "value", "input"])
            self.assertEqual(result.stderr, "error\n")
            self.assertEqual(result.returncode, 0)

    def test_binary_output_and_nonzero_result(self):
        result = process.run(python(
            "import sys; sys.stdout.buffer.write(b'\\x00\\xff');"
            "sys.stderr.buffer.write(b'failure'); sys.exit(7)"), capture_output=True)
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (7, b"\x00\xff", b"failure"))

    def test_checked_error_retains_both_streams(self):
        with self.assertRaises(process.CalledProcessError) as caught:
            process.run(python("import sys; print('out'); print('err',file=sys.stderr);sys.exit(9)"),
                        capture_output=True, text=True, check=True)
        error = caught.exception
        self.assertEqual((error.returncode, error.stdout, error.stderr), (9, "out\n", "err\n"))

    def test_timeout_kills_and_waits_for_child(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "completed"
            code = ("import pathlib,time; print('started',flush=True); time.sleep(1.5);"
                    f"pathlib.Path({str(marker)!r}).write_text('should not run')")
            with self.assertRaises(process.TimeoutExpired) as caught:
                process.run(python(code), capture_output=True, timeout=0.4)
            self.assertEqual(caught.exception.output, b"started\n" if os.name != "nt" else b"started\r\n")
            # A child left alive after the timeout would create this marker.
            process.run(python("import time;time.sleep(1.6)"), check=True)
            self.assertFalse(marker.exists())

    def test_popen_context_and_communicate(self):
        with process.Popen(python("import sys; print(sys.stdin.read());print('err',file=sys.stderr)"),
                           stdin=process.PIPE, stdout=process.PIPE, stderr=process.PIPE,
                           text=True) as child:
            self.assertIsInstance(child, stdlib.Popen)
            self.assertEqual(child.communicate("payload", timeout=10), ("payload\n", "err\n"))
        self.assertEqual(child.returncode, 0)

    def test_redirected_file_and_merged_stderr(self):
        with tempfile.TemporaryFile() as output:
            result = process.run(python("import sys;print('out',flush=True);print('err',file=sys.stderr)"),
                                 stdout=output, stderr=process.STDOUT, check=True)
            output.seek(0)
            self.assertEqual(output.read().splitlines(), [b"out", b"err"])
            self.assertIsNone(result.stdout)

    def test_nested_child_inherits_output_without_redirection_arguments(self):
        # The outer process supplies OS pipes, while the inner call uses the
        # default inherited-stream API. CREATE_NO_WINDOW must retain those pipes.
        directory = str(Path(__file__).resolve().parent)
        child = "import sys;print('inherited-out');print('inherited-err',file=sys.stderr);sys.exit(7)"
        command = f"[sys.executable,'-c',{child!r}]"
        for expression in (f"process.run({command}).returncode", f"process.call({command})",
                           f"process.Popen({command}).wait()"):
            with self.subTest(api=expression):
                code = (f"import sys;sys.path.insert(0,{directory!r});from uctest import process;"
                        f"result={expression};print('return='+str(result))")
                result = process.run(python(code), capture_output=True, text=True, check=True)
                self.assertEqual(result.stdout.splitlines(), ["inherited-out", "return=7"])
                self.assertEqual(result.stderr, "inherited-err\n")

    def test_older_apis(self):
        self.assertEqual(process.call(python("raise SystemExit(6)")), 6)
        self.assertEqual(process.check_call(python("pass")), 0)
        self.assertEqual(process.check_output(python("print('ok')"), text=True), "ok\n")
        with self.assertRaises(process.CalledProcessError) as caught:
            process.check_output(python("print('out');raise SystemExit(3)"), text=True)
        self.assertEqual((caught.exception.returncode, caught.exception.output), (3, "out\n"))

    def test_shell_and_missing_executable(self):
        result = process.run("echo quiet-shell", shell=True, capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.strip(), "quiet-shell")
        with self.assertRaises(FileNotFoundError):
            process.run(["uctest-nonexistent-executable-908ab4"])

    @unittest.skipUnless(os.name == "nt", "Windows console contract")
    def test_windows_children_keep_parent_console_and_existing_flags(self):
        import ctypes
        parent_console = str(ctypes.windll.kernel32.GetConsoleWindow())
        command = python("import ctypes; print(ctypes.windll.kernel32.GetConsoleWindow())")
        self.assertEqual(process.check_output(command, text=True).strip(), parent_console)
        with process.Popen(command, stdout=process.PIPE, text=True,
                           creationflags=process.CREATE_NEW_PROCESS_GROUP) as child:
            self.assertEqual(child.communicate(timeout=10)[0].strip(), parent_console)
        # Exercise positional creationflags, not just the common keyword form.
        defaults = list(inspect.signature(stdlib.Popen).parameters.values())
        index = next(i for i, value in enumerate(defaults) if value.name == "creationflags")
        positional = [command] + [p.default for p in defaults[1:index]] + [0]
        positional[4] = process.PIPE
        with process.Popen(*positional, text=True) as child:
            self.assertEqual(child.communicate(timeout=10)[0].strip(), parent_console)

    @unittest.skipUnless(os.name == "nt", "Windows console intent")
    def test_windows_existing_console_is_inherited_without_a_new_window(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            startup = stdlib.STARTUPINFO()
            startup.dwFlags |= stdlib.STARTF_USESHOWWINDOW
            startup.wShowWindow = stdlib.SW_HIDE
            # The fixture is hidden from creation, not a human's interactive window.
            with stdlib.Popen([sys.executable, str(Path(__file__).resolve()),
                               "--hidden-console-parent", str(output)],
                               creationflags=stdlib.CREATE_NEW_CONSOLE, startupinfo=startup) as child:
                self.assertEqual(child.wait(timeout=30), 0)
            result = json.loads(output.read_text())
            self.assertEqual(result["codes"], [7, 7, 7])
            self.assertFalse(result["window_visible"])
            self.assertEqual(result["captured"], "captured-out\n")
            for name in ("run", "call", "Popen"):
                self.assertIn(name + "-out", result["text"])
                self.assertIn(name + "-err", result["text"])
            self.assertIn("partial-err", result["text"])

    @unittest.skipUnless(os.name == "nt", "Windows console intent")
    def test_explicit_console_intent_is_preserved_without_opening_a_test_window(self):
        startup = stdlib.STARTUPINFO()
        startup.dwFlags |= stdlib.STARTF_USESHOWWINDOW
        startup.wShowWindow = 5  # SW_SHOW: an explicit visible-window request.
        cases = [dict(interactive=True), dict(creationflags=stdlib.CREATE_NEW_CONSOLE),
                 dict(creationflags=stdlib.DETACHED_PROCESS), dict(startupinfo=startup)]
        for options in cases:
            with self.subTest(options=options), patch.object(stdlib, "run") as launch:
                process.run(["intentional-interactive-child"], **options)
                expected = {k: v for k, v in options.items() if k != "interactive"}
                launch.assert_called_once_with(["intentional-interactive-child"], **expected)
        self.assertEqual(startup.dwFlags, stdlib.STARTF_USESHOWWINDOW)
        handles = stdlib.STARTUPINFO()
        handles.dwFlags |= stdlib.STARTF_USESTDHANDLES
        with patch.object(stdlib, "run") as launch:
            process.run(["caller-owned-handles"], startupinfo=handles)
            import ctypes
            expected = {"startupinfo": handles}
            if not ctypes.windll.kernel32.GetConsoleWindow():
                expected["creationflags"] = stdlib.CREATE_NO_WINDOW
            launch.assert_called_once_with(["caller-owned-handles"], **expected)

    def test_stdlib_is_not_globally_patched(self):
        self.assertIsNot(process.Popen, stdlib.Popen)
        self.assertEqual(stdlib.Popen.__module__, "subprocess")
        self.assertEqual(stdlib.run.__module__, "subprocess")

    @unittest.skipUnless(os.name == "posix", "POSIX process contract")
    def test_posix_session_and_signal_exit(self):
        child = json.loads(process.check_output(python(
            "import os,json; print(json.dumps([os.getpid(),os.getsid(0)]))"),
            start_new_session=True, text=True))
        self.assertEqual(child[0], child[1])
        result = process.run(python("import os,signal;os.kill(os.getpid(),signal.SIGTERM)"))
        self.assertEqual(result.returncode, -15)

    def test_tracked_wrappers_use_adapter(self):
        # Imports are scanned as Python syntax; embedded remote snippets are data.
        directory = Path(__file__).resolve().parent
        allowed = {Path(__file__).resolve(), directory / "uctest/process.py"}
        paths = list(directory.glob("*.py")) + list((directory / "uctest").glob("*.py"))
        for path in paths:
            with self.subTest(file=path.name):
                tree = ast.parse(path.read_text(encoding="utf-8-sig"))
                if path in allowed:
                    continue
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        self.assertFalse(any(alias.name == "subprocess" for alias in node.names),
                                         f"{path}:{node.lineno} bypasses quiet process adapter")
                    elif isinstance(node, ast.ImportFrom):
                        self.assertNotEqual(node.module, "subprocess",
                                            f"{path}:{node.lineno} bypasses quiet process adapter")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--hidden-console-parent":
        hidden_console_parent(Path(sys.argv[2]))
    else:
        unittest.main(verbosity=2)
