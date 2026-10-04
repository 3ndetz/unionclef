"""Checkpoint replacement must not inherit the menu's automatic reconnect."""
import ast
from pathlib import Path
from types import SimpleNamespace
import unittest


class RestoreBoundaryTests(unittest.TestCase):
    def setUp(self):
        # Load the production boundary without CLI/import-time game side effects.
        tree = ast.parse(Path(__file__).with_name("gamer_smoke.py").read_text())
        function = next(node for node in tree.body
                        if isinstance(node, ast.FunctionDef)
                        and node.name == "restore_checkpoint")
        self.namespace = {"StandDown": RuntimeError}
        exec(compile(ast.Module(body=[function], type_ignores=[]),
                     "gamer_smoke.py", "exec"), self.namespace)
        self.restore = self.namespace["restore_checkpoint"]

    def test_deliberate_logout_prevents_restore_rejoin(self):
        events = []
        state = {"online": True, "reconnect": True}
        metadata = {"source": "saved-world"}

        def logout(op, **kwargs):
            self.assertEqual(op, "disconnect-confirmed")
            state.update(online=False, reconnect=False)
            events.append("logout")
            return {"inGame": False, "runner": "active=false"}

        def replace_world(name):
            # Model server restart: a pending menu retry would rejoin here.
            state["online"] = state["reconnect"]
            self.assertFalse(state["online"])
            self.assertEqual(name, "saved")
            events.append("restore")
            return metadata

        self.namespace.update(py4j=logout, _cp=SimpleNamespace(restore=replace_world))
        self.assertIs(self.restore("saved"), metadata)
        self.assertEqual(events, ["logout", "restore"])

    def test_unconfirmed_or_active_boundary_never_swaps_world(self):
        for boundary in ({}, {"inGame": True, "runner": "active=false"},
                         {"inGame": False, "runner": "active=true"},
                         {"inGame": False}):
            with self.subTest(boundary=boundary):
                swaps = []
                self.namespace.update(py4j=lambda *a, **k: boundary,
                    _cp=SimpleNamespace(restore=lambda name: swaps.append(name)))
                with self.assertRaisesRegex(RuntimeError, "logout was not confirmed"):
                    self.restore("saved")
                self.assertEqual(swaps, [])

    def test_transport_failure_never_swaps_world(self):
        swaps = []
        def fail(*args, **kwargs):
            raise OSError("gateway unavailable")
        self.namespace.update(py4j=fail,
            _cp=SimpleNamespace(restore=lambda name: swaps.append(name)))
        with self.assertRaisesRegex(OSError, "gateway unavailable"):
            self.restore("saved")
        self.assertEqual(swaps, [])

    def test_restore_failure_remains_failure(self):
        def fail(name):
            raise OSError("server restore failed")
        self.namespace.update(py4j=lambda *a, **k: {"inGame": False, "runner": "active=false"},
            _cp=SimpleNamespace(restore=fail))
        with self.assertRaisesRegex(OSError, "server restore failed"):
            self.restore("saved")


if __name__ == "__main__":
    unittest.main()
