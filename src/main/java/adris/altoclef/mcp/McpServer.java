package adris.altoclef.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import adris.altoclef.Debug;
import adris.altoclef.Py4jEntryPoint;

/**
 * MCP server (Model Context Protocol over Streamable HTTP) hosted directly by
 * the mod. A cognitive agent (Claude) connects over the LAN
 * (http://&lt;lan-ip&gt;:port/mcp) and drives the bot through the SAME
 * Py4jEntryPoint levers — single source of truth, no py4j hop, no docker-exec.
 *
 * Transport: JSON-RPC 2.0 over a single POST endpoint (/mcp). Requests get an
 * application/json response; notifications get 202. Implements initialize,
 * tools/list, tools/call, ping. Tools are a curated set of agent levers with
 * descriptions + JSON schemas (see docs/features/AGENT_PY4J_LEVERS.md).
 *
 * The HTTP handler runs off-thread; each lever internally marshals to the
 * client thread as needed (onClientThread), so calling them here is safe.
 */
public class McpServer {

    private static final Gson GSON = new Gson();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final Py4jEntryPoint api;
    private final List<Tool> tools = new ArrayList<>();
    private HttpServer http;

    private static final class Tool {
        final String name, description;
        final JsonObject inputSchema;
        final Function<JsonObject, Object> handler;
        Tool(String n, String d, JsonObject s, Function<JsonObject, Object> h) {
            name = n; description = d; inputSchema = s; handler = h;
        }
    }

    public McpServer(Py4jEntryPoint api) {
        this.api = api;
        registerTools();
    }

    public void start(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        http.createContext("/mcp", this::handle);
        http.createContext("/", this::handle); // tolerate clients that POST to root
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
    }

    public void stop() {
        if (http != null) { http.stop(0); http = null; }
    }

    // ---- HTTP ----

