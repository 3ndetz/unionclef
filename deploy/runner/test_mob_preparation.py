"""Failure-boundary contracts only; no game, survival or FPS claims."""
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from uctest.scenarios_mob import MobMelee
from uctest.scenario import Scenario
import run_suite


class PreparationTests(unittest.TestCase):
    def fixture(self, *, hp=20, paused=1, count=1, resume='Modified entity data of Zombie',
                active=True, spawn_error=False, command_error=False):
        events, artifacts = [], []
        ctx = SimpleNamespace(geo={})
        def rcon(command, **kwargs):
            if 'summon zombie' in command or 'NoAI:0b' in command:
                self.assertTrue(ctx.geo['entered_mob_fixture'])
            events.append(command)
            if 'summon zombie' in command and spawn_error:
                raise RuntimeError('spawn acknowledgement lost')
            if command == 'execute if entity @e[type=zombie]':
                return f'Count: {count}'
            return resume if 'NoAI:0b' in command else 'Modified entity data of Zombie'
        def activate(command):
            events.append(command)
            if command_error:
                raise RuntimeError('activation acknowledgement lost')
        reads = 0
        def runner(method):
            nonlocal reads
            reads += 1
            events.append(method)
            return 'active=false' if reads == 1 or not active else 'active=true'
        ctx.rcon = SimpleNamespace(cmd=rcon, entity_float=lambda *a: paused)
        ctx.bot = SimpleNamespace(name='tester1', health=lambda: hp, cmd=activate,
                                  py=SimpleNamespace(call=runner))
        ctx.art = SimpleNamespace(write_json=lambda name, data: artifacts.append((name, data)))
        return ctx, events, artifacts

    def invoke(self, ctx):
        with patch('uctest.scenarios_mob.time.sleep'):
            MobMelee()._start_prepared_zombie(ctx)

    def test_resume_is_immediately_followed_by_original_command(self):
        ctx, events, artifacts = self.fixture()
        self.invoke(ctx)
        release = events.index('data merge entity @e[type=zombie,limit=1] {NoAI:0b}')
        self.assertEqual(events[release+1], '@test kill')
        self.assertEqual(artifacts[0][1]['health_before_exposure'], 20)
        self.assertEqual(artifacts[1][1]['runner'], 'active=true')
        self.assertFalse(any('effect ' in event for event in events))

    def test_inadequate_setup_never_releases_or_activates(self):
        for kwargs in ({'hp':17}, {'hp':None}, {'paused':0}, {'paused':None}, {'count':0}, {'count':2}):
            with self.subTest(kwargs=kwargs):
                ctx, events, artifacts = self.fixture(**kwargs)
                with self.assertRaisesRegex(RuntimeError, 'inadequate mob staging'):
                    self.invoke(ctx)
                self.assertTrue(ctx.geo['entered_mob_fixture'])
                self.assertNotIn('@test kill', events)
                self.assertFalse(any('NoAI:0b' in event for event in events))
                self.assertEqual(artifacts, [])

    def test_lost_spawn_acknowledgement_keeps_exposure_marker(self):
        ctx, events, artifacts = self.fixture(spawn_error=True)
        with self.assertRaisesRegex(RuntimeError, 'spawn acknowledgement lost'):
            self.invoke(ctx)
        self.assertTrue(ctx.geo['entered_mob_fixture'])
        self.assertNotIn('@test kill', events)

    def test_negative_resume_reply_cannot_start_a_fight_against_a_statue(self):
        ctx, events, artifacts = self.fixture(resume='No entity was found')
        with self.assertRaisesRegex(RuntimeError, 'AI resume was not confirmed'):
            self.invoke(ctx)
        self.assertTrue(ctx.geo['entered_mob_fixture'])
        self.assertNotIn('@test kill', events)
        self.assertEqual([name for name, _ in artifacts], ['mob-preparation.json'])

    def test_lost_activation_acknowledgement_keeps_exposure_marker(self):
        ctx, events, artifacts = self.fixture(command_error=True)
        with self.assertRaisesRegex(RuntimeError, 'activation acknowledgement lost'):
            self.invoke(ctx)
        self.assertTrue(ctx.geo['entered_mob_fixture'])
        self.assertEqual([name for name, _ in artifacts], ['mob-preparation.json'])

    def test_inactive_timeout_cannot_claim_activation(self):
        ctx, events, artifacts = self.fixture(active=False)
        with patch('uctest.scenarios_mob.time.monotonic', side_effect=[0, 21]):
            with self.assertRaisesRegex(RuntimeError, 'task did not activate'):
                self.invoke(ctx)
        self.assertTrue(ctx.geo['entered_mob_fixture'])
        self.assertEqual([name for name, _ in artifacts], ['mob-preparation.json'])

    def test_already_active_runner_is_rejected_before_spawning(self):
        ctx, events, artifacts = self.fixture()
        ctx.bot.py.call = lambda *a: 'active=true'
        with self.assertRaisesRegex(RuntimeError, 'requires an inactive runner'):
            self.invoke(ctx)
        self.assertNotIn('entered_mob_fixture', ctx.geo)
        self.assertEqual(events, [])


