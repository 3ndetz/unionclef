package adris.altoclef.commands.worldedit;

import adris.altoclef.AltoClef;
import adris.altoclef.Py4jEntryPoint;

/**
 * `;;`-prefixed WorldEdit-like command handler for tungsten — thin wrappers over the
 * Py4jEntryPoint worldedit primitives (select / fill / walls / hollow / cyl / sphere /
 * replace / buildBlocks), so the operator can drive them by typed commands like WorldEdit:
 *
 *   ;;pos1 ;;pos2      selection corners at the PLAYER's block
 *   ;;hpos1 ;;hpos2    selection corners at the LOOKED-AT (crosshair) block
 *   ;;sel             clear selection
 *   ;;set <block>     fill selection
 *   ;;replace <from> <to>
 *   ;;walls / ;;hollow / ;;cyl / ;;sphere <block>
 *   ;;restat / ;;minestat   poll //replace and mine progress
 *   ;;schem / ;;paste       (agent parses the file + drives buildBlocks — not a client file op)
 *
 * Called OFF the client thread (the primitives marshal back via onClientThread; calling
 * them ON the client thread would deadlock), with the player/crosshair block positions
 * pre-read on the client thread and passed in. Real survival placement (protection rules
 * apply). The chosen prefix `;;` is distinct from `;` (tungsten movement/combat) and `@`
 * (altoclef). ACCEPTANCE CRITERION for the tungsten worldedit handler (user 2026-07-24).
 */
public final class WorldEditCommands {

    // incremental selection corners (WorldEdit-style: set pos1 then pos2)
    private static int[] pos1 = null;
    private static int[] pos2 = null;

    private WorldEditCommands() {}

    /** @param playerBlock  the player's block pos (read on the client thread), may be null
     *  @param crosshairBlock the looked-at block pos (read on the client thread), may be null */
    public static void handle(AltoClef mod, String line, int[] playerBlock, int[] crosshairBlock) {
        Py4jEntryPoint api = mod.getInfoSender();
        if (api == null) { log(mod, "not ready"); return; }
        String[] t = line.trim().isEmpty() ? new String[]{""} : line.trim().split("\\s+");
        String cmd = t[0].toLowerCase();
        try {
            switch (cmd) {
                case "pos1": corner(mod, api, 1, playerBlock); break;
                case "pos2": corner(mod, api, 2, playerBlock); break;
                case "hpos1":
                    if (crosshairBlock == null) { log(mod, "no block in view"); return; }
                    corner(mod, api, 1, crosshairBlock); break;
                case "hpos2":
                    if (crosshairBlock == null) { log(mod, "no block in view"); return; }
                    corner(mod, api, 2, crosshairBlock); break;
                case "sel": case "desel":
                    api.clearSelection(); pos1 = null; pos2 = null; log(mod, "selection cleared"); break;
                case "set":
                    log(mod, "//set " + arg(t, 1) + " -> " + api.fillSelection(arg(t, 1))); break;
                case "replace": case "re":
                    log(mod, "//replace " + arg(t, 1) + "->" + arg(t, 2) + " " + api.replaceSelection(arg(t, 1), arg(t, 2))); break;
                case "walls":
                    log(mod, "//walls " + api.wallsSelection(arg(t, 1))); break;
                case "hollow": case "faces":
                    log(mod, "//hollow " + api.hollowSelection(arg(t, 1))); break;
                case "cyl":
                    log(mod, "//cyl " + api.cylSelection(arg(t, 1))); break;
                case "sphere":
                    log(mod, "//sphere " + api.sphereSelection(arg(t, 1))); break;
                case "restat": case "replacestat":
                    log(mod, "replaceStatus " + api.replaceStatus()); break;
                case "minestat":
                    log(mod, "mineStatus " + api.mineStatus()); break;
                case "schem": case "paste": case "load":
                    log(mod, "schem/paste: parse the schematic file agent-side and drive buildBlocks(list) — no client-side file op yet"); break;
                case "help": case "":
                    log(mod, "WE: pos1 pos2 hpos1 hpos2 sel | set<b> replace<f><t> walls<b> hollow<b> cyl<b> sphere<b> | restat minestat"); break;
                default:
                    log(mod, "unknown ;; command: " + cmd + " (try ;;help)");
            }
        } catch (Exception e) {
            log(mod, "WE error: " + e.getMessage());
        }
    }

    private static void corner(AltoClef mod, Py4jEntryPoint api, int which, int[] b) {
        if (b == null) { log(mod, "no player position"); return; }
        if (which == 1) pos1 = b; else pos2 = b;
        log(mod, "pos" + which + " = " + b[0] + "," + b[1] + "," + b[2]);
        if (pos1 != null && pos2 != null) {
            api.select(pos1[0], pos1[1], pos1[2], pos2[0], pos2[1], pos2[2]);
            log(mod, "selection set (" + pos1[0] + "," + pos1[1] + "," + pos1[2] + " -> "
                    + pos2[0] + "," + pos2[1] + "," + pos2[2] + ")");
        }
    }

    private static String arg(String[] t, int i) { return i < t.length ? t[i] : ""; }

    private static void log(AltoClef mod, String s) { mod.log("[WE] " + s); }
}