    private void handle(HttpExchange ex) {
        try {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) { ex.sendResponseHeaders(204, -1); return; }
            if ("GET".equals(method)) { ex.sendResponseHeaders(405, -1); return; } // no server->client push
            if (!"POST".equals(method)) { ex.sendResponseHeaders(405, -1); return; }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(body);
            JsonElement respElem;
            if (parsed.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement e : parsed.getAsJsonArray()) {
                    JsonObject r = handleRpc(e.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                respElem = out.size() == 0 ? null : out;
            } else {
                respElem = handleRpc(parsed.getAsJsonObject());
            }

            if (respElem == null) { ex.sendResponseHeaders(202, -1); return; } // notification(s)
            byte[] data = GSON.toJson(respElem).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
        } catch (Exception e) {
            Debug.logInternal("MCP handler error: " + e);
            try { ex.sendResponseHeaders(500, -1); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    // ---- JSON-RPC ----

    private JsonObject handleRpc(JsonObject req) {
        JsonElement id = req.get("id");
        if (id == null || id.isJsonNull()) return null; // notification — no reply
        String method = req.has("method") ? req.get("method").getAsString() : "";
        try {
            JsonObject result;
            switch (method) {
                case "initialize":   result = initializeResult(); break;
                case "ping":         result = new JsonObject(); break;
                case "tools/list":   result = toolsListResult(); break;
                case "tools/call":   result = toolsCallResult(req.getAsJsonObject("params")); break;
                default:             return error(id, -32601, "Method not found: " + method);
            }
            JsonObject resp = new JsonObject();
            resp.addProperty("jsonrpc", "2.0");
            resp.add("id", id);
            resp.add("result", result);
            return resp;
        } catch (Exception e) {
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonObject initializeResult() {
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", "unionclef");
        info.addProperty("version", "1.0.0");
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", PROTOCOL_VERSION);
        r.add("capabilities", caps);
        r.add("serverInfo", info);
        r.addProperty("instructions",
                "Levers to drive a Minecraft bot as a cognitive agent. Loop: getGameState (see) "
                + "-> gotoXYZ/pathStatus (move) -> combat/build/menu (act). You decide strategy; "
                + "the mod executes mechanics.");
        return r;
    }

    private JsonObject toolsListResult() {
        JsonArray arr = new JsonArray();
        for (Tool t : tools) {
            JsonObject to = new JsonObject();
            to.addProperty("name", t.name);
            to.addProperty("description", t.description);
            to.add("inputSchema", t.inputSchema);
            arr.add(to);
        }
        JsonObject r = new JsonObject();
        r.add("tools", arr);
        return r;
    }

    private JsonObject toolsCallResult(JsonObject params) {
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        for (Tool t : tools) {
            if (t.name.equals(name)) {
                boolean isError = false;
                String text;
                try {
                    Object result = t.handler.apply(args);
                    text = GSON.toJson(result);
                } catch (Exception e) {
                    isError = true;
                    text = "Tool error: " + e.getMessage();
                }
                return contentResult(text, isError);
            }
        }
        return contentResult("Unknown tool: " + name, true);
    }

    private JsonObject contentResult(String text, boolean isError) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(content);
        JsonObject r = new JsonObject();
        r.add("content", arr);
        r.addProperty("isError", isError);
        return r;
    }

    private JsonObject error(JsonElement id, int code, String msg) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", msg);
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("error", err);
        return resp;
    }

    // ---- tool registry (single source: wraps Py4jEntryPoint levers) ----

    private void tool(String name, String desc, JsonObject schema, Function<JsonObject, Object> h) {
        tools.add(new Tool(name, desc, schema, h));
    }

    private void registerTools() {
        // perception
        tool("getGameState",
                "Battle perception (the agent's eyes): self(hp/maxHp/armor/pos/onGround/held/blocks), "
                + "players[](name/pos/distance/hp/sprinting), beds[](nearby beds). Read before deciding tactics.",
                schema(), a -> api.getGameState());
        tool("inventorySpace",
                "Free inventory slots + block counts by type (resource planning before bridging/building).",
                schema(), a -> api.inventorySpace());
        tool("getOpenScreen",
                "Read the open menu (title + every slot: item id / display name / count) — shop or chest.",
                schema(), a -> api.getOpenScreen());
        tool("getRecentChat",
                "Last n chat messages (event/error markers).",
                schema("n:int"), a -> api.getRecentChat(argInt(a, "n")));
        tool("canReach",
                "Heuristic: can we path to a cell (optionally breaking blocks)? Returns reached/pathSize/breaks/endDistance.",
                schema("x:int", "y:int", "z:int", "withBreaking:bool"),
                a -> api.canReach(argInt(a, "x"), argInt(a, "y"), argInt(a, "z"), argBool(a, "withBreaking")));

        // movement
        tool("gotoXYZ",
                "Navigate to a world coordinate via the tungsten pathfinder (walk/parkour/bridge). "
                + "Fire-and-poll: then call pathStatus repeatedly until arrived.",
                schema("x:int", "y:int", "z:int"),
                a -> api.gotoXYZ(argInt(a, "x"), argInt(a, "y"), argInt(a, "z")));
        tool("pathStatus",
                "Poll the current navigation: busy/pos/distance-to-goal/arrived(<1.5). Loop after gotoXYZ until arrived.",
                schema(), a -> api.pathStatus());
        tool("gotoFar",
                "Navigate toward a FAR target with a receding horizon (huge goals freeze the pathfinder). "
                + "Advances one segment (<= horizon blocks) toward the target. Loop: gotoFar -> pathStatus "
                + "until arrived -> gotoFar, until finalSegment=true.",
                schema("x:int", "y:int", "z:int", "horizon:int"),
                a -> api.gotoFar(argInt(a, "x"), argInt(a, "y"), argInt(a, "z"), argInt(a, "horizon")));
        tool("stopPathing",
                "Stop all navigation and tasks (tungsten ;stop + altoclef @stop).",
                schema(), a -> api.stopPathing());
        tool("setTungstenPathing",
                "Toggle routing altoclef/shredder navigation through tungsten's physics (useTungsten + "
                + "experimentalPathfinding). On = @goto/@get/@gamer delegate qualifying segments to tungsten.",
                schema("on:bool"), a -> api.setTungstenPathing(argBool(a, "on")));
        tool("pathingMode", "Read the current pathing-delegation flags.", schema(), a -> api.pathingMode());
        tool("bridgeTo",
                "Godbridge toward a target coordinate (continuous pave-ahead). Needs a block in hand (selectHotbar first).",
                schema("x:int", "y:int", "z:int"),
                a -> booleanMap(api.bridgeTo(argInt(a, "x"), argInt(a, "y"), argInt(a, "z"))));

        // combat
        tool("mouseClick",
                "Click the mouse: 'left' (attack), 'right' (use), 'middle'. Anti-cheat-safe input pipeline.",
                schema("button:string"), a -> api.mouseClick(argStr(a, "button")));
        tool("shootArrowAt",
                "Shoot a bow at a player with computed ballistic trajectory + target lead.",
                schema("player:string"), a -> booleanMap(api.shootArrowAt(argStr(a, "player"))));
        tool("shieldBlock",
                "Raise the shield for N ticks (blocks arrows/hits).",
                schema("ticks:int"), a -> booleanMap(api.shieldBlock(argInt(a, "ticks"))));
        tool("punk",
                "Hunt one player by name via the tungsten combat engine (A* approach + melee aura).",
                schema("name:string"), a -> api.punk(argStr(a, "name")));
        tool("punkAny",
                "Multi-target hunt: attack the NEAREST player in `allow` (empty = any player), never hitting "
                + "anyone in `avoid`. Auto re-targets. You pick who to fight; the mod executes.",
                schema("allow:strarray", "avoid:strarray"),
                a -> api.punkAny(strList(a, "allow"), strList(a, "avoid")));
        tool("punkAvoid",
                "Update the avoid-list mid-fight (never hit these players).",
                schema("avoid:strarray"), a -> api.punkAvoid(strList(a, "avoid")));
        tool("punkStop", "Stop the tungsten combat engine.", schema(), a -> api.punkStop());
        tool("punkStatus", "Combat status: active + the player currently being fought (null if none).",
                schema(), a -> api.punkStatus());
        tool("runAwayPlayer",
                "Flee a player, keeping at least `distance` blocks (min 3). Mirror of punk — tungsten paths to "
                + "the safest reachable point AWAY from the threat and never backs off into the void. Stops any punk.",
                schema("name:string", "distance:int"),
                a -> api.runAwayPlayer(argStr(a, "name"), argInt(a, "distance")));
        tool("runAwayStop", "Stop fleeing.", schema(), a -> api.runAwayStop());
        tool("runAwayStatus", "Flee status: active + the threat currently tracked (null if none).",
                schema(), a -> api.runAwayStatus());

        // building + WorldEdit
        tool("placeBlockAt",
                "Place a block at a cell (aims at a supporting face, interactBlock). Must be within reach; equip a block first.",
                schema("x:int", "y:int", "z:int"),
                a -> api.placeBlockAt(argInt(a, "x"), argInt(a, "y"), argInt(a, "z")));
        tool("select",
                "Set a WorldEdit region (inclusive corners, any order). Shows a yellow selection box.",
                schema("x1:int", "y1:int", "z1:int", "x2:int", "y2:int", "z2:int"),
                a -> api.select(argInt(a, "x1"), argInt(a, "y1"), argInt(a, "z1"),
                                argInt(a, "x2"), argInt(a, "y2"), argInt(a, "z2")));
        tool("clearSelection", "Clear the WorldEdit selection.", schema(), a -> api.clearSelection());
        tool("fillSelection",
                "//set — fill the selection with a block (reachable cells, bottom-up, cap per call). Equips the named "
                + "block from the hotbar. If remaining>0, reposition (gotoXYZ) and call again.",
                schema("block:string"), a -> api.fillSelection(argStr(a, "block")));
        tool("wallsSelection",
                "//walls — fill only the 4 vertical walls of the selection (hollow interior).",
                schema("block:string"), a -> api.wallsSelection(argStr(a, "block")));
        tool("hollowSelection",
                "//hollow — fill the 6-face SHELL of the selection (walls + floor + ceiling), interior open.",
                schema("block:string"), a -> api.hollowSelection(argStr(a, "block")));
        tool("cylSelection",
                "//cyl — fill the solid cylinder inscribed in the selection (circle in XZ that fits the box, all Y).",
                schema("block:string"), a -> api.cylSelection(argStr(a, "block")));
        tool("sphereSelection",
                "//sphere — fill the solid sphere/ellipsoid inscribed in the selection (radii = the 3 half-extents).",
                schema("block:string"), a -> api.sphereSelection(argStr(a, "block")));
        tool("mineBlock",
                "Mine the block at (x,y,z) if in reach (tungsten break queue: tool equip + protection "
                + "+ gravity re-mine apply). Poll mineStatus; reposition (gotoXYZ) for out-of-reach.",
                schema("x:int", "y:int", "z:int"),
                a -> api.mineBlocks(java.util.List.of(java.util.List.of(argInt(a, "x"), argInt(a, "y"), argInt(a, "z")))));
        tool("mineStatus",
                "Poll the break queue: {mining, remaining}. mining=false && remaining=0 -> done.",
                schema(), a -> api.mineStatus());
        tool("cleanupScaffold",
                "Mine out the tungsten NAV scaffolding (pillar-up / bridge blocks it placed to reach a "
                + "goal) — the garbage around a build. Top-down, finite, can't loop. Poll mineStatus; "
                + "reposition (gotoXYZ) for out-of-reach.",
                schema(), a -> api.cleanupScaffold());
        tool("copySelection",
                "//copy — snapshot the non-air blocks of the selection into a clipboard (offsets from min).",
                schema(), a -> api.copySelection());
        tool("pasteClipboard",
                "//paste — place the clipboard at the player's block position. Poll buildBlocks-style remaining.",
                schema(), a -> api.pasteClipboard());
        tool("selectionSize",
                "//size — the selection's min/max/size/volume and clipboard count.",
                schema(), a -> api.selectionSize());
        tool("undoLast",
                "//undo — restore the last snapshot (auto-taken before set/replace/walls/hollow/cyl/sphere): "
                + "breaks the region then rebuilds the snapshot. Poll undoStatus.",
                schema(), a -> api.undoLast());
        tool("undoStatus",
                "Poll //undo: phase breaking -> placing -> done.",
                schema(), a -> api.undoStatus());
        tool("replaceSelection",
                "//replace — swap every selection cell that is `from` (or \"*\"/\"any\" = any non-air) for "
                + "`to`. Two phases: breaks the matching cells, then poll replaceStatus which places `to`. "
                + "Real survival placement. Reposition (gotoXYZ) if remaining stalls out of reach.",
                schema("from:string", "to:string"),
                a -> api.replaceSelection(argStr(a, "from"), argStr(a, "to")));
        tool("replaceStatus",
                "Poll //replace: phase = breaking -> placing -> done, with placed/remaining counts.",
                schema(), a -> api.replaceStatus());
        tool("buildBlocks",
                "Schematic placement: blocks = JSON array of [x,y,z,name] (world coords), placed bottom-up "
                + "in reach. Reposition (gotoXYZ) for `remaining`, call again. Parse a schematic into this "
                + "list; build-order + material sourcing are the agent's job.",
                schema("blocks:string"),
                a -> {
                    try {
                        com.google.gson.JsonArray arr = com.google.gson.JsonParser
                                .parseString(argStr(a, "blocks")).getAsJsonArray();
                        java.util.List<Object> blocks = new java.util.ArrayList<>();
                        for (com.google.gson.JsonElement e : arr) {
                            com.google.gson.JsonArray b = e.getAsJsonArray();
                            blocks.add(java.util.List.of(b.get(0).getAsInt(), b.get(1).getAsInt(),
                                    b.get(2).getAsInt(), b.get(3).getAsString()));
                        }
                        return api.buildBlocks(blocks);
                    } catch (Exception ex) {
                        return java.util.Map.of("ok", false, "reason", "bad blocks JSON: " + ex.getMessage());
                    }
                });
        tool("buildDefenseAround",
                "Box a cell with a protective shell (sides + roof) — e.g. defend a bed. Covers reachable cells; "
                + "reposition for the rest.",
                schema("x:int", "y:int", "z:int"),
                a -> api.buildDefenseAround(argInt(a, "x"), argInt(a, "y"), argInt(a, "z")));
        tool("canBreakBlock",
                "Predict whether the mod is allowed to mine a cell (protection policy: deny-list/zones, "
                + "claims, altoclef break-avoiders). Check before mining.",
                schema("x:int", "y:int", "z:int"),
                a -> booleanMap(api.canBreakBlock(argInt(a, "x"), argInt(a, "y"), argInt(a, "z"))));
        tool("canPlaceBlock",
                "Predict whether the mod is allowed to place at a cell (protection policy + replaceable). "
                + "Returns canPlace/policyAllows/replaceable. Check before building.",
                schema("x:int", "y:int", "z:int"),
                a -> api.canPlaceBlock(argInt(a, "x"), argInt(a, "y"), argInt(a, "z")));
        tool("markProtectedArea",
                "Mark a claim/private the mod must NOT build or mine in — a cube of radius r around (x,y,z). "
                + "The bot then routes and builds around it (baritone claim convention).",
                schema("x:int", "y:int", "z:int", "r:int"),
                a -> api.markProtectedArea(argInt(a, "x"), argInt(a, "y"), argInt(a, "z"), argInt(a, "r")));
        tool("clearProtectedAreas",
                "Clear all runtime protected areas (place + break deny zones).",
                schema(), a -> api.clearProtectedAreas());

        // menus / shop / hotbar
        tool("selectHotbar", "Select hotbar slot 0-8.", schema("slot:int"),
                a -> booleanMap(api.selectHotbar(argInt(a, "slot"))));
        tool("clickMenuByName",
                "Click a menu slot by item display name (shop purchase / hub navigation). names = candidate labels "
                + "(first match wins), button 0=left/1=right, action e.g. 'PICKUP', timeoutMs to retry the menu read.",
                schema("names:strarray", "button:int", "action:string", "timeoutMs:int"),
                a -> {
                    List<String> ns = new ArrayList<>();
                    for (JsonElement e : a.getAsJsonArray("names")) ns.add(e.getAsString());
                    return intMap(api.clickMenuByName(ns, argInt(a, "button"), argStr(a, "action"), argInt(a, "timeoutMs")));
                });

        // commands / chat
        tool("ExecuteCommand",
                "Run an altoclef command (e.g. '@goto 10 64 20', '@get log 3', '@game', '@stop').",
                schema("cmd:string"), a -> { api.ExecuteCommand(argStr(a, "cmd")); return okMap(); });
        tool("ChatMessage",
                "Send chat / a tungsten command (';goto x y z', ';bridge', ';stop') or plain chat text.",
                schema("msg:string"), a -> { api.ChatMessage(argStr(a, "msg")); return okMap(); });
        tool("ConnectToServer",
                "Connect to a Minecraft server by address (e.g. 'mc.example.top' or 'test-server').",
                schema("ip:string"), a -> { api.ConnectToServer(argStr(a, "ip")); return okMap(); });
        tool("inGame", "Whether the bot is currently in a world (bool).", schema(),
                a -> booleanMap(api.inGame()));
    }

    // ---- helpers ----

    private static java.util.Map<String, Object> okMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("ok", true);
        return m;
    }
    private static java.util.Map<String, Object> booleanMap(boolean b) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("ok", b);
        return m;
    }
    private static java.util.Map<String, Object> intMap(int v) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("result", v);
        return m;
    }

