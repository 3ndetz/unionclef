# Elytra bench

Flight, water and pursuit checks of the mod against a real game: a vanilla 1.21.11 server on this
machine, one to three game clients with the mod, everything driven over the mod's MCP server and the
server's RCON. Videos come from the mod's own recorder (`recordStart`): the game window only,
1920x1080, 20 fps, no stall.

What it found so far is in `docs/features/ELYTRA_TUNGSTEN.md`.

## Set up

1. Server: put `server.jar` (1.21.11) into `server/` here (or `BENCH_SERVER_DIR`), make its
   `server.properties` from `server.properties.example`, accept the EULA, set `rcon.password`, put the
   same password into `rcon.secret` here (or `RCON_PASSWORD`). `./start-server.sh` (Java 21:
   `JAVA=...`).
2. Clients: start one to three game clients with the mod and join `127.0.0.1` (offline names are fine:
   the server is in offline mode). The first client's MCP server is on 25350, the next take 25351,
   25352. Name them in `BENCH_CLIENTS` if they are not `arcmlchikkk`, `pyhtun_lead`, `pyhtun_wing`.
3. For videos, put the path of an ffmpeg executable on the first line of
   `<game dir>/altoclef/recorder_ffmpeg.txt`. Without it the checks run unfilmed. (Not a setting and not
   a command argument: the MCP server listens on the LAN, `@set` reaches every setting, and a program
   path anyone with the token can change would let them run anything.)
4. Optional `local.json` here (git-ignored): `{"MC_SETTINGS": "...", "FFMPEG": "..."}`.

Python 3.10+, standard library only. Everything a run writes goes to `out/`.

## Run

```bash
python flighttests.py                 # all checks on the PI pilot
python flighttests.py --solver        # ... on tungsten's ElytraSolver
python flighttests.py rings long_1000 # some of them
python abtest.py                      # pilot against solver, flight by flight
python landtest.py 3                  # landings in survival: solver (LandingPlan) against pilot, 50/150/300 blocks
python landtest.py 2 --walls          # ... with a wall across the way in and posts round the spot
python physcheck.py                   # fly 300 blocks, check ElytraPhysics against the game per tick
python showcase.py solo|race|chase    # the show course (needs 1 / 3 / 3 clients), filmed in third person
python showcase.py follow             # pursuit: the bot on the solver, the third client on the pilot
python report.py out/storyboard.json out/report.mp4   # cards + captioned clips into one video
```

| check | what it asks |
|---|---|
| flat_hotbar, flat_backpack | take-off from flat ground; with the rockets only in the backpack too |
| long_500, long_1000 | land within 30 blocks of a point 500 / 1000 away; rockets and time |
| gazebo_edge | take-off right beside a roof with the course leading under it |
| under_canopy | under leaves: refuse the take-off and name open sky to walk to |
| low_forest | low-level flight along a line of tall trees |
| rings | six 7x7 rings at varied heights and sides, in order, under 3.2 blocks off centre |
| tank_swim | from the bottom of 16 blocks of water: up first, then out on the one bank level with the water |
| tank_hover | creative flight out of the same tank (the water surface is no roof) |
| py4j_bridge | a settings change (`@set`) leaves the py4j gateway listening |

The arena is built by the scripts on a flat world around the origin (a gazebo, a leaf canopy, a tree
line, a water tank, a rings lane at z 130, the show course at z 200); building is idempotent.
