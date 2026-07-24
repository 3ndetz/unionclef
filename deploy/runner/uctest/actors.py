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
    def ensure_in_game(self, server="test-server", timeout=600):
        wait_for(f"{self.name} py4j", lambda: self.py.call("inGame") is not None,
                 timeout, 10, self.log)
        if not self.py.call("inGame"):
            self.py.call("ConnectToServer", server)
            wait_for(f"{self.name} in game", lambda: self.py.call("inGame"),
                     180, 5, self.log)
            time.sleep(3)

    def stop_all(self):
        """Kill every driver a previous scenario could have left running."""
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

    def fresh_reset(self, spawn, kit=None):
        """kill -> respawn -> clear -> kit -> tp. Clears leftover hp/effects
        AND stale async pathfinder state from earlier scenarios."""
        self.stop_all()
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
KIT_SWORD = ["item replace entity {name} weapon.mainhand with iron_sword"]
KIT_BOW = ["item replace entity {name} weapon.mainhand with bow",
           "give {name} arrow 64"]
# explicit hotbar slots so the scenario can select deterministically:
# slot 0 = cobblestone (for bridgeTo — needs a block IN HAND), slot 1 = sword
KIT_BRIDGER = ["item replace entity {name} hotbar.0 with cobblestone 64",
               "item replace entity {name} hotbar.1 with iron_sword"]
KIT_ARCHER_DEF = ["item replace entity {name} weapon.mainhand with bow",
                  "give {name} arrow 64"]
