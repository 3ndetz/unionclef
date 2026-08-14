package adris.altoclef;


import adris.altoclef.butler.Butler;
import adris.altoclef.chains.*;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.commandsystem.TabCompleter;
import adris.altoclef.control.InputControls;
import adris.altoclef.control.PlayerExtraController;
import adris.altoclef.control.SlotHandler;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBrokenEvent;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.eventbus.events.ClientRenderEvent;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TitleScreenEntryEvent;
import adris.altoclef.multiversion.DrawContextWrapper;
import adris.altoclef.multiversion.RenderLayerVer;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.*;
import adris.altoclef.trackers.BlockScanner;
import adris.altoclef.trackers.DamageTracker;
import adris.altoclef.trackers.storage.ContainerSubTracker;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.ui.AltoClefTickChart;
import adris.altoclef.ui.CommandStatusOverlay;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.ui.MessageSender;
import adris.altoclef.util.helpers.InputHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import adris.altoclef.mixins.MinecraftClientSessionMixin;
import adris.altoclef.util.helpers.ConfigHelper;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.session.Session;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;
import py4j.GatewayServer;
import py4j.Py4JNetworkException;

import java.util.*;
import java.util.function.Consumer;

/**
 * Central access point for AltoClef
 */
public class AltoClef implements ModInitializer {

    // Static access to altoclef
    private static final Queue<Consumer<AltoClef>> _postInitQueue = new ArrayDeque<>();

    // Camera modifier statics (used by CameraMixin / EpicCamera).
    // Kept fully qualified because this file also imports baritone types; the rotation itself is
    // tungsten's now — same (yaw, pitch) value type, it never crossed into the pathfinder.
    public static kaptainwutax.tungsten.path.movements.Rotation _cameraRotationModifer = null;
    public static net.minecraft.util.math.Vec3d _cameraPositionModifer = null;

    public static kaptainwutax.tungsten.path.movements.Rotation getCameraRotationModifer() {
        return _cameraRotationModifer;
    }
    public static void setCameraRotationModifer(kaptainwutax.tungsten.path.movements.Rotation rotation) {
        _cameraRotationModifer = rotation;
    }
    public static void resetCameraRotationModifer() {
        _cameraRotationModifer = null;
    }
    public static net.minecraft.util.math.Vec3d getCameraPositionModifer() {
        return _cameraPositionModifer;
    }
    public static void setCameraPositionModifer(net.minecraft.util.math.Vec3d pos) {
        _cameraPositionModifer = pos;
    }
    public static void resetCameraPositionModifer() {
        _cameraPositionModifer = null;
    }

    // Central Managers
    private static CommandExecutor commandExecutor;
    private TaskRunner taskRunner;
    private TrackerManager trackerManager;
    private BotBehaviour botBehaviour;
    private PlayerExtraController extraController;
    // Task chains
    private UserTaskChain userTaskChain;
    private FoodChain foodChain;
    private MobDefenseChain mobDefenseChain;
    private MLGBucketFallChain mlgBucketChain;
    private WorldSurvivalChain worldSurvivalChain;
    // Trackers
    private ItemStorageTracker storageTracker;
    private ContainerSubTracker containerSubTracker;
    private EntityTracker entityTracker;
    private BlockScanner blockScanner;
    private SimpleChunkTracker chunkTracker;
    private MiscBlockTracker miscBlockTracker;
    private CraftingRecipeTracker craftingRecipeTracker;
    private DamageTracker damageTracker;
    // Renderers
    private CommandStatusOverlay commandStatusOverlay;
    private AltoClefTickChart altoClefTickChart;
    // Settings
    private adris.altoclef.Settings settings;
    // Misc managers/input
    private MessageSender messageSender;
    private InputControls inputControls;
    private SlotHandler slotHandler;
    // Butler
    private Butler butler;
    // Pausing
    private boolean paused = false;
    private Task storedTask;

    // Task timeout
    private boolean _timeoutActive = false;
    private float _timeoutDuration = 60;
    private long _timeoutStartMs;

    private static AltoClef instance;

    // Pipeline (multiplayer game mode)
    private static adris.altoclef.util.agent.Pipeline _pipeline = adris.altoclef.util.agent.Pipeline.None;

    // Py4j bridge
    private Py4jEntryPoint _py4jEntryPoint = null;
    private GatewayServer _gatewayServer = null;
    // MCP server (LAN-hosted control surface over the same levers)
    private adris.altoclef.mcp.McpServer _mcpServer = null;

    public static adris.altoclef.util.agent.Pipeline getPipeline() {
        return _pipeline;
    }

