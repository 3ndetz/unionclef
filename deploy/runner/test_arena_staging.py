"""Returning arena players reach dry support before slow setup; support survives clearing."""
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from uctest.actors import Bot
from uctest.arena import ArenaBuilder, STAND_Y
from uctest.scenario import Scenario
import run_suite


class ArenaStagingTests(unittest.TestCase):
    def test_waiting_support_is_outside_each_clearing_cube(self):
        for half in (6, 14, 40, 80):
            with self.subTest(half=half):
                commands = []
                arena = ArenaBuilder(SimpleNamespace(cmd=lambda command: commands.append(command)))
                spawn = arena.prepare_waiting_pad(half)
                x, y, z = map(float, spawn.split()[:3])
                self.assertGreater(x - 2.5, half)
                self.assertEqual(y, STAND_Y + 6)
                # Simulate the actual prepare() clearing range against support.
                support = commands[1].split()
                self.assertEqual(support[-1], 'stone')
                self.assertGreater(int(support[1]), half)
                self.assertEqual(int(support[2]) + 1, y)
                arena.remove_waiting_pad(half)
                self.assertEqual(commands[-1], commands[1].replace('stone', 'air'))

    def bot(self, *, on_target=False, position=None):
        commands, calls = [], []
        attempts = 0
        def command(text, **kwargs):
            nonlocal attempts
            commands.append(text)
            if text == 'list':
                return '1 players online: tester1' if on_target else '0 players online'
            attempts += 1
            return 'No entity was found' if attempts == 1 else 'Teleported tester1 to dry support'
        rcon = SimpleNamespace(cmd=command, entity_pos=lambda name: position or [48.5,-54,0.5])
        bot = Bot('owned-fixture', 'tester1', rcon, log=lambda *a: None)
        bot.py = SimpleNamespace(call=lambda method,*args: calls.append((method,args)) or False)
        return bot, commands, calls

    def test_connect_then_retry_teleport_without_old_five_second_join_delay(self):
        bot, commands, calls = self.bot()
        with patch('uctest.actors.time.sleep') as sleep:
            bot.ensure_in_game(rcon=bot.rcon, staging='48.5 -54 0.5 90 0')
        self.assertIn(('ConnectToServer',('test-server',)), calls)
        self.assertEqual(commands, ['list','tp tester1 48.5 -54 0.5 90 0','tp tester1 48.5 -54 0.5 90 0'])
        # wait_for uses harness.time, the same Python time module patched above.
        sleep.assert_called_once_with(0.25)

    def test_already_online_player_is_staged_without_reconnecting(self):
        bot, commands, calls = self.bot(on_target=True)
        with patch('uctest.actors.time.sleep'):
            bot.ensure_in_game(rcon=bot.rcon, staging='48.5 -54 0.5 90 0')
        self.assertFalse(any(method == 'ConnectToServer' for method,args in calls))
        self.assertTrue(commands[-1].startswith('tp tester1'))

    def test_wrong_or_incomplete_position_does_not_confirm_staging(self):
        for position in ([0.5,-60,0.5], [48.5,-54]):
            with self.subTest(position=position):
                bot, commands, calls = self.bot(position=position)
                with patch('uctest.actors.time.sleep'):
                    with self.assertRaisesRegex(RuntimeError,'not confirmed'):
                        bot.ensure_in_game(rcon=bot.rcon, staging='48.5 -54 0.5 90 0')

    def test_missing_target_rcon_fails_before_any_client_mutation(self):
        bot, commands, calls = self.bot()
        with self.assertRaisesRegex(ValueError,'target server rcon'):
            bot.ensure_in_game(staging='48.5 -54 0.5 90 0')
        self.assertEqual(calls, [])
        self.assertEqual(commands, [])

    def test_native_nonstaged_connection_retains_existing_behavior(self):
        bot, commands, calls = self.bot(on_target=True)
        bot.ensure_in_game(rcon=bot.rcon)
        self.assertEqual(commands,['list'])
        self.assertFalse(any(method == 'ConnectToServer' for method,args in calls))

    def failed_staging(self, declined_logout):
        class OrdinaryArena(Scenario):
            id = 'arena-staging-failure'
            needs_victim = False
        calls = []
        def join(*args, **kwargs):
            self.assertIn('staging',kwargs)
            calls.append('join')
            raise RuntimeError('saved hazard staging failed')
        bot = SimpleNamespace(container='owned-fixture',ensure_in_game=join,
                              stop_all=lambda:calls.append('bot-stop'))
        victim = SimpleNamespace(stop_all=lambda:calls.append('victim-stop'))
        def logout(container):
            calls.append('logout')
            if declined_logout:
                raise RuntimeError('logout declined')
            return dict(inGame=False,runner='active=false')
        arena = SimpleNamespace(prepare_waiting_pad=lambda half:'48.5 -54 0.5 90 0',
                                remove_waiting_pad=lambda half:calls.append('remove-pad'))
        art = SimpleNamespace(write_json=lambda *a:None,close=lambda:None)
        with patch.object(run_suite,'wait_for'),patch.object(run_suite,'ArenaBuilder',return_value=arena), \
             patch.object(run_suite,'Artifacts',return_value=art), \
             patch.object(run_suite,'disconnect_and_stop',side_effect=logout):
            if declined_logout:
                with self.assertRaisesRegex(RuntimeError,'logout declined'):
                    run_suite.run_scenario(OrdinaryArena,{'flat':object()},bot,victim,'unused')
            else:
                row=run_suite.run_scenario(OrdinaryArena,{'flat':object()},bot,victim,'unused')
                self.assertFalse(row['passed'])
        return calls

    def test_failed_returning_join_leaves_world_before_generic_stop_and_keeps_support(self):
        self.assertEqual(self.failed_staging(False),['join','logout','bot-stop','victim-stop'])

    def test_failed_returning_join_and_declined_exit_do_not_stop_or_remove_support(self):
        self.assertEqual(self.failed_staging(True),['join','logout'])


if __name__ == '__main__':
    unittest.main()
