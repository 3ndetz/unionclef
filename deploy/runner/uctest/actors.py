"""uctest actors — Bot wrapper over one headless client container."""
import time

from .harness import Py4jClient, wait_for


class Bot:
    def __init__(self, container, name, rcon, log=print):
        self.container = container
        self.name = name
        self.rcon = rcon
        self.log = log
        self.py = Py4jClient(container)

    # -- lifecycle ---------------------------------------------------------
    def ensure_in_game(self, server="test-server", timeout=600, rcon=None):
        """Make sure the bot is in game ON `server`. When `rcon` is given it is
        the authority on presence: a bot logged into the OTHER stand server
        still reports inGame()=true, so scenarios that switch worlds (real-
        terrain chase on gamer-server) must verify against that server's
        player list, not just the client's own flag."""
        wait_for(f"{self.name} py4j", lambda: self.py.call("inGame") is not None,
                 timeout, 10, self.log)

        def on_target_server():
            if rcon is None:
                return bool(self.py.call("inGame"))
            try:
                return self.name in rcon.cmd("list")
            except Exception:  # noqa: BLE001 - server may still be booting
                return False

        if on_target_server():
            return
        self.py.call("ConnectToServer", server)
        wait_for(f"{self.name} in game on {server}", on_target_server,
                 240, 5, self.log)
        time.sleep(3)

    def ensure_alive(self, timeout=60):
        """A dead bot never moves and every metric after that is noise — one
        stand run measured a corpse lying on the ground for 3 minutes. Respawn
        it before any scenario starts."""
        if (self.health() or 0) > 0:
            return True
        self.log(f"  {self.name} is DEAD — respawning")
        t0 = time.time()
        while time.time() - t0 < timeout:
            self.py.try_call("respawnPlayer")
            time.sleep(3)
            if (self.health() or 0) > 0:
                self.log(f"  {self.name} respawned")
                return True
        raise RuntimeError(f"{self.name} stayed dead for {timeout}s")

    def reset_config(self):
        """Wipe the persisted tungsten.json back to shipped defaults.

        Any `;settings x y` rewrites the whole file, so a value saved once
        shadows every future default: the stand silently ran months-old combat
        tuning with ALL visualisation off, which is why the recorded clips
        showed no paths and a sluggish camera. Every run starts from defaults
        now, then pins only what it asserts on."""
        ok, res = self.py.try_call("resetTungstenConfig")
        if not ok:
            self.log(f"  WARN {self.name}: resetTungstenConfig unavailable ({res})")
            self.py.try_call("ChatMessage", ";settings reset")
        return res

    def stop_all(self):
        """Kill every driver a previous scenario could have left running.

        LOGGED, because this is also called from prepare() and therefore from any RE-prepare:
        a chase measured two Punking/PUNKSTOP pairs inside ONE scenario attempt, and the stop
        carried a direct py4j caller trace. If a mid-run call appears here — especially one for
        the OTHER actor — then the bench is ending the chase, not the bot."""
        self.log(f"  stop_all({self.name})")
        for m, args in (("ExecuteCommand", ("@stop",)), ("punkStop", ()),
                        ("runAwayStop", ()), ("stopPathing", ())):
            self.py.try_call(m, *args)

    def pin_settings(self, settings):
        """;settings k v for each — persisted tungsten.json may carry stale
        defaults (combatMovementsEnabled=false shipped for months); pin what
        the scenario depends on."""
        for k, v in settings.items():
            self.py.call("ChatMessage", f";settings {k} {v}")
            time.sleep(0.3)

    # -- state -------------------------------------------------------------
    def pos(self):
        return self.rcon.entity_pos(self.name)

    def health(self):
        return self.rcon.entity_float(self.name, "Health")

    def fresh_reset(self, spawn, kit=None, hard=True):
        """Put the bot in a known state at `spawn`.

        hard=True (arena worlds): kill -> respawn -> clear -> kit -> tp. The
        death also drops stale async pathfinder state from earlier scenarios.
        hard=False (real generated world): never kill — a survival respawn there
        is slow/unreliable (the bot can land far away in unloaded chunks and the
        health poll times out). Heal in place instead."""
        self.stop_all()
        self.ensure_alive()
        if not hard:
            self.rcon.cmd(f"effect clear {self.name}")
            self.rcon.cmd(f"effect give {self.name} instant_health 1 10 true")
            self.rcon.cmd(f"clear {self.name}")
            if kit:
                for item_cmd in kit:
                    self.rcon.cmd(item_cmd.format(name=self.name))
            self.rcon.cmd(f"tp {self.name} {spawn}")
            time.sleep(2)
            return
        # Personal respawn point INSIDE the arena. setworldspawn alone is not
        # enough: a player carries its own stored respawn position, so a bot
        # that dies mid-scenario reappeared at the world default (y=101) and
        # spent the rest of the run wandering there, poisoning every metric.
        parts = spawn.split()
        self.rcon.cmd(f"spawnpoint {self.name} "
                      f"{int(float(parts[0]))} {int(float(parts[1]))} {int(float(parts[2]))}")
        self.rcon.cmd(f"kill {self.name}")
        wait_for(f"{self.name} respawned",
                 lambda: (self.health() or 0) >= 19.9, 60, 3, self.log)
        self.rcon.cmd(f"effect clear {self.name}")
        self.rcon.cmd(f"clear {self.name}")
        if kit:
            for item_cmd in kit:
                self.rcon.cmd(item_cmd.format(name=self.name))
        self.rcon.cmd(f"tp {self.name} {spawn}")
        time.sleep(2)

    # -- convenience -------------------------------------------------------
    def cmd(self, altoclef_command):
        self.py.call("ExecuteCommand", altoclef_command)

    def chat(self, message):
        self.py.call("ChatMessage", message)

    def recent_chat(self, n=25):
        chat = self.py.call("getRecentChat", n)
        return [str(x) for x in (chat or [])]


# Standard kits: rcon command templates ({name} substituted).
# Sword only. A shield WAS added here to make the raise-between-swings logic testable, and
# the experiment is worth keeping in writing: with the shield in the off hand and the logic
# ON, melee collapsed from 15-19 landed swings a fight to ONE, trades 0:5 and 0:6. With the
# logic OFF but the shield still held, swings still fell to 6-9. Merely carrying it changes
# the baseline, so it is not left in the kit while the feature is disabled — put it back
# together with a fixed shield timing, and compare against these numbers.
KIT_SWORD = ["item replace entity {name} weapon.mainhand with iron_sword"]
KIT_BOW = ["item replace entity {name} weapon.mainhand with bow",
           "give {name} arrow 64"]
# explicit hotbar slots so the scenario can select deterministically:
# slot 0 = cobblestone (for bridgeTo — needs a block IN HAND), slot 1 = sword
KIT_BRIDGER = ["item replace entity {name} hotbar.0 with cobblestone 64",
               "item replace entity {name} hotbar.1 with iron_sword"]
KIT_ARCHER_DEF = ["item replace entity {name} weapon.mainhand with bow",
                  "give {name} arrow 64"]