class MobLifetimeTests(unittest.TestCase):
    def fixture(self, marked=True):
        events = []
        ctx = SimpleNamespace(geo={'entered_mob_fixture': True} if marked else {},
            bot=SimpleNamespace(container='owned-fixture', stop_all=lambda: events.append('stop')),
            victim=None)
        def logout(*args):
            events.append('logout')
            return dict(inGame=False, runner='active=false')
        return ctx, events, logout

    def test_marked_finish_leaves_world_before_stop(self):
        ctx, events, logout = self.fixture()
        with patch('uctest.scenarios_mob.disconnect_and_stop', side_effect=logout):
            MobMelee().drive_stop(ctx)
        self.assertEqual(events, ['logout', 'stop'])

    def test_declined_logout_preserves_live_defence(self):
        ctx, events, _ = self.fixture()
        with patch('uctest.scenarios_mob.disconnect_and_stop', side_effect=RuntimeError('declined')):
            with self.assertRaisesRegex(RuntimeError, 'declined'):
                MobMelee().drive_stop(ctx)
        self.assertEqual(events, [])
        self.assertNotIn('protective_logout', ctx.geo)

    def test_unmarked_subclasses_retain_ordinary_stop(self):
        ctx, events, _ = self.fixture(marked=False)
        with patch('uctest.scenarios_mob.disconnect_and_stop') as logout:
            MobMelee().drive_stop(ctx)
        self.assertEqual(events, ['stop'])
        logout.assert_not_called()

    def test_confirmed_boundary_is_not_repeated(self):
        ctx, events, _ = self.fixture()
        ctx.geo['protective_logout'] = dict(inGame=False, runner='active=false')
        with patch('uctest.scenarios_mob.disconnect_and_stop') as logout:
            MobMelee().drive_stop(ctx)
        self.assertEqual(events, ['stop'])
        logout.assert_not_called()

    def test_failed_setup_is_protected_by_real_suite_finally(self):
        for decline in (False, True):
            with self.subTest(decline=decline):
                events = []
                class FailedMobSetup(Scenario):
                    id = 'mob-boundary-fixture'
                    needs_victim = False
                    builds_arena = False
                    def build(self, arena, ctx):
                        ctx.geo['entered_mob_fixture'] = True
                        raise RuntimeError('lost spawn acknowledgement')
                bot = SimpleNamespace(container='owned-fixture',
                    ensure_in_game=lambda *a, **k: None, reset_config=lambda: None,
                    pin_settings=lambda *a: None, stop_all=lambda: events.append('bot-stop'))
                victim = SimpleNamespace(reset_config=lambda: None,
                    stop_all=lambda: events.append('victim-stop'))
                def logout(*args):
                    events.append('logout')
                    if decline:
                        raise RuntimeError('declined')
                    return dict(inGame=False, runner='active=false')
                art = SimpleNamespace(write_json=lambda *a: None, close=lambda: None)
                with patch.object(run_suite, 'wait_for'), patch.object(run_suite, 'EXTRA_PINS', {}), \
                     patch.object(run_suite, 'ArenaBuilder'), patch.object(run_suite, 'Artifacts', return_value=art), \
                     patch.object(run_suite, 'disconnect_and_stop', side_effect=logout):
                    if decline:
                        with self.assertRaisesRegex(RuntimeError, 'declined'):
                            run_suite.run_scenario(FailedMobSetup, {'flat': object()}, bot, victim, 'unused')
                        self.assertEqual(events, ['logout'])
                    else:
                        row = run_suite.run_scenario(FailedMobSetup, {'flat': object()}, bot, victim, 'unused')
                        self.assertFalse(row['passed'])
                        self.assertEqual(events, ['logout', 'bot-stop', 'victim-stop'])


if __name__ == '__main__':
    unittest.main()
