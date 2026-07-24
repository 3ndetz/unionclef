package adris.altoclef.commands.worldedit;

import adris.altoclef.AltoClef;
import adris.altoclef.Py4jEntryPoint;

/**
 * `@@`-prefixed WorldEdit-like command handler — thin wrappers over the Py4jEntryPoint
 * worldedit primitives (select / fill / walls / hollow / cyl / sphere / replace /
 * buildBlocks), so the operator can drive them by typed commands like WorldEdit:
 *
 *   @@pos1 @@pos2      selection corners at the PLAYER's block
 *   @@hpos1 @@hpos2    selection corners at the LOOKED-AT (crosshair) block
 *   @@sel             clear selection
 *   @@set <block>     fill selection
 *   @@replace <from> <to>
 *   @@walls / @@hollow / @@cyl / @@sphere <block>
 *   @@cleanup         mine out nav scaffolding (pillar/bridge garbage)
 *   @@restat / @@minestat   poll //replace and mine progress
 *   @@schem / @@paste       (agent parses the file + drives buildBlocks — not a client file op)
 *
 * Called OFF the client thread (the primitives marshal back via onClientThread; calling
 * them ON the client thread would deadlock), with the player/crosshair block positions
 * pre-read on the client thread and passed in. Real survival placement (protection rules
 * apply). The prefix `@@` distances the many WE commands from the main `@` altoclef
 * commands (mirrors WorldEdit's `//`). ACCEPTANCE CRITERION for the WE handler (user 2026-07-24).
 */
public final class WorldEditCommands {

    // incremental selection corners (WorldEdit-style: set pos1 then pos2)
    private static int[] pos1 = null;
    private static int[] pos2 = null;

    private WorldEditCommands() {}

    /** @param playerBlock  the player's block pos (read on the client thread), may be null
     *  @param crosshairBlock the looked-at block pos (read on the client thread), may be null
     *  @return the underlying primitive's result map (surfaced to py4j we() for tests). */
    public static java.util.Map<String, Object> handle(AltoClef mod, String line, int[] playerBlock, int[] crosshairBlock) {
        Py4jEntryPoint api = mod.getInfoSender();
        if (api == null) return status(false, "not ready");
        String[] t = line.trim().isEmpty() ? new String[]{""} : line.trim().split("\\s+");
        String cmd = t[0].toLowerCase();
        try {
            switch (cmd) {
                case "pos1": return corner(mod, api, 1, playerBlock);
                case "pos2": return corner(mod, api, 2, playerBlock);
                case "hpos1":
                    if (crosshairBlock == null) return status(false, "no block in view");
                    return corner(mod, api, 1, crosshairBlock);
                case "hpos2":
                    if (crosshairBlock == null) return status(false, "no block in view");
                    return corner(mod, api, 2, crosshairBlock);
                case "sel": case "desel":
                    pos1 = null; pos2 = null; log(mod, "selection cleared"); return api.clearSelection();
                case "set":
                    api.undoSnapshot();
                    return logged(mod, "//set " + arg(t, 1), api.fillSelection(arg(t, 1)));
                case "replace": case "re":
                    api.undoSnapshot();
                    return logged(mod, "//replace " + arg(t, 1) + "->" + arg(t, 2), api.replaceSelection(arg(t, 1), arg(t, 2)));
                case "walls":
                    api.undoSnapshot();
                    return logged(mod, "//walls", api.wallsSelection(arg(t, 1)));
                case "hollow": case "faces":
                    api.undoSnapshot();
                    return logged(mod, "//hollow", api.hollowSelection(arg(t, 1)));
                case "cyl":
                    api.undoSnapshot();
                    return logged(mod, "//cyl", api.cylSelection(arg(t, 1)));
                case "sphere":
                    api.undoSnapshot();
                    return logged(mod, "//sphere", api.sphereSelection(arg(t, 1)));
                case "undo":
                    return logged(mod, "//undo", api.undoLast());
                case "undostat": case "ustat":
                    return logged(mod, "//undo status", api.undoStatus());
                case "restat": case "replacestat":
                    return logged(mod, "replaceStatus", api.replaceStatus());
                case "minestat":
                    return logged(mod, "mineStatus", api.mineStatus());
                case "cleanup": case "clearscaffold":
                    return logged(mod, "cleanup(" + api.scaffoldCount() + ")", api.cleanupScaffold());
                case "copy":
                    return logged(mod, "//copy", api.copySelection());
                case "paste":
                    return logged(mod, "//paste", api.pasteClipboard());
                case "size":
                    return logged(mod, "//size", api.selectionSize());
                case "schem":
                    if ("load".equalsIgnoreCase(arg(t, 1)))
                        return logged(mod, "//schem load " + arg(t, 2), api.loadSchem(arg(t, 2)));
                    log(mod, "usage: @@schem load <name>  (file in <gamedir>/schematics/)");
                    return status(false, "usage: @@schem load <name>");
                case "load":
                    return logged(mod, "//load " + arg(t, 1), api.loadSchem(arg(t, 1)));
                case "help": case "":
                    log(mod, "@@ WE: pos1 pos2 hpos1 hpos2 sel size | set<b> replace<f><t> walls<b> hollow<b> cyl<b> sphere<b> | copy paste undo | cleanup restat minestat | schem load");
                    return status(true, "help");
                default:
                    log(mod, "unknown @@ command: " + cmd + " (try @@help)");
                    return status(false, "unknown: " + cmd);
            }
        } catch (Exception e) {
            log(mod, "WE error: " + e.getMessage());
            return status(false, "error: " + e.getMessage());
        }
    }

    private static java.util.Map<String, Object> corner(AltoClef mod, Py4jEntryPoint api, int which, int[] b) {
        if (b == null) { log(mod, "no player position"); return status(false, "no player position"); }
        if (which == 1) pos1 = b; else pos2 = b;
        log(mod, "pos" + which + " = " + b[0] + "," + b[1] + "," + b[2]);
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("ok", true);
        out.put("pos" + which, b[0] + "," + b[1] + "," + b[2]);
        if (pos1 != null && pos2 != null) {
            java.util.Map<String, Object> sel = api.select(pos1[0], pos1[1], pos1[2], pos2[0], pos2[1], pos2[2]);
            log(mod, "selection set " + sel);
            out.put("selection", sel);
        }
        return out;
    }

    private static java.util.Map<String, Object> logged(AltoClef mod, String label, java.util.Map<String, Object> r) {
        log(mod, label + " " + r);
        return r;
    }

    private static java.util.Map<String, Object> status(boolean ok, String reason) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("ok", ok); m.put("reason", reason);
        return m;
    }

    private static String arg(String[] t, int i) { return i < t.length ? t[i] : ""; }

    private static void log(AltoClef mod, String s) { mod.log("[WE] " + s); }
}
