"""Safety boundaries: a living client never loses defence before world exit."""
import unittest
from types import SimpleNamespace

from uctest.survival import disconnect_before_stop, disconnect_gateway, GATEWAY_SOURCE


class SurvivalBoundaryTests(unittest.TestCase):
    def run_boundary(self, *, initially_online=True, exits_after=2,
                     disconnect_error=False, stop_error=False, rejoin=False):
        elapsed, checks, events = [0.0], [0], []
        online = [initially_online]

        def in_game():
            if 'disconnect' in events and 'stop' not in events and online[0]:
                checks[0] += 1
                if exits_after is not None and checks[0] >= exits_after:
                    online[0] = False
            return online[0]

        def disconnect():
            events.append('disconnect')
            if disconnect_error:
                raise ValueError('transport failed')

        def stop():
            self.assertFalse(online[0], 'Defence stopped while exposed')
            events.append('stop')
            if stop_error:
                raise ValueError('stop failed')
            online[0] = rejoin
            return 'inactive'

        def sleep(seconds):
            elapsed[0] += seconds

        try:
            result = disconnect_before_stop(in_game, disconnect, stop,
                timeout=0.3, clock=lambda: elapsed[0], sleep=sleep)
            return result, events
        except Exception as error:
            error.events = events
            raise

    def test_delayed_logout_precedes_stop(self):
        self.assertEqual(self.run_boundary(), ('inactive', ['disconnect', 'stop']))

    def test_already_offline_is_idempotent(self):
        self.assertEqual(self.run_boundary(initially_online=False), ('inactive', ['disconnect', 'stop']))

    def test_offline_cancel_failure_does_not_stop(self):
        with self.assertRaisesRegex(ValueError, 'transport failed') as caught:
            self.run_boundary(initially_online=False, disconnect_error=True)
        self.assertEqual(caught.exception.events, ['disconnect'])

    def test_gateway_declined_offline_logout_keeps_defence_active(self):
        events = []
        gateway = SimpleNamespace(
            entry_point=SimpleNamespace(inGame=lambda: False,
                disconnectFromServer=lambda: False,
                stopPathing=lambda: events.append('stop')),
            jvm=SimpleNamespace(net=SimpleNamespace(minecraft=SimpleNamespace(
                class_310=SimpleNamespace(method_1551=lambda: None)))))
        with self.assertRaisesRegex(RuntimeError, 'keeping survival active'):
            disconnect_gateway(gateway)
        self.assertEqual(events, [])

    def test_timeout_keeps_defence_active(self):
        with self.assertRaisesRegex(RuntimeError, 'keeping survival active') as caught:
            self.run_boundary(exits_after=None)
        self.assertEqual(caught.exception.events, ['disconnect'])

    def test_transport_error_keeps_defence_active(self):
        with self.assertRaisesRegex(ValueError, 'transport failed') as caught:
            self.run_boundary(disconnect_error=True)
        self.assertEqual(caught.exception.events, ['disconnect'])

    def test_stop_failure_is_not_success(self):
        with self.assertRaisesRegex(ValueError, 'stop failed'):
            self.run_boundary(stop_error=True)

    def test_rejoin_is_not_a_confirmed_boundary(self):
        with self.assertRaisesRegex(RuntimeError, 'rejoined'):
            self.run_boundary(rejoin=True)

    def test_container_source_uses_the_same_function(self):
        namespace = {}
        exec(GATEWAY_SOURCE, namespace)
        events = []
        namespace['disconnect_before_stop'](lambda: False,
            lambda: events.append('cancel'), lambda: events.append('stop'))
        self.assertEqual(events, ['cancel', 'stop'])


if __name__ == '__main__':
    unittest.main()
