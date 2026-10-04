"""A transient lava exit must be observed, with no missing-trace or respawn false pass."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from uctest.scenarios_craft import EscapeLavaPool


class EscapeObservationTests(unittest.TestCase):
    def specimen(self, rows):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        calls, artifacts = [], []
        def call(method, *args):
            calls.append((method,args))
            return [json.dumps(row) for row in rows] if method == 'getMovementTrace' else True
        bot = SimpleNamespace(py=SimpleNamespace(call=call),health=lambda:12,
                              pos=lambda:[20,-60,20],stop_all=lambda:None)
        ctx = SimpleNamespace(bot=bot,victim=None,geo={},t0=1,
            art=SimpleNamespace(path=lambda name:str(Path(directory.name)/name),
                                write_json=lambda *args:artifacts.append(args)))
        scn=EscapeLavaPool()
        scn._start_escape_observation(ctx)
        return scn,ctx,calls,artifacts

    def tick(self, seq, *, lava=False, hp=12, x=5):
        return dict(seq=seq,event='end-client-tick',epochMs=1000+seq*50,
                    lava=lava,hp=hp,pos=[x,-60,.5])

    def test_transient_nearby_escape_survives_later_distant_sample(self):
        rows=[self.tick(1,lava=True,x=.5),self.tick(2,x=5),self.tick(3,x=20)]
        scn,ctx,calls,artifacts=self.specimen(rows)
        scn._observe_escape(ctx)
        self.assertTrue(ctx.geo['entered'])
        self.assertEqual(ctx.geo['escaped_pos'],(5,.5))
        self.assertEqual(artifacts[0][1]['tick']['seq'],2)
        self.assertTrue(scn.early_stop(ctx))
        self.assertEqual(scn.escape_sequence,3)

    def test_dry_start_or_dead_replacement_cannot_supply_escape(self):
        for rows in ([self.tick(1)],
                     [self.tick(1,lava=True,x=.5),self.tick(2,hp=0)],
                     [self.tick(1,lava=True,x=.5),self.tick(2,x=10.5)|{'pos':[10.5,-60,10.5]}]):
            with self.subTest(rows=rows):
                scn,ctx,calls,artifacts=self.specimen(rows)
                scn._observe_escape(ctx)
                self.assertNotIn('escaped_at',ctx.geo)
                self.assertEqual(artifacts,[])

    def test_original_nearby_radius_boundaries_remain_strict(self):
        scn=EscapeLavaPool()
        self.assertFalse(scn._nearby_escape_position(.5+3.3,.5))
        self.assertTrue(scn._nearby_escape_position(.5+4,.5))
        self.assertFalse(scn._nearby_escape_position(.5+9,.5))

    def test_gap_error_or_incomplete_tick_cannot_claim_escape(self):
        for rows in ([self.tick(2)], [{'error':'client timeout'}],
                     [self.tick(1)|{'lava':None}], [self.tick(1)|{'pos':[]}],
                     [self.tick(1)|{'hp':None}]):
            with self.subTest(rows=rows):
                scn,ctx,calls,artifacts=self.specimen(rows)
                with self.assertRaisesRegex(RuntimeError,'trace|snapshot'):
                    scn._observe_escape(ctx)
                self.assertNotIn('escaped_at',ctx.geo)

    def test_failed_observation_still_disables_trace_through_cleanup(self):
        scn,ctx,calls,artifacts=self.specimen([{'error':'timeout'}])
        with self.assertRaises(RuntimeError):
            scn._observe_escape(ctx)
        scn.cleanup(ctx)
        self.assertFalse(scn.escape_trace_enabled)
        self.assertEqual(calls[-1],('setMovementTrace',(False,)))

    def test_unacknowledged_enable_still_queues_disable_after_possible_late_enable(self):
        for outcome in (False, RuntimeError('lost acknowledgement')):
            with self.subTest(outcome=outcome):
                calls=[]
                def call(method, enabled):
                    calls.append((method,enabled))
                    if enabled:
                        if isinstance(outcome,Exception):
                            raise outcome
                        return outcome
                    return True
                ctx=SimpleNamespace(bot=SimpleNamespace(py=SimpleNamespace(call=call)))
                scn=EscapeLavaPool()
                with self.assertRaises(RuntimeError):
                    scn._start_escape_observation(ctx)
                scn.cleanup(ctx)
                self.assertEqual(calls,[('setMovementTrace',True),('setMovementTrace',False)])
                self.assertFalse(scn.escape_trace_enabled)


if __name__ == '__main__':
    unittest.main()
