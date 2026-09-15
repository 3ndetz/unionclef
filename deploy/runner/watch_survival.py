#!/usr/bin/env python3
"""Observe survival continuously without stopping the bot between video clips.

Defaults to observing the existing task on tester1. --start explicitly starts
@gamer; --connect explicitly joins gamer-server. Inventory, health and time of
day are never reset. Finishing a recording leaves the bot and its defence active.
Use --stop-on-exit only when intentionally ending the gameplay task as well.
"""
import argparse
import json
import time
from pathlib import Path

from gamer_smoke import rec_start, rec_stop
from uctest.harness import Py4jClient


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output-dir', type=Path, required=True)
    ap.add_argument('--tag', required=True)
    ap.add_argument('--seconds', type=int, default=600)
    ap.add_argument('--chunk-seconds', type=int, default=120)
    ap.add_argument('--start', action='store_true')
    ap.add_argument('--connect', action='store_true')
    ap.add_argument('--stop-on-exit', action='store_true')
    args = ap.parse_args()
    if args.seconds < 1 or args.chunk_seconds < 1:
        ap.error('recording durations must be positive')
    root = args.output_dir.resolve()
    root.mkdir(parents=True, exist_ok=True)
    p = Py4jClient('uctest-mc-tester1')
    if args.connect:
        p.call('ConnectToServer', 'gamer-server')
        time.sleep(3)
    for _ in range(45):
        if p.call('getGameState').get('self'):
            break
        time.sleep(1)
    else:
        raise RuntimeError('client is not in the world')

    rows = []
    index = 1
    recording = False
    started = time.monotonic()
    chunk_started = started
    last_shot = -20

    def finish_clip():
        nonlocal recording
        if recording:
            path = root / f'{args.tag}-{index:03d}.mp4'
            if rec_stop(str(path)) is None:
                raise RuntimeError(f'recorder did not produce {path}')
            recording = False

    try:
        rec_start(min(args.chunk_seconds, args.seconds) + 15)
        recording = True
        if args.start:
            p.call('setTungstenPathing', True)
            p.call('ExecuteCommand', '@gamer')
        while time.monotonic() - started < args.seconds:
            elapsed = round(time.monotonic() - started, 1)
            state = p.call('getGameState')
            row = {'t': elapsed, 'state': state,
                   'chain': p.call('getTaskChainString'),
                   'inventory': p.call('getInventoryFull'),
                   'performance': p.call('getPerfStats')}
            rows.append(row)
            temp = root / f'{args.tag}.json.tmp'
            temp.write_text(json.dumps(rows, ensure_ascii=False, indent=2))
            temp.replace(root / f'{args.tag}.json')
            print(json.dumps(row, ensure_ascii=False), flush=True)
            if elapsed - last_shot >= 20:
                p.screenshot(str(root / f'{args.tag}-{int(elapsed):04d}.png'))
                last_shot = elapsed
            if time.monotonic() - chunk_started >= args.chunk_seconds:
                finish_clip()
                index += 1
                remaining = args.seconds - (time.monotonic() - started)
                if remaining > 0:
                    rec_start(min(args.chunk_seconds, remaining) + 15)
                    recording = True
                    chunk_started = time.monotonic()
            time.sleep(5)
    finally:
        try:
            finish_clip()
        finally:
            if args.stop_on_exit:
                p.call('stopPathing')


if __name__ == '__main__':
    main()
