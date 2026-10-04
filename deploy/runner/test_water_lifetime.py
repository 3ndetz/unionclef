"""Water-fixture endpoints survive logout; failed logout retains live defence."""
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import run_suite
from uctest.scenario import Scenario
from uctest.scenarios_mob import DrownTunnel


class WaterLifetimeTests(unittest.TestCase):
    def specimen(self, air=223, health=20):
        state = dict(online=True, stops=0)
        def read(value):
            self.assertTrue(state['online'], 'Read a disappeared player after logout')
            return value
        def stop():
            self.assertFalse(state['online'], 'Stopped an exposed submerged specimen')
            state['stops'] += 1
        rcon = SimpleNamespace(entity_float=lambda *a: read(air), score=lambda *a: read(7))
        bot = SimpleNamespace(name='tester1', container='owned-test-fixture',
                              health=lambda: read(health), stop_all=stop)
        art = SimpleNamespace(write_json=lambda *a: None)
        ctx = SimpleNamespace(bot=bot, victim=None, rcon=rcon, art=art,
                              geo=dict(d0=7), samples=[dict(bot_hp=20)])
        def logout(*a):
            state['online'] = False
            return dict(inGame=False, runner='active=false')
        return ctx, state, logout

    def test_final_values_are_read_before_logout_and_judged_after_it(self):
        ctx, state, logout = self.specimen()
        with patch('uctest.scenarios_mob.disconnect_and_stop', side_effect=logout):
            DrownTunnel().drive_stop(ctx)
        self.assertEqual(state, dict(online=False, stops=1))
        criteria = list(DrownTunnel().judge(ctx))
        self.assertTrue(all(c.ok for c in criteria))
        self.assertEqual(criteria[1].detail, 'air=223')

    def test_final_health_loss_cannot_hide_between_samples_and_logout(self):
        ctx, state, logout = self.specimen(health=10)
        with patch('uctest.scenarios_mob.disconnect_and_stop', side_effect=logout):
            DrownTunnel().drive_stop(ctx)
        self.assertFalse(list(DrownTunnel().judge(ctx))[-1].ok)

    def test_missing_final_readout_still_leaves_the_world(self):
        ctx, state, logout = self.specimen(air=None)
        with patch('uctest.scenarios_mob.disconnect_and_stop', side_effect=logout):
            with self.assertRaisesRegex(RuntimeError, 'Missing final'):
                DrownTunnel().drive_stop(ctx)
        self.assertFalse(state['online'])

    def test_failed_logout_does_not_stop_the_water_driver(self):
        ctx, state, logout = self.specimen()
        with patch('uctest.scenarios_mob.disconnect_and_stop', side_effect=RuntimeError('logout declined')):
            with self.assertRaisesRegex(RuntimeError, 'logout declined'):
                DrownTunnel().drive_stop(ctx)
        self.assertEqual(state, dict(online=True, stops=0))

    def failed_setup(self, protected, decline, water_entry=False):
        class FailingSetup(Scenario):
            id = 'water-boundary-fixture'
            needs_victim = False
            ends_with_logout = protected
            builds_arena = False  # This contract isolates the failed logout, not arena staging.
            def build(self, arena, ctx):
                if water_entry:
                    ctx.geo['entered_water_fixture'] = True
                raise RuntimeError('intentional setup witness')
        calls = []
        bot = SimpleNamespace(container='owned-test-fixture',
            ensure_in_game=lambda *a, **k: None, reset_config=lambda: None,
            pin_settings=lambda *a: None, stop_all=lambda: calls.append('bot-stop'))
        victim = SimpleNamespace(reset_config=lambda: None, stop_all=lambda: calls.append('victim-stop'))
        def logout(*a):
            calls.append('logout')
            if decline:
                raise RuntimeError('logout declined')
            return dict(inGame=False, runner='active=false')
        art = SimpleNamespace(write_json=lambda *a: None, close=lambda: None)
        with patch.object(run_suite, 'wait_for'), patch.object(run_suite, 'EXTRA_PINS', {}), \
             patch.object(run_suite, 'ArenaBuilder'), patch.object(run_suite, 'Artifacts', return_value=art), \
             patch.object(run_suite, 'disconnect_and_stop', side_effect=logout):
            if decline:
                with self.assertRaisesRegex(RuntimeError, 'logout declined'):
                    run_suite.run_scenario(FailingSetup, {'flat': object()}, bot, victim, 'unused')
            else:
                row = run_suite.run_scenario(FailingSetup, {'flat': object()}, bot, victim, 'unused')
                self.assertFalse(row['passed'])
                self.assertEqual(row['error'], 'intentional setup witness')
        return calls

    def test_failed_setup_confirms_logout_before_actor_cleanup(self):
        self.assertEqual(self.failed_setup(True, False), ['logout', 'bot-stop', 'victim-stop'])

    def test_failed_setup_and_declined_logout_keeps_both_drivers(self):
        self.assertEqual(self.failed_setup(True, True), ['logout'])

    def test_ordinary_scenarios_retain_existing_setup_cleanup(self):
        self.assertEqual(self.failed_setup(False, False), ['bot-stop', 'victim-stop'])

    def test_failed_water_entry_is_protected_without_changing_ordinary_cleanup(self):
        self.assertEqual(self.failed_setup(False, False, True), ['logout', 'bot-stop', 'victim-stop'])
        self.assertEqual(self.failed_setup(False, True, True), ['logout'])


