# bw-150 near-void rising step (live, c3843ab2, 2026-11-27) -- kept for the next movement A/B

Source: UberPersona session receipts copied here (bw150-*.json, screenshots), exec-37c7befd-7a94-4c2c-94cf-c35ba451bf0a.

Sequence, as received:
1. Start: on oak leaves, pose (-1360.30, 11, 575.70), feet cell -1361,11,575, onGround. getOriginStatus:
   north and west `drop`, east and south `blocked`, up open (bw150-origin-pocket-current.json).
2. Placed: white_wool at -1361,11,574, anchored to polished_andesite -1360,11,574; wool 16 -> 15
   (bw150-escape-step-receipt.json).
3. gotoCell(-1361,12,574): succeeded, settled at (-1360.38384, 12, 574.4628) in 1.55 s -- on the wool,
   near its EAST edge, not centred.
4. gotoCell(-1360,13,574): the adjacent andesite ledge top (andesite at y10..12 in column -1360,574;
   y13 "no block returned"). Accepted; the avatar fell and was in spectator within 4.27 s. No operator
   input.

Columns read (levels from bw150-escape-map-all.json, x_first -1365, z_first 571; survey for y12/13):
- -1361,575: leaves y10, air y11..13 (the start).  -1361,576: leaves y10..12.
- -1360,575: andesite y10..12, air y13.  -1360,574: andesite y10..12 (the target column).
- -1362,575 and -1361,574 (before the wool): air y10..13 -- the void side.

Bench fixture to build (A/B, after the parity work):
- Rebuild those columns, the void to the west/north (no floor below for >= 20 blocks).
- STAGE AT (-1360.38384, 12, 574.4628) on the wool, NOT at the cell centre, and verify the pose.
- Then gotoCell(-1360,13,574) with exactly the live arguments; record a 50 ms pose/input trace
  (ladder_probe_inner.py style: pos, onGround, hColl, waypoint, walker, keys, jump) until arrival or fall.
- Gate: arrives in cell -1360,13,574 settled, never falls; repeat >= 5; A/B against c3843ab2.