    public static void setPipeline(adris.altoclef.util.agent.Pipeline pipeline) {
        _pipeline = pipeline;
    }

    public Py4jEntryPoint getInfoSender() {
        return _py4jEntryPoint;
    }

    public GatewayServer getGateway() {
        return _gatewayServer;
    }

    public DamageTracker getDamageTracker() {
        return damageTracker;
    }

    public Task getCurrentTask() {
        if (getUserTaskChain() != null) {
            return getUserTaskChain().getCurrentTask();
        }
        return null;
    }

    public void initializePythonSender() {
        if (_gatewayServer != null) {
            // Already running — restart cleanly to pick up new port settings
            reloadPythonSender();
            return;
        }
        _py4jEntryPoint = new Py4jEntryPoint(this);
        int port = getModSettings().getPythonGatewayPort();
        final int originalPort = port;
        final int MAX_ATTEMPTS = 20;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            _gatewayServer = new GatewayServer(
                    _py4jEntryPoint,
                    port,
                    port + 1,
                    GatewayServer.DEFAULT_CONNECT_TIMEOUT,
                    GatewayServer.DEFAULT_READ_TIMEOUT,
                    null
            );
            try {
                _gatewayServer.start();
                // Port changed — save to settings so next launch uses the same port
                if (port != originalPort) {
                    getModSettings().setPythonGatewayPort(port);
                    ConfigHelper.saveConfig(adris.altoclef.Settings.SETTINGS_PATH, getModSettings());
                    Debug.logMessage("Py4j port conflict — bound to " + port + " (saved to settings)");
                }
                _py4jEntryPoint.InitPythonCallback();
                Debug.logMessage("Py4j gateway started on port " + port);
                startMcpServer();
                return;
            } catch (Py4JNetworkException e) {
                if (e.getCause() instanceof java.net.BindException) {
                    _gatewayServer = null;
                    Debug.logWarning("Py4j port " + port + " in use, trying " + (port + 2) + "...");
                    port += 2;
                } else {
                    throw e;
                }
            }
        }
        Debug.logError("Py4j: failed to bind after " + MAX_ATTEMPTS + " attempts, giving up");
    }

    /** Start the in-mod MCP server (LAN-hosted, 0.0.0.0:mcpPort) so a cognitive
     *  agent can drive the bot over the network through the same levers. */
    private void startMcpServer() {
        if (!getModSettings().isMcpEnabled() || _mcpServer != null || _py4jEntryPoint == null) return;
        try {
            _mcpServer = new adris.altoclef.mcp.McpServer(_py4jEntryPoint);
            int mport = getModSettings().getMcpPort();
            _mcpServer.start(mport);
            Debug.logMessage("MCP server started on 0.0.0.0:" + mport + " (http://<lan-ip>:" + mport + "/mcp)");
        } catch (Exception e) {
            Debug.logWarning("MCP server failed to start: " + e.getMessage());
            _mcpServer = null;
        }
    }

    public void stopPythonSender() {
        if (_gatewayServer != null) _gatewayServer.shutdown();
    }

    public void reloadPythonSender() {
        stopPythonSender();
        _gatewayServer = null;
        initializePythonSender();
    }

    public static String getSelfName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return "";
        if (client.getSession() != null) return client.getSession().getUsername();
        if (client.player != null) return client.player.getName().getString();
        return "";
    }

    /**
     * Replaces the client session with a new one bearing a different username.
     * Only works before connecting to a server.
     */
    public static boolean changePlayerName(String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            Debug.logWarning("Cannot change username: empty");
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null) {
            Debug.logWarning("Cannot change username: no session");
            return false;
        }
        try {
            Session cur = client.getSession();
            //#if MC >= 12111
            //$$ // TODO [1.21.11] Session.getAccountType() removed — constructor changed
            //$$ Session next = new Session(
            //$$         newUsername,
            //$$         cur.getUuidOrNull(),
            //$$         cur.getAccessToken(),
            //$$         cur.getXuid(),
            //$$         cur.getClientId()
            //$$ );
            //#else
            Session next = new Session(
                    newUsername,
                    cur.getUuidOrNull(),
                    cur.getAccessToken(),
                    cur.getXuid(),
                    cur.getClientId(),
                    cur.getAccountType()
            );
            //#endif
            ((MinecraftClientSessionMixin) client).setSession(next);
            Debug.logMessage("Username changed to: " + newUsername);
            return true;
        } catch (Exception e) {
            Debug.logError("Failed to change username: " + e.getMessage());
            return false;
        }
    }

    private static boolean looksLikeDefaultNick(String name) {
        return name != null && name.matches("(?i)player\\d+");
    }

    private void tryRestoreNickname() {
        String currentName = getSelfName();
        if (settings == null) return;

        if (!looksLikeDefaultNick(currentName)) {
            // Good nick — remember it
            if (!currentName.equals(settings.getLastNickname())) {
                settings.setLastNickname(currentName);
                ConfigHelper.saveConfig(adris.altoclef.Settings.SETTINGS_PATH, settings);
                Debug.logMessage("Saved nickname: " + currentName);
            }
            return;
        }
        // Current nick is PlayerNNN — restore saved one
        String last = settings.getLastNickname();
        if (last != null && !last.isBlank() && !looksLikeDefaultNick(last)) {
            Debug.logMessage("Detected default nick '" + currentName + "', restoring to: " + last);
            changePlayerName(last);
        }
    }

    // Are we in game (playing in a server/world)
    public static boolean inGame() {
        return MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().getNetworkHandler() != null;
    }

    /**
     * Executes commands (ex. `@get`/`@gamer`)
     */
    public static CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // As such, nothing will be loaded here but basic initialization.
        EventBus.subscribe(TitleScreenEntryEvent.class, evt -> onInitializeLoad());

        if (instance != null) {
            throw new IllegalStateException("AltoClef already loaded!");
        }
        instance = this;
    }

    public void onInitializeLoad() {
        // This code should be run after Minecraft loads everything else in.
        // This is the actual start point, controlled by a mixin.

        initializeBaritoneSettings();

        // Central Managers
        commandExecutor = new CommandExecutor(this);
        taskRunner = new TaskRunner(this);
        trackerManager = new TrackerManager(this);
        botBehaviour = new BotBehaviour(this);
        extraController = new PlayerExtraController(this);

        // Task chains
        new GameMenuTaskChain(taskRunner);
        userTaskChain = new UserTaskChain(taskRunner);
        mobDefenseChain = new MobDefenseChain(taskRunner);
        new DeathMenuChain(taskRunner);
        new PlayerInteractionFixChain(taskRunner);
        mlgBucketChain = new MLGBucketFallChain(taskRunner);
        new UnstuckChain(taskRunner);
        new PreEquipItemChain(taskRunner);
        worldSurvivalChain = new WorldSurvivalChain(taskRunner);
        foodChain = new FoodChain(taskRunner);

        // Trackers
        storageTracker = new ItemStorageTracker(this, trackerManager, container -> containerSubTracker = container);
        entityTracker = new EntityTracker(trackerManager);
        blockScanner = new BlockScanner(this);
        chunkTracker = new SimpleChunkTracker(this);
        miscBlockTracker = new MiscBlockTracker(this);
        craftingRecipeTracker = new CraftingRecipeTracker(trackerManager);
        damageTracker = new DamageTracker(trackerManager);

        // Renderers
        commandStatusOverlay = new CommandStatusOverlay();
        altoClefTickChart = new AltoClefTickChart(MinecraftClient.getInstance().textRenderer);

        // Misc managers
        messageSender = new MessageSender();
        inputControls = new InputControls();
        slotHandler = new SlotHandler(this);

        butler = new Butler(this);

        initializeCommands();

        // Load settings
        adris.altoclef.Settings.load(newSettings -> {
            settings = newSettings;
            // Baritone's `acceptableThrowawayItems` should match our own.
            List<Item> baritoneCanPlace = Arrays.stream(settings.getThrowawayItems(true))
                    .filter(item -> item != Items.SOUL_SAND && item != Items.MAGMA_BLOCK && item != Items.SAND && item
                            != Items.GRAVEL).toList();
            getClientBaritoneSettings().acceptableThrowawayItems.value.addAll(baritoneCanPlace);
            // ⛔ AND SNAPSHOT THE RESULT AS ALTOCLEF'S OWN (G-0b).
            // Six places in this mod read the throwaway list back OUT of the pathfinder's settings
            // object, which is how a list altoclef derives from its OWN settings ends up being
            // owned by a foreign type. The union is what matters -- the pathfinder ships defaults
            // (cobblestone, dirt, ...) and the line above adds ours -- so it is captured HERE, at
            // the one moment both halves are present, rather than reconstructed by each reader.
            // The write above stays: the pathfinder still needs the list to plan placements. What
            // goes away is six READS of a foreign object model for data we produced.
            throwawayItems.clear();
            throwawayItems.addAll(getClientBaritoneSettings().acceptableThrowawayItems.value);
            // If we should run an idle command...
            if ((!getUserTaskChain().isActive() || getUserTaskChain().isRunningIdleTask()) && getModSettings().shouldRunIdleCommandWhenNotActive()) {
                getUserTaskChain().signalNextTaskToBeIdleTask();
                getCommandExecutor().executeWithPrefix(getModSettings().getIdleCommand());
            }
            // Don't break blocks or place blocks where we are explicitly protected.
            getExtraBaritoneSettings().avoidBlockBreak(blockPos -> settings.isPositionExplicitlyProtected(blockPos));
            getExtraBaritoneSettings().avoidBlockPlace(blockPos -> settings.isPositionExplicitlyProtected(blockPos));
            getExtraBaritoneSettings().getForceSaveToolPredicates().add((state, item) -> StorageHelper.shouldSaveStack(this, state.getBlock(), item));

            // Auto-restore nickname if current name looks like PlayerNNN
            tryRestoreNickname();

            // Auto-connect to server if configured
            if (settings.shouldAutoConnect() && !inGame()) {
                String server = settings.getAutoConnectServer();
                Debug.logMessage("Auto-connecting to server: " + server);
                // Delay slightly to let the title screen settle
                _postInitQueue.add(mod -> {
                    if (taskRunner != null && taskRunner.gameMenuTaskChain != null) {
                        taskRunner.gameMenuTaskChain.connectToServer(server);
                    }
                });
            }

            // Initialize Python sender after settings are loaded (needs getPythonGatewayPort())
            initializePythonSender();
        });

        // Forward incoming server chat/game messages through Butler
        // (server detection, chatType, autoJoin, strong/weak py4j forwarding)
        String _modChatPrefixNoCodes = getModSettings().getChatLogPrefix();
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String msg = message.getString();
            if (getInfoSender() != null) getInfoSender().recordChat(msg);
            if (!msg.startsWith(_modChatPrefixNoCodes) && getButler() != null)
                getButler().onReceiveChat(msg);
            return true;
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) {
                String msg = message.getString();
                // RECORD FIRST, FILTER SECOND. The filter exists so Butler does not parse the
                // bot's own log lines as server chat; it must not also blind the chat ring, which
                // is the only channel a test has for seeing the mod's own errors.
                if (getInfoSender() != null) getInfoSender().recordChat(msg);
                if (!msg.contains(_modChatPrefixNoCodes) && getButler() != null)
                    getButler().onReceiveChat(msg);
            }
            return true;
        });

        // Receive + cancel chat
        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            // `@@` WorldEdit command handler (user 2026-07-24) — `@@` distances the many WE
            // commands from the main `@` altoclef commands (mirrors WorldEdit's `//`) and dodges
            // the `@set` clash. Checked BEFORE the `@` dispatch. Read the player / crosshair block
            // HERE on the client thread (safe), then run the handler OFF-thread so the worldedit
            // primitives' onClientThread marshalling doesn't deadlock the client.
            if (line.startsWith("@@")) {
                evt.cancel();
                final String weCmd = line.substring(2).strip();
                net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                int[] pb = null, cb = null;
                if (mc.player != null) {
                    net.minecraft.util.math.BlockPos p = mc.player.getBlockPos();
                    pb = new int[]{p.getX(), p.getY(), p.getZ()};
                }
                if (mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bhr
                        && mc.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                    net.minecraft.util.math.BlockPos p = bhr.getBlockPos();
                    cb = new int[]{p.getX(), p.getY(), p.getZ()};
                }
                final int[] fpb = pb, fcb = cb;
                new Thread(() -> adris.altoclef.commands.worldedit.WorldEditCommands.handle(this, weCmd, fpb, fcb),
                        "we-cmd").start();
                return;
            }
            if (getCommandExecutor().isClientCommand(line)) {
                evt.cancel();
                getCommandExecutor().execute(line);
            }
        });

        // Tick with the client
        EventBus.subscribe(ClientTickEvent.class, evt -> {
            long nanos = System.nanoTime();
            onClientTick();
            altoClefTickChart.pushTickNanos(System.nanoTime()-nanos);
        });

        // Render
        EventBus.subscribe(ClientRenderEvent.class, evt -> onClientRenderOverlay(evt.context));

        // Block place/break tracking for protected area avoidance
        EventBus.subscribe(BlockPlaceEvent.class, evt -> worldSurvivalChain.onBlockPlaced(this, evt.blockPos, evt.blockState));
        EventBus.subscribe(BlockBrokenEvent.class, evt -> worldSurvivalChain.onBlockBroken(this, evt.blockPos, evt.blockState, evt.player));

        // Projectile detection — instant reaction to incoming arrows
        EventBus.subscribe(adris.altoclef.eventbus.events.multiplayer.ProjectileEvent.class, evt ->
                getMobDefenseChain().onProjectileLaunched(this, evt.entity, evt.sticked));

        // Item use detection — detect players aiming bows (disabled in MobDefenseChain for now)
        EventBus.subscribe(adris.altoclef.eventbus.events.multiplayer.ItemUseEvent.class, evt ->
                getMobDefenseChain().onPlayerItemUse(this, evt.entity, evt.released));

        // AUTO-ACCEPT server resource-pack prompts (operator 2026-06-21): some servers (fdmc.pw) push a
        // REQUIRED resource pack with a "this server requires a resource pack — decline = DISCONNECT" screen.
        // A headless bot would sit on it / get kicked. When that ConfirmScreen opens, click YES automatically.
        EventBus.subscribe(adris.altoclef.eventbus.events.ScreenOpenEvent.class, evt -> {
            if (evt.preOpen) return;
            net.minecraft.client.gui.screen.Screen s = evt.screen;
            if (!(s instanceof net.minecraft.client.gui.screen.ConfirmScreen)) return;
            String t = s.getTitle().getString().toLowerCase();
            if (t.contains("resource pack") || t.contains("texture")
                    || t.contains("ресурс") || t.contains("набор ресурс")) {
                try {
                    it.unimi.dsi.fastutil.booleans.BooleanConsumer cb =
                            ((adris.altoclef.mixins.ConfirmScreenAccessor) s).getCallback();
                    if (cb != null) {
                        Debug.logMessage("Auto-accepting server resource-pack prompt.");
                        cb.accept(true);
                    }
                } catch (Throwable e) {
                    Debug.logWarning("RP auto-accept failed: " + e.getMessage());
                }
            }
        });

        // Playground
        Playground.IDLE_TEST_INIT_FUNCTION(this);

        // Tasks
        TaskCatalogue.init();

        if (getClientBaritone() != null) {
            getClientBaritone().getGameEventHandler().registerEventListener(new TabCompleter());
        }

        // Tungsten need-fulfiller, stage 1 (docs/features/TUNGSTEN_ALTOCLEF_API.md):
        // when the tungsten executor mines a block, equip the best tool for it.
        // Runs on the client thread (tungsten calls it from its mining tick).
        kaptainwutax.tungsten.TungstenModDataContainer.equipToolHook = (pos, state) -> {
            try {
                if (getFoodChain().isTryingToEat()) return;
                java.util.Optional<adris.altoclef.util.slots.Slot> best =
                        adris.altoclef.util.helpers.StorageHelper.getBestToolSlot(this, state);
                if (best.isEmpty()) return;
                adris.altoclef.util.slots.Slot current = adris.altoclef.util.slots.PlayerSlot.getEquipSlot();
                net.minecraft.item.Item bestItem =
                        adris.altoclef.util.helpers.StorageHelper.getItemStackInSlot(best.get()).getItem();
                if (adris.altoclef.util.helpers.StorageHelper.getItemStackInSlot(current).getItem() == bestItem) return;
                getSlotHandler().forceEquipItem(bestItem);
            } catch (Throwable t) {
                // the hook must never break mining
            }
        };

        // when the tungsten executor paves a planned bridge, equip a cheap build block
        // into the main hand (mirror of equipToolHook for placing). Runs on the client
        // thread from tickPlacing. Tungsten never touches the inventory itself.
        kaptainwutax.tungsten.TungstenModDataContainer.equipBlockHook = () -> {
            try {
                if (getPlayer() == null) return;
                if (getPlayer().getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem) return;
                net.minecraft.item.Item[] blocks = {
                    net.minecraft.item.Items.COBBLESTONE, net.minecraft.item.Items.DIRT,
                    net.minecraft.item.Items.STONE, net.minecraft.item.Items.NETHERRACK,
                    net.minecraft.item.Items.COBBLED_DEEPSLATE, net.minecraft.item.Items.OAK_PLANKS,
                    net.minecraft.item.Items.DEEPSLATE, net.minecraft.item.Items.ANDESITE
                };
                for (net.minecraft.item.Item b : blocks) {
                    if (getItemStorage().hasItemInventoryOnly(b)) { getSlotHandler().forceEquipItem(b); return; }
                }
            } catch (Throwable t) {
                // the hook must never break placing
            }
        };

        // Tungsten protection hook: altoclef's break-avoiders (bed protection,
        // task-scoped avoid lists, protected zones) are the single source of
        // truth for "may we mine this" — bridge them into tungsten BreakRules.
        kaptainwutax.tungsten.TungstenModDataContainer.canBreakHook = pos -> {
            try {
                return !getExtraBaritoneSettings().shouldAvoidBreaking(pos);
            } catch (Throwable t) {
                return true; // protection lookup failure must not freeze pathing
            }
        };
        // Symmetric place-protection: altoclef's place-avoiders / protected zones
        // are the single source of truth for "may we build here" — bridge them
        // into tungsten PlaceRules (bridge/fill/build/schematic all honour it).
        kaptainwutax.tungsten.TungstenModDataContainer.canPlaceHook = pos -> {
            try {
                return !getExtraBaritoneSettings().shouldAvoidPlacingAt(pos);
            } catch (Throwable t) {
                return true; // protection lookup failure must not freeze building
            }
        };

        // External mod initialization
        runEnqueuedPostInits();
    }

    // Client tick
    private void onClientTick() {
        runEnqueuedPostInits();

        inputControls.onTickPre();

        // Cancel shortcut
        if (InputHelper.isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL) && InputHelper.isKeyPressed(GLFW.GLFW_KEY_K)) {
            stopTasks();
        }

        // TODO: should this go here?
        storageTracker.setDirty();
        containerSubTracker.onServerTick();
        miscBlockTracker.tick();
        trackerManager.tick();
        blockScanner.tick();
        damageTracker.tick();
        taskRunner.tick();

        if (taskRunner.gameMenuTaskChain != null) {
            taskRunner.gameMenuTaskChain.onTickPost(this);
        }

        messageSender.tick();

        inputControls.onTickPost();
    }

    public void stopTasks() {
        if (userTaskChain != null) {
            userTaskChain.cancel(this);
        }
        if (taskRunner.getCurrentTaskChain() != null) {
            taskRunner.getCurrentTaskChain().stop();
        }
        // "Stop all automation" has never included the thing walking the body. This method cancels
        // the CHAIN and has never spoken to a pathfinder, so `@stop` left whatever route was in
        // flight steering the bot -- which is why the bench has to send `;stop` separately after
        // every `@stop`, a workaround for a gap rather than a second concern.
        adris.altoclef.control.Nav.cancelAll();
        commandStatusOverlay.resetTimer();
    }

    /// GETTERS AND SETTERS

    private void onClientRenderOverlay(DrawContextWrapper context) {
        context.setRenderLayer(RenderLayerVer.getGuiOverlay());
        if (settings.shouldShowTaskChain()) {
            commandStatusOverlay.render(this, context);
        }

        if (settings.shouldShowDebugTickMs()) {
            altoClefTickChart.render(this, context, 1, context.getScaledWindowWidth() / 2 - 124);
        }

        if (inGame()) {
            LookHelper.updateWindMouseRotation(this);
        }
    }

    private void initializeBaritoneSettings() {
        if (getClientBaritone() == null) return; // baritone not available on this MC version
        getExtraBaritoneSettings().canWalkOnEndPortal(false);
        getClientBaritoneSettings().freeLook.value = false;
        getClientBaritoneSettings().overshootTraverse.value = false;
        getClientBaritoneSettings().allowOvershootDiagonalDescend.value = true;
        getClientBaritoneSettings().allowInventory.value = true;
        getClientBaritoneSettings().allowParkour.value = true;
        getClientBaritoneSettings().allowParkourAscend.value = true;
        getClientBaritoneSettings().allowParkourPlace.value = false;
        getClientBaritoneSettings().allowDiagonalDescend.value = false;
        getClientBaritoneSettings().allowDiagonalAscend.value = false;
        getClientBaritoneSettings().blocksToAvoid.value = new LinkedList<>(List.of(Blocks.FLOWERING_AZALEA, Blocks.AZALEA,
                Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                Blocks.WARPED_ROOTS, Blocks.VINE, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN));

        // dont try to break nether portal block
        avoidBreaking(Blocks.NETHER_PORTAL);
        getClientBaritoneSettings().blocksToDisallowBreaking.value.add(Blocks.NETHER_PORTAL);

        // Let baritone move items to hotbar to use them
        // Reduces a bit of far rendering to save FPS
        getClientBaritoneSettings().fadePath.value = true;
        // Don't let baritone scan dropped items, we handle that ourselves.
        getClientBaritoneSettings().mineScanDroppedItems.value = false;
        // Don't let baritone wait for drops, we handle that ourselves.
        getClientBaritoneSettings().mineDropLoiterDurationMSThanksLouca.value = 0L;

        // Water bucket placement will be handled by us exclusively
        getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);

        // For render smoothing
        getClientBaritoneSettings().randomLooking.value = 0.0;
        getClientBaritoneSettings().randomLooking113.value = 0.0;

        // Give baritone more time to calculate paths. Sometimes they can be really far away.
        // Was: 2000L
        getClientBaritoneSettings().failureTimeoutMS.reset();
        // Was: 5000L
        getClientBaritoneSettings().planAheadFailureTimeoutMS.reset();
        // Was 100
        getClientBaritoneSettings().movementTimeoutTicks.reset();
    }

    // List all command sources here.
    private void initializeCommands() {
        try {
            // This creates the commands. If you want any more commands feel free to initialize new command lists.
            AltoClefCommands.init();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // TODO refactor codebase to use this instead of passing an argument around
    /**
     * @return the instance of this class or null if it has not been initialized yet
     */
    public static AltoClef getInstance() {
        return instance;
    }

    /**
     * Runs the highest priority task chain
     * (task chains run the task tree)
     */
    public TaskRunner getTaskRunner() {
        return taskRunner;
    }

    /**
     * The user task chain (runs your command. Ex. Get Diamonds, Beat the Game)
     */
    public UserTaskChain getUserTaskChain() {
        return userTaskChain;
    }

    /**
     * Controls bot behaviours, like whether to temporarily "protect" certain blocks or items
     */
    public BotBehaviour getBehaviour() {
        return botBehaviour;
    }

    /**
     * Controls tasks, for pausing and unpausing the bot
     */
    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean pausing) {
        this.paused = pausing;
    }

    /**
     * storages the task you where doing before pausing.
     */
    public void setStoredTask(Task currentTask) {
        this.storedTask = currentTask;
    }

    /**
     * Gets the task you where doing before pausing.
     */
    public Task getStoredTask() {
        return storedTask;
    }

    // --- Task timeout ---
    /** Enable task timeout with default duration (60s). */
    public void setTimeoutTaskFlag(boolean active) {
        _timeoutActive = active;
        _timeoutDuration = 60;
        _timeoutStartMs = System.currentTimeMillis();
    }

    /** Enable task timeout with a specific duration in seconds. */
    public void setTimeoutTask(float seconds) {
        _timeoutActive = true;
        _timeoutDuration = seconds;
        _timeoutStartMs = System.currentTimeMillis();
    }

    /**
     * Returns true (and clears the flag) if a timeout was active and has elapsed.
     * Called from UserTaskChain each tick.
     */
    public boolean checkAndClearTimeout() {
        if (!_timeoutActive) return false;
        float elapsed = (System.currentTimeMillis() - _timeoutStartMs) / 1000f;
        if (elapsed >= _timeoutDuration) {
            _timeoutActive = false;
            return true;
        }
        return false;
    }

    /**
     * Tracks items in your inventory and in storage containers.
     */
    public ItemStorageTracker getItemStorage() {
        return storageTracker;
    }

    /**
     * Tracks loaded entities
     */
    public EntityTracker getEntityTracker() {
        return entityTracker;
    }

    /**
     * Manages a list of all available recipes
     */
    public CraftingRecipeTracker getCraftingRecipeTracker() {
        return craftingRecipeTracker;
    }

    /**
     * Tracks blocks and their positions - better version of BlockTracker
     */
    public BlockScanner getBlockScanner() {
        return blockScanner;
    }

    /**
     * Tracks of whether a chunk is loaded/visible or not
     */
    public SimpleChunkTracker getChunkTracker() {
        return chunkTracker;
    }

    /**
     * Tracks random block things, like the last nether portal we used
     */
    public MiscBlockTracker getMiscBlockTracker() {
        return miscBlockTracker;
    }

    /**
     * Baritone access (could just be static honestly)
     */
    public Baritone getClientBaritone() {
        try {
            if (getPlayer() == null) {
                return (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
            }
            return (Baritone) BaritoneAPI.getProvider().getBaritoneForPlayer(getPlayer());
        } catch (ClassCastException e) {
            // Baritone not initialized (incompatible MC version) — proxy can't cast
            return null;
        }
    }

    /**
     * Baritone settings access (could just be static honestly)
     */
    public Settings getClientBaritoneSettings() {
        return Baritone.settings();
    }

    /**
     * Baritone settings special to AltoClef (could just be static honestly)
     */
    public AltoClefSettings getExtraBaritoneSettings() {
        return AltoClefSettings.getInstance();
    }

    /**
     * AltoClef Settings
     */
    public adris.altoclef.Settings getModSettings() {
        return settings;
    }

    /**
     * Butler controller. Keeps track of users and lets you receive user messages
     */
    public Butler getButler() {
        return butler;
    }

    /**
     * Sends chat messages (avoids auto-kicking)
     */
    public MessageSender getMessageSender() {
        return messageSender;
    }

    /**
     * Does Inventory/container slot actions
     */
    public SlotHandler getSlotHandler() {
        return slotHandler;
    }

    /**
     * Minecraft player client access (could just be static honestly)
     */
    /**
     * Items this bot is willing to place and lose — altoclef's own list, snapshotted at load.
     *
     * <p>The union of the pathfinder's shipped defaults and the throwaways altoclef derives from
     * its settings, taken at the moment both are present. A snapshot rather than a live view
     * because there is exactly one writer, in the settings-load callback; if a second appears this
     * has to become a re-read, and that is the thing to check first if the list ever looks stale.
     */
    private final List<Item> throwawayItems = new ArrayList<>();

    /**
     * Protect blocks from the pathfinder's pick — and let them go again (G-0b).
     *
     * <p>Eleven call sites reached into {@code getClientBaritoneSettings().blocksToAvoidBreaking
     * .value} to add or remove a block, which is altoclef's OWN policy stored in, and mutated
     * through, a foreign object model. Every one of them is a WRITE; nothing ever read the list
     * back. So the port is an encapsulation rather than a data move: one seam instead of eleven,
     * and when the pathfinder behind it is replaced only this method changes.
     *
     * <p>Pairs of add/remove across a task's lifetime are exactly the shape that drifts. It already
     * had: ConstructGraveTask protected COBBLESTONE_SLAB and released STONE_SLAB, so the slab it
     * actually added was never released and the pathfinder refused to break cobblestone slabs for
     * the rest of the session.
     */
    public void avoidBreaking(Block... blocks) {
        getClientBaritoneSettings().blocksToAvoidBreaking.value.addAll(Arrays.asList(blocks));
    }

    /** Release blocks protected by {@link #avoidBreaking}. Pass exactly what was passed there. */
    public void allowBreaking(Block... blocks) {
        getClientBaritoneSettings().blocksToAvoidBreaking.value.removeAll(Arrays.asList(blocks));
    }

    /** Keep the pathfinder from routing THROUGH these blocks, and let them go again. */
    public void avoidWalkingThrough(Block... blocks) {
        getClientBaritoneSettings().blocksToAvoid.value.addAll(Arrays.asList(blocks));
    }

    /** Undo {@link #avoidWalkingThrough}. */
    public void allowWalkingThrough(Block... blocks) {
        getClientBaritoneSettings().blocksToAvoid.value.removeAll(Arrays.asList(blocks));
    }

    /** Whether the pathfinder should leave gravel and sand alone (bucket work turns this on). */
    public void setAvoidUpdatingFallingBlocks(boolean avoid) {
        getClientBaritoneSettings().avoidUpdatingFallingBlocks.value = avoid;
    }

    /** @return items safe to place and abandon — see {@link #throwawayItems}. Never null. */
    public List<Item> getThrowawayItems() {
        return throwawayItems;
    }

    public ClientPlayerEntity getPlayer() {
        return MinecraftClient.getInstance().player;
    }

    /**
     * Minecraft world access (could just be static honestly)
     */
    public ClientWorld getWorld() {
        return MinecraftClient.getInstance().world;
    }

    /**
     * Minecraft client interaction controller access (could just be static honestly)
     */
    public ClientPlayerInteractionManager getController() {
        return MinecraftClient.getInstance().interactionManager;
    }

    /**
     * Extra controls not present in ClientPlayerInteractionManager. This REALLY should be made static or combined with something else.
     */
    public PlayerExtraController getControllerExtras() {
        return extraController;
    }

    /**
     * Manual control over input actions (ex. jumping, attacking)
     */
    public InputControls getInputControls() {
        return inputControls;
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task) {
        runUserTask(task, () -> {
        });
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task, Runnable onFinish) {
        userTaskChain.runTask(this, task, onFinish);
    }

    /**
     * Cancel currently running user task
     */
    public void cancelUserTask() {
        userTaskChain.cancel(this);
    }

    /**
     * Takes control away to eat food
     */
    public FoodChain getFoodChain() {
        return foodChain;
    }

    /**
     * Takes control away to defend against mobs
     */
    public MobDefenseChain getMobDefenseChain() {
        return mobDefenseChain;
    }

    /**
     * Takes control away to perform bucket saves
     */
    public MLGBucketFallChain getMLGBucketChain() {
        return mlgBucketChain;
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    /**
     * Logs to the console and also messages any player using the bot as a butler.
     */
    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
    }

    public void logWarning(String message) {
        logWarning(message, MessagePriority.TIMELY);
    }

    /**
     * Logs a warning to the console and also alerts any player using the bot as a butler.
     */
    public void logWarning(String message, MessagePriority priority) {
        Debug.logWarning(message);
    }

    private void runEnqueuedPostInits() {
        synchronized (_postInitQueue) {
            while (!_postInitQueue.isEmpty()) {
                _postInitQueue.poll().accept(this);
            }
        }
    }

}