    private static int argInt(JsonObject a, String k) { return a.get(k).getAsInt(); }
    private static String argStr(JsonObject a, String k) { return a.get(k).getAsString(); }
    private static boolean argBool(JsonObject a, String k) { return a.get(k).getAsBoolean(); }
    private static List<String> strList(JsonObject a, String k) {
        List<String> out = new ArrayList<>();
        if (a.has(k) && a.get(k).isJsonArray()) for (JsonElement e : a.getAsJsonArray(k)) out.add(e.getAsString());
        return out;
    }

    /** Build a JSON-Schema object from "name:type" pairs (int/string/bool/strarray). */
    private static JsonObject schema(String... pairs) {
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (String p : pairs) {
            String[] kv = p.split(":");
            JsonObject prop = new JsonObject();
            switch (kv[1]) {
                case "int":    prop.addProperty("type", "integer"); break;
                case "bool":   prop.addProperty("type", "boolean"); break;
                case "strarray":
                    prop.addProperty("type", "array");
                    JsonObject items = new JsonObject();
                    items.addProperty("type", "string");
                    prop.add("items", items);
                    break;
                default:       prop.addProperty("type", "string");
            }
            props.add(kv[0], prop);
            required.add(kv[0]);
        }
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", props);
        s.add("required", required);
        return s;
    }
}
