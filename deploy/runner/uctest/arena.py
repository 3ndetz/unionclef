"""uctest arenas — deterministic, self-evident polygons (RW-7).

All builders work in the flat test world: block floor top at y=-61, players
stand at y=-60. Void = air carved down to bedrock; below -64 is death.
Every fill is chunked per y-layer to stay under the vanilla 32768-block cap.
Markers: lime concrete = start/friendly side, red concrete = objective side.
"""

FLOOR_Y = -61          # the y of the block players stand ON
STAND_Y = -60          # the y players are teleported to
VOID_BOTTOM = -64


class ArenaBuilder:
    def __init__(self, rcon, log=print):
        self.rcon = rcon
        self.log = log

    def _fill(self, x1, y1, z1, x2, y2, z2, block):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            self.rcon.cmd(f"fill {x1} {y} {z1} {x2} {y} {z2} {block}")

    def prepare(self, half=40, regen=False):
        """Forceload + clear the arena cube + standard gamerules."""
        self.rcon.cmd(f"forceload add {-half} {-half} {half} {half}")
        self._fill(-half, VOID_BOTTOM, -half, half, -40, half, "air")
        # 1.21.11 renamed gamerules to snake_case, but repo tests contain both
        # spellings; send both — the wrong one just returns an error string.
        for rules, val in (
            (("pvp",), "true"),
            (("immediate_respawn", "immediateRespawn"), "true"),
            # 1.21.11 calls it advance_time. Both older spellings are rejected here, so before
            # this line the arena never actually stopped the clock -- it only looked like it,
            # because rejection is tolerated below and merely prints a note.
            (("advance_time", "do_daylight_cycle", "doDaylightCycle"), "false"),
            (("keep_inventory", "keepInventory"), "true"),
            # 1.21.11 spells it natural_health_regeneration -- verified against the live
            # server, which answers "currently set to" for that name and rejects both
            # natural_regeneration and naturalRegeneration. An earlier version of this comment
            # had it backwards and called the working name "not a gamerule in EITHER scheme".
            # It is the one every HP criterion depends on ("took no damage", "survived with
            # >= 8 hp", every hp_drop number), so which name lands is not a detail.
            (("natural_regeneration", "natural_health_regeneration", "naturalRegeneration"),
             "true" if regen else "false"),
            # AND THIS ONE WAS NEVER LANDING EITHER, WHICH IS WORSE.
            # On 1.21.11 the rule is spawn_monsters; do_mob_spawning and doMobSpawning are both
            # rejected. So every nav and pvp course has been running with monsters spawning into
            # the arena -- found while a mob probe kept counting 11 to 27 zombies in a world it
            # believed it had silenced. Courses are short and lit, so the damage is mostly noise
            # rather than wrong verdicts, but "mostly" is not a thing a bench should rely on.
            (("spawn_monsters", "do_mob_spawning", "doMobSpawning"), "false"),
        ):
            # Deliberately tolerant: each tuple carries one spelling per MC generation and the
            # others are EXPECTED to be rejected, so rejection is not an error HERE. It must not
            # be promoted to one either: "pvp" is in this list and is not a gamerule at all (it
            # lives in server.properties), so a hard failure on "no spelling worked" takes down
            # every arena build. Everything OUTSIDE this loop still raises on rejection.
            accepted = [r for r in rules
                        if "Unknown" not in self.rcon.cmd(f"gamerule {r} {val}",
                                                          allow_reject=True)]
            if not accepted:
                print(f"  note: no accepted spelling for gamerule {rules} = {val}", flush=True)
        self.rcon.cmd("time set day")
        self.rcon.cmd("weather clear")

    # -- primitives --------------------------------------------------------
    def floor(self, x1, z1, x2, z2, block="stone"):
        self._fill(x1, FLOOR_Y, z1, x2, FLOOR_Y, z2, block)

    def rim_wall(self, x1, z1, x2, z2, height=3, block="barrier"):
        """A wall around the rim so combat knockback can't fling a bot off the
        floor into the surrounding void (which respawns it at world spawn and
        ruins the run). Used by flat combat/chase arenas, NOT by void/edge
        arenas where falling is the metric."""
        for y in range(FLOOR_Y + 1, FLOOR_Y + 1 + height):
            self.rcon.cmd(f"fill {x1} {y} {z1} {x2} {y} {z1} {block}")
            self.rcon.cmd(f"fill {x1} {y} {z2} {x2} {y} {z2} {block}")
            self.rcon.cmd(f"fill {x1} {y} {z1} {x1} {y} {z2} {block}")
            self.rcon.cmd(f"fill {x2} {y} {z1} {x2} {y} {z2} {block}")

    def set_spawn(self, x, y, z):
        self.rcon.cmd(f"setworldspawn {int(x)} {int(y)} {int(z)}")

    def island(self, cx, cz, half, block="stone"):
        self.floor(cx - half, cz - half, cx + half, cz + half, block)

    def bridge_x(self, x1, x2, cz, width, block="oak_planks"):
        """Bridge along +x centred on z=cz. width 1 => the bedwars walkway."""
        hw = (width - 1) // 2
        self.floor(x1, cz - hw, x2, cz + hw + (width - 1) % 2, block)

    def marker(self, x, z, color):
        self.rcon.cmd(f"setblock {x} {FLOOR_Y} {z} {color}_concrete")

    # -- composite polygons ------------------------------------------------
    def flat_field(self, half=20, grass=False, wall=True):
        self.floor(-half, -half, half, half, "grass_block" if grass else "stone")
        if grass:
            self._fill(2, STAND_Y, -4, 6, STAND_Y, 4, "short_grass")
            self._fill(8, STAND_Y, -2, 10, STAND_Y, 2, "tall_grass")
        if wall:
            self.rim_wall(-half, -half, half, half)

    def edge_platform(self, half=2):
        """Tiny island over void — RW-1 'fight 1 block from the drop'."""
        self.island(0, 0, half)

    def two_islands(self, gap=9, island_half=3, bridge_width=0):
        """Islands at -x and +x. bridge_width 0 = no bridge (assault builds it).
        Returns (spawnA, spawnB, edgeA_x, edgeB_x)."""
        ax2 = -(gap + 1) // 2                      # inner edge x of island A
        bx1 = ax2 + gap + 1                        # inner edge x of island B
        self.island(ax2 - island_half, 0, island_half)
        self.island(bx1 + island_half, 0, island_half)
        if bridge_width > 0:
            self.bridge_x(ax2 + 1, bx1 - 1, 0, bridge_width)
        self.marker(ax2 - island_half, -island_half, "lime")
        self.marker(bx1 + island_half, island_half, "red")
        a = f"{ax2 - island_half}.5 {STAND_Y} 0.5 -90 0"
        b = f"{bx1 + island_half}.5 {STAND_Y} 0.5 90 0"
        return a, b, ax2, bx1

    # Deterministic terrain strip for the chase bench (RW-9). A fixed feature
    # table, 3-block segments along +x, width 5 (z -2..2). Max step +1, gaps
    # sprint-jumpable, trenches climbable — passable with NO block placing.
    TERRAIN = ["flat", "+1", "flat", "gap1", "flat", "+1", "+1", "flat",
               "-1", "gap2", "flat", "+1", "-1", "flat", "+1", "flat"]

    def terrain_strip(self, x_start=6):
        h = FLOOR_Y
        x = x_start
        for feat in self.TERRAIN:
            if feat.startswith("+"):
                h += int(feat[1:])
            elif feat.startswith("-"):
                h -= int(feat[1:])
            if feat.startswith("gap"):
                width = int(feat[3:])
                # trench: floor 2 lower, climb-out step at the far wall
                self._fill(x, h - 2, -2, x + width - 1, h - 2, 2, "stone")
                self._fill(x, h - 1, -2, x + width - 1, h, 2, "air")
                self.rcon.cmd(f"setblock {x + width - 1} {h - 1} 0 stone")
                x += width
                continue
            self._fill(x, FLOOR_Y - 3, -2, x + 2, h, 2, "stone")
            x += 3
        end_x = x - 1
        self.marker(x_start - 1, 0, "lime")
        self.marker(end_x + 1, 0, "red")
        return x_start, end_x, h - FLOOR_Y  # start x, end x, end height offset