class WaterEntryTests(unittest.TestCase):
    def specimen(self, air=300, hp=20, active=True):
        events, artifacts = [], []
        reads = iter([air, 271])
        rcon = SimpleNamespace(entity_float=lambda *a: next(reads),
                               cmd=lambda command: events.append(command))
        bot = SimpleNamespace(name='tester1', health=lambda: hp,
            py=SimpleNamespace(call=lambda method: 'active=' + str(active).lower()))
        ctx = SimpleNamespace(bot=bot, rcon=rcon, geo=dict(water_entry='0.5 -62 0.5 -90 0'),
                              art=SimpleNamespace(write_json=lambda *a: artifacts.append(a)))
        return ctx, events, artifacts

    def test_water_entry_precedes_activation_with_no_heal_or_air_injection(self):
        ctx, events, artifacts = self.specimen()
        Scenario().start_in_water(ctx, lambda: events.append('@goto original-target'))
        self.assertEqual(events, ['tp tester1 0.5 -62 0.5 -90 0', '@goto original-target'])
        self.assertTrue(ctx.geo['entered_water_fixture'])
        self.assertEqual(artifacts[0][1]['entry_air'], 271)

    def test_inadequate_dry_staging_never_enters_or_starts_navigation(self):
        for air, hp in ((48, 20), (300, 19)):
            with self.subTest(air=air, hp=hp):
                ctx, events, artifacts = self.specimen(air=air, hp=hp)
                with self.assertRaisesRegex(RuntimeError, 'inadequate dry staging'):
                    Scenario().start_in_water(ctx, lambda: events.append('activate'))
                self.assertEqual(events, [])
                self.assertEqual(artifacts, [])
                self.assertNotIn('entered_water_fixture', ctx.geo)

    def test_declined_command_keeps_water_cleanup_marker_and_cannot_claim_entry_success(self):
        ctx, events, artifacts = self.specimen()
        def decline():
            raise RuntimeError('command declined')
        with self.assertRaisesRegex(RuntimeError, 'command declined'):
            Scenario().start_in_water(ctx, decline)
        self.assertTrue(ctx.geo['entered_water_fixture'])
        self.assertEqual(artifacts, [])

    def test_inactive_runner_timeout_keeps_cleanup_marker_and_fails(self):
        ctx, events, artifacts = self.specimen(active=False)
        with patch('uctest.scenario.time.monotonic', side_effect=[0, 21]):
            with self.assertRaisesRegex(RuntimeError, 'task did not activate'):
                Scenario().start_in_water(ctx, lambda: events.append('activate'))
        self.assertTrue(ctx.geo['entered_water_fixture'])
        self.assertEqual(artifacts, [])


if __name__ == '__main__':
    unittest.main()
