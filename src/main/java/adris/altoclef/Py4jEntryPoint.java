package adris.altoclef;

import adris.altoclef.control.Nav;
import adris.altoclef.butler.WhisperChecker;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.multiplayer.GestureTask;
import adris.altoclef.tasks.speedrun.WaitForDragonAndPearlTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.threats.PlayerThreat;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.util.agent.AgentState;
import adris.altoclef.util.agent.AgentActionButtons;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.agent.Pipeline;
import kaptainwutax.tungsten.path.movements.Rotation;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import py4j.Py4JException;

import static adris.altoclef.util.helpers.LookHelper.getLookingProbability;

public class Py4jEntryPoint {
    AltoClef _mod;
    PythonCallback _cb;
    // Ring buffer of recent chat lines this client saw — lets external control (e.g. the Telegram
    // `/switch local` bridge) PULL chat over py4j via getRecentChat(), with no log file needed.
    // 300 lines is less than ONE scenario of verbose tungsten logging, so an error early in a
    // run was evicted long before any test read the ring.
    private static final int CHAT_LOG_MAX = 2000;
    private final java.util.concurrent.ConcurrentLinkedDeque<String> _chatLog = new java.util.concurrent.ConcurrentLinkedDeque<>();
    Executor _executor;
    public static String last_talking_player = "";

    // Bounded, drop-on-overflow executor for the VOICE hot path. Simple Voice Chat fires
    // ~50 packets/sec PER speaking player (~150-200/sec with several speakers). The old path
    // submitted each packet (holding its audio byte[]) to the UNBOUNDED main worker pool AND
    // did a synchronous py4j round-trip per task → the producer outran the consumer, the queue
    // + retained byte[]s grew without bound, the 1G heap OOM'd and the whole JVM (incl. the
    // py4j gateway on 25333) died. A single-thread / 32-slot / DiscardOldest pool caps memory
    // and simply drops stale audio under load — STT only needs a steady stream, not every frame.
    private final java.util.concurrent.ThreadPoolExecutor _voiceExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(32),
                    r -> { Thread t = new Thread(r, "py4j-voice"); t.setDaemon(true); return t; },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    public Py4jEntryPoint(AltoClef mod) {
        _mod = mod;
        resetValues();
        _executor = Util.getMainWorkerExecutor();
    }

    public void onVoiceFeed(String playerName, byte[] audio) {
        // Use the CACHED callback flag — NO py4j round-trip on the hot path (round-tripping
        // IsCallbackServerStarted per packet halved throughput and fed the OOM). Bounded
        // executor drops stale audio under load instead of growing the heap. The flag is kept
        // fresh by the periodic callbacks (chat/server-info) that call IsCallbackServerStarted.
        if (!callbackstarted) return;
        try {
            _voiceExecutor.execute(() -> {
                try { _cb.onVoiceFeed(playerName, audio); }
                catch (Throwable e) { /* never let one packet's failure kill the loop */ }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // queue saturated → drop this packet (acceptable under heavy voice)
        }
    }

    private void executeInNetworkThread(Runnable task) {
        try {
            _executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private <T> T executeInNetworkThread(Callable<T> task) {
        try {
            FutureTask<T> futureTask = new FutureTask<>(task);
            try {
                _executor.execute(futureTask);
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                return futureTask.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void resetValues() {
        CentralGameInfoDict.put("server", "universal");
        CentralGameInfoDict.put("serverMode", "survival");
        CentralGameInfoDict.put("chatType", "lobby");
    }

    public void setPerspective(int perspectiveNum) {
        Perspective perspective = Perspective.FIRST_PERSON;
        switch (perspectiveNum) {
            case 0:
                perspective = Perspective.FIRST_PERSON;
                break;
            case 1:
                perspective = Perspective.THIRD_PERSON_BACK;
                break;
            case 2:
                perspective = Perspective.THIRD_PERSON_FRONT;
                break;
            default:
                Debug.logMessage("Unknown perspective requested: " + perspectiveNum);
        }
        MinecraftClient.getInstance().options.setPerspective(perspective);
    }

    public boolean hasActiveTask() {
        if (!(AltoClef.inGame() && _mod.getPlayer() != null && _mod.getWorld() != null))
            return false;
        Task task = _mod.getUserTaskChain().getCurrentTask();

        if (task instanceof AbstractKillEntityTask || hasBaritoneGoal() || isTungstenActive())
            return true;

        return !(task instanceof IdleTask || task instanceof GestureTask
                || task instanceof WaitForDragonAndPearlTask
                || (task != null && (task.toString() != null && !task.toString().isBlank() &&
                        task.toString().toLowerCase().contains("wait"))));
    }

    public boolean isTungstenActive() {
        try {
            return kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get()
                    || (kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR != null
                        && kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR.isRunning());
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] getScreenshot() {
        //#if MC >= 12111
        //$$ // 1.21.11: takeScreenshot is async (Consumer<NativeImage>) and NativeImage.getBytes() is gone.
        //$$ // Encode PNG via NativeImage.writeTo(Path) (MC's native encoder) into a temp file, read it back, delete.
        //$$ CompletableFuture<byte[]> future = new CompletableFuture<>();
        //$$ MinecraftClient.getInstance().execute(() -> {
        //$$     try {
        //$$         Framebuffer buffer = MinecraftClient.getInstance().getFramebuffer();
        //$$         ScreenshotRecorder.takeScreenshot(buffer, (img) -> {
        //$$             try {
        //$$                 java.nio.file.Path tmp = java.nio.file.Files.createTempFile("uclef_shot", ".png");
        //$$                 img.writeTo(tmp);
        //$$                 byte[] data = java.nio.file.Files.readAllBytes(tmp);
        //$$                 java.nio.file.Files.deleteIfExists(tmp);
        //$$                 future.complete(data);
        //$$             } catch (Exception e) {
        //$$                 future.completeExceptionally(e);
        //$$             } finally {
        //$$                 img.close();
        //$$             }
        //$$         });
        //$$     } catch (Exception e) {
        //$$         future.completeExceptionally(e);
        //$$     }
        //$$ });
        //$$ try {
        //$$     return future.get(5, TimeUnit.SECONDS);
        //$$ } catch (Exception e) {
        //$$     Debug.logInternal("Error taking screenshot: " + e.getMessage());
        //$$     return null;
        //$$ }
        //#else
        try {
            AtomicReference<NativeImage> screenshot = new AtomicReference<>();
            CompletableFuture<Void> future = new CompletableFuture<>();

            MinecraftClient.getInstance().execute(() -> {
                try {
                    Framebuffer buffer = MinecraftClient.getInstance().getFramebuffer();
                    screenshot.set(ScreenshotRecorder.takeScreenshot(buffer));
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    Debug.logInternal("Error taking screenshot: " + e.getMessage());
                }
            });

            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                Debug.logInternal("Timeout or error waiting for screenshot: " + e.getMessage());
                return null;
            }

            if (screenshot.get() == null) {
                Debug.logInternal("Screenshot is null");
                return null;
            }

            NativeImage img = screenshot.get();
            try {
                return img.getBytes();
            } finally {
                img.close();
            }
        } catch (Exception e) {
            Debug.logInternal("Error taking screenshot: " + e.getMessage());
        }
        return null;
        //#endif
    }

    public String getPipelineDescription() {
        return Objects.requireNonNullElse(AltoClef.getPipeline(), Pipeline.None).getDescription();
    }

    /**
     * Whether the task RUNNER is switched on, and what it last reported.
     *
     * <p>⛔ WHY THIS EXISTS. {@link #getTaskChainString()} reads
     * {@code TaskRunner.cachedCurrentTaskChain}, and {@code TaskRunner.tick()} opens with
     * {@code if (!active) return;} -- BEFORE that cache is ever written. So its "No tasks. Time to
     * add new!" means either "there is no task" or "there is one, the runner is off and has never
     * ticked", and nothing downstream can tell those apart. A whole diagnosis of the mine_stone
     * failure was written on the first reading before this was noticed, and checklist rule 2
     * records an earlier case where three independent witnesses agreed on a conclusion that was
     * wrong for exactly this reason -- the runner was switched off.
     *
     * <p>Returns {@code active=<bool> report=<the runner's own statusReport>}.
     */
    public String taskRunnerState() {
        try {
            var r = _mod.getTaskRunner();
            return "active=" + r.isActive() + " report=" + r.statusReport
                    + " lastDisableBy=" + adris.altoclef.tasksystem.TaskRunner.lastDisableCaller;
        } catch (Exception e) {
            return "active=? report=" + e;
        }
    }

    public String getTaskChainString() {
        StringBuilder tasks_string = null;

        try {
            if (_mod.getTaskRunner().getCurrentTaskChain() != null) {
                String chain_name = _mod.getTaskRunner().getCurrentTaskChain().getName();
                List<Task> tasks = _mod.getTaskRunner().getCurrentTaskChain().getTasks();
                if (tasks != null && !tasks.isEmpty() && chain_name != null) {
                    tasks_string = new StringBuilder("Current Baritone executor task list (");
                    tasks_string.append(chain_name);
                    tasks_string.append(" task chain)\n");
                    int i = 0;
                    for (Task task : tasks) {
                        if (i == 0) {
                            tasks_string.append("1. Main task: ");
                            tasks_string.append(task.toString());
                        } else if (i == 1) {
                            tasks_string.append("1.1. Subtasks: ");
                            tasks_string.append(task.toString());
                        } else {
                            tasks_string.append(" ->\n").append("1.1." + (i - 2)).append(". ");
                            String task_info = task.toString();
                            int MAX_TASK_INFO_LENGTH = 200;
                            if (task_info.length() < MAX_TASK_INFO_LENGTH) {
                                tasks_string.append(task_info);
                            } else {
                                tasks_string.append(task_info, 0, MAX_TASK_INFO_LENGTH - 5);
                                tasks_string.append("...");
                            }
                        }
                        if (i < tasks.size() - 1) {
                            tasks_string.append("\n");
                        }
                        i++;
                    }
                }
            }
        } catch (Exception e) {
            tasks_string = new StringBuilder("Error when getting tasks! Something is broken!");
        }
        if (tasks_string == null)
            tasks_string = new StringBuilder("No tasks. Time to add new!");
        return "Description of current game pipeline that is selected: " + getPipelineDescription() + "Current game tasks: " + tasks_string.toString();
    }

    /**
     * Which task chain owns this tick, and what every chain bid for it.
     *
     * <p>Call this when the bot is idle but a task was set: it distinguishes "the runner is off",
     * "no chain wants the tick" and "a chain is running but has no tasks", which
     * {@code getTaskChainString} reports identically as "No tasks".
     */
    public String getRunnerStatus() {
        try {
            return _mod.getTaskRunner().describeChains();
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    /**
     * How many log blocks sit within {@code radius} of the player.
     *
     * <p>For the bench, so a run can be started somewhere the bot actually CAN work. Measured: the
     * same build reached its first log in 21 seconds on one patch of ground and never on another
     * 300 blocks away, because one was forest and the other was not -- so "time to first log" was
     * measuring the biome rather than the bot. One call instead of a grid of py4j round trips.
     *
     * @return the count, or -1 when there is no world to look at
     */
    public int countLogsNear(int radius) {
        try {
            if (!AltoClef.inGame() || _mod.getWorld() == null || _mod.getPlayer() == null) return -1;
            net.minecraft.util.math.BlockPos me = _mod.getPlayer().getBlockPos();
            int found = 0;
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    for (int dy = -8; dy <= 24; dy += 2) {
                        net.minecraft.util.math.BlockPos p = me.add(dx, dy, dz);
                        if (!_mod.getWorld().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
                        if (_mod.getWorld().getBlockState(p).isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
                            found++;
                        }
                    }
                }
            }
            return found;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Stacks of every live thread, returned as text.
     *
     * <p>Because a JVM signal dump could not be got out of this container: kill -3 runs without
     * error and the output never reaches either the container log or the client's own 24 MB
     * latest.log. This goes through our own code and comes back through py4j, so nothing in the
     * image or the log rotation can swallow it. Intended for the moment the bench's watcher
     * reports a freeze -- every counter delta zero while the bot stays in game and busy.
     *
     * @param filter only threads whose name contains this (empty for all)
     */
    public String threadDump(String filter) {
        // ⛔ THE FILTER IS A LIST, IN PRIORITY ORDER, BECAUSE ONE NAME CAPTURED THE WRONG THREADS.
        //
        // This took a single substring, and gamer_smoke asked it for "Render". So every stall
        // capture in the repo holds four render threads and NOTHING ELSE -- and the question a
        // navigation stall actually poses is what the PATHFINDER is doing. Today that absence was
        // briefly read as "no search was running", which the dump cannot support: the search thread
        // was never eligible to appear in it.
        //
        // Callers truncate the result, so ORDER decides what survives: parts are emitted in the
        // order given, which lets a caller put the nav threads ahead of a dozen chunk-render ones.
        StringBuilder sb = new StringBuilder();
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            java.util.Set<Thread> seen = new java.util.HashSet<>();
            String[] parts = (filter == null || filter.isEmpty())
                    ? new String[]{""} : filter.split(",");
            for (String raw : parts) {
                String part = raw.trim();
                for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                    Thread t = e.getKey();
                    if (!part.isEmpty() && !t.getName().contains(part)) continue;
                    if (!seen.add(t)) continue;
                    sb.append('"').append(t.getName()).append("\" ").append(t.getState()).append(System.lineSeparator());
                    StackTraceElement[] st = e.getValue();
                    for (int i = 0; i < Math.min(st.length, 18); i++) {
                        sb.append("    at ").append(st[i]).append(System.lineSeparator());
                    }
                }
            }
        } catch (Exception ex) {
            sb.append("error: ").append(ex);
        }
        return sb.toString();
    }

    public String getGroundBlock() {
        if (AltoClef.inGame() && _mod.getPlayer() != null && _mod.getWorld() != null) {
            // Get the block below the player
            try {
                net.minecraft.util.math.BlockPos playerPos = _mod.getPlayer().getBlockPos();
                net.minecraft.util.math.BlockPos belowPos = playerPos.down();
                net.minecraft.block.BlockState blockState = _mod.getWorld().getBlockState(belowPos);
                String blockName = blockState.getBlock().getName().getString().toLowerCase();
                if (_mod.getPlayer().isOnGround() && blockName.equals("air")) {
                    return "dirt";
                }
                return blockName;
            } catch (Exception e) {
                return "unknown";
            }
        } else {
            return "void";
        }
    }

    public String getGroundBlockForPlayer(PlayerEntity player) {
        if (_mod.getWorld() != null && player != null) {
            try {
                net.minecraft.util.math.BlockPos playerPos = player.getBlockPos();
                net.minecraft.util.math.BlockPos belowPos = playerPos.down();
                net.minecraft.block.BlockState blockState = _mod.getWorld().getBlockState(belowPos);
                return blockState.getBlock().getName().getString().toLowerCase();
            } catch (Exception e) {
                return "unknown";
            }
        }
        return "unknown";
    }

    public String getHeldItem() {
        //#if MC >= 12111
        //$$ if (AltoClef.inGame() && _mod.getPlayer() != null) {
        //$$     java.util.List<ItemStack> handItems = java.util.List.of(_mod.getPlayer().getMainHandStack(), _mod.getPlayer().getOffHandStack());
        //$$     for (ItemStack item : handItems) {
        //#else
        if (AltoClef.inGame() && _mod.getPlayer() != null && _mod.getPlayer().getHandItems() != null) {
            for (ItemStack item : _mod.getPlayer().getHandItems()) {
        //#endif
                if (item.getItem() != null) {
                    String itemName = item.getItem().getName().getString().toLowerCase();
                    if (!itemName.equals("air")) {
                        if (item.contains(DataComponentTypes.CUSTOM_NAME)) {
                            String itemCustomName = item.getName().getString().toLowerCase();
                            return itemName + " (named " + itemCustomName + ")";
                        }
                        return itemName;
                    }
                }
            }
            return "nothing";
        } else {
            return "nothing";
        }
    }

    public PlayerEntity getEntity(String playerName) {
        if (AltoClef.inGame() && _mod.getPlayer() != null) {
            Optional<PlayerEntity> player = _mod.getEntityTracker().getPlayerEntity(playerName);
            return player.orElse(null);
        } else {
            return null;
        }
    }

    public String getInfo() {
        String result = "";
        for (String value : CentralGameInfoDict.values()) {
            if (!value.isBlank()) {
                result += value + " ";
            }
        }
        if (callbackstarted)
            result += "CB=ON";
        return result.strip();
    }

    public String getInfo(String key) {
        return getInfo(key, "");
    }

    public String getInfo(String key, String defolt) {
        return CentralGameInfoDict.getOrDefault(key, defolt);
    }

    public void InitPythonCallback() {
        _cb = (PythonCallback) _mod.getGateway().getPythonServerEntryPoint(new Class[]{PythonCallback.class});
    }

    boolean callbackstarted = false;

    public boolean getCallbackServerStatusFast() {
        return callbackstarted;
    }

    public boolean IsCallbackServerStarted() {
        boolean result = false;
        try {
            _cb.isStarted();
            result = true;
        } catch (Py4JException e) {
            // don't print, it's normal if there's errors, it just can't connect
        } catch (Exception e) {
            // unknown error but we won't allow pipeline to crash
        }
        callbackstarted = result;
        return result;
    }

    public PythonCallback get_cb() {
        return _cb;
    }

    AgentState _state = new AgentState();

    public boolean handshake() {
        return true;
    }

    public String saayHellooo(String name) {
        return "Hello, " + name + "!" + Items.SOUL_SAND.getName().getString();
    }

    public AgentState getState() {
        return _state;
    }

    public void setEmotionalState(String state) {
        _state.emotionalState = state;
    }

    public void setFocusPlayerName(String name) {
        _state.focusPlayerName = name;
    }

    /*
     * DO NOT USE IT FROM PYTHON! NOT WORKED!!!
     * We approve implicit state field definition like this from Python part:
     * ```python
     * state = mc.getState()
     * state.emotionalState = "angry"
     * mc.setState(state)
     * ```
     */
    public void setState(AgentState state) {
        _state = state;
    }

    public boolean inGame() {
        return Boolean.TRUE.equals(executeInNetworkThread(AltoClef::inGame));
    }

    public void onStrongChatMessage(WhisperChecker.MessageResult message) {
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                Map<String, String> messageDict = new HashMap<>();
                messageDict.put("user", message.from);
                messageDict.put("msg", message.message);
                messageDict.put("parse_type", "parsed");
                messageDict.put("message_type", "chat");
                // altoclef's WhisperChecker.MessageResult only has from and message fields
                // Extended fields (clan, team, rank, etc.) are autoclef-specific - not available here
                _cb.onVerifedChat(messageDict);
            }
        });
    }

    public void onWeakChatMessage(String message) {
        try {
            executeInNetworkThread(() -> {
                if (IsCallbackServerStarted()) {
                    Map<String, String> messageDict = new HashMap<>();
                    messageDict.put("parse_type", "unparsed");
                    messageDict.put("message_type", "chat");
                    messageDict.put("msg", message);
                    _cb.onVerifedChat(messageDict);
                }
            });
        } catch (Exception e) {
            Debug.logInternal("onWeakChatMessage error: " + e.getMessage());
        }
    }

    public void onCustomMessage(Map<String, String> messageDict) {
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onVerifedChat(messageDict);
            }
        });
    }

    public void ChatMessage(String msg) {
        executeInNetworkThread(() -> {
            if (msg != null) {
                if (AltoClef.inGame() && !msg.isBlank()) {
                    _mod.getMessageSender().enqueueChat(msg, MessagePriority.ASAP);
                }
            }
        });
    }

    public void RunInnerCommand(String command) {
        MinecraftClient.getInstance().execute(() -> {
            AltoClef.getCommandExecutor().execute(command);
        });
    }

    public void CaptchaSolvedSend(String msg, double accuracy) {
        if (AltoClef.inGame()) {
            Debug.logMessage("GOT CAPTCHA SOLVING! >" + msg + "< acc=" + accuracy);
            _mod.getMessageSender().enqueueChat(msg, MessagePriority.ASAP);
        }
    }

    public boolean attackPlayer(String playerName) {
        if (AltoClef.inGame()) {
            return _mod.getDamageTracker().getThreatTable().pursue(playerName);
        }
        return false;
    }

    public boolean avoidPlayer(String playerName) {
        if (AltoClef.inGame()) {
            return _mod.getDamageTracker().getThreatTable().avoid(playerName);
        }
        return false;
    }

    public boolean isAttacking(String playerName) {
        if (AltoClef.inGame()) {
            return _mod.getDamageTracker().getThreatTable().shouldAttack(playerName);
        }
        return false;
    }

    public boolean isAvoiding(String playerName) {
        if (AltoClef.inGame()) {
            return _mod.getDamageTracker().getThreatTable().shouldAvoid(playerName);
        }
        return false;
    }

    public void ExecuteCommand(String cmd) {
        executeInNetworkThread(() -> {
            _mod.getCommandExecutor().execute(cmd);
        });
    }

    public void ConnectToServer(String ip) {
        MinecraftClient.getInstance().execute(() -> {
            _mod.getTaskRunner().gameMenuTaskChain.connectToServer(ip);
        });
    }

    public Map<String, String> CentralGameInfoDict = new HashMap<>();

    public Map<String, String> getServerInfoDict() {
        return CentralGameInfoDict;
    }

    public void UpdateServerInfo(String field, String value) {
        executeInNetworkThread(() -> {
            if (!field.isBlank() && !value.isBlank()) {
                if (CentralGameInfoDict.containsKey(field)) {
                    if (!CentralGameInfoDict.get(field).equals(value)) {
                        putInfo(field, value);
                    }
                } else {
                    putInfo(field, value);
                }
            }
        });
    }

    void putInfo(String field, String value) {
        executeInNetworkThread(() -> {
            CentralGameInfoDict.put(field, value);
            if (IsCallbackServerStarted()) {
                _cb.onUpdateServerInfo(CentralGameInfoDict);
            }
        });
    }

    /**
     * Put a line in the chat ring and NOTHING ELSE.
     *
     * <p>Recording used to be welded to {@link #onChatMessage}, which is reached only through
     * {@code Butler.onReceiveChat} — and AltoClef drops every line carrying the mod's own chat
     * prefix before it gets there, because Butler must not parse the bot's own log as server
     * chat. The consequence was that the mod's OWN errors were the one class of message the ring
     * could never hold, while every scenario carried a green "no command errors in chat".
     * So the two jobs are separated: the ring records what the client saw, Butler still sees only
     * what it should.
     */
    public void recordChat(String msg) {
        if (msg == null) {
            return;
        }
        _chatLog.addLast(msg);
        while (_chatLog.size() > CHAT_LOG_MAX) _chatLog.pollFirst();
    }

    /** Drop the ring, so a scenario's chat is that scenario's and not the container's lifetime. */
    public void clearRecentChat() {
        _chatLog.clear();
    }

    public void onChatMessage(String msg) {
        if (msg != null) {                       // buffer EVERY incoming line (works even with no callback, e.g. the local client)
            _chatLog.addLast(msg);
            while (_chatLog.size() > CHAT_LOG_MAX) _chatLog.pollFirst();
        }
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onChatMessage(msg);
            }
        });
    }

    /** Last `n` chat lines this client saw (oldest first, newest last). PULL access over py4j so
     *  external control reads chat without the log file; returns a plain List for py4j auto-convert. */
    public java.util.List<String> getRecentChat(int n) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(_chatLog);
        int from = Math.max(0, all.size() - Math.max(0, n));
        return new java.util.ArrayList<>(all.subList(from, all.size()));
    }

    public void onDeath(String killer) {
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onDeath(killer);
            }
        });
    }

    public void onKill(String killed) {
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onKill(killed);
            }
        });
    }

    public String executeAgentCommand(String cmd) {
        try {
            if (IsCallbackServerStarted() && cmd != null && !cmd.isBlank()) {
                return _cb.agentCommandRequest(cmd);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Not connected or error when execution queued.";
    }

    public void onAutoclefEvent(String type, String description) {
        try {
            if (IsCallbackServerStarted() && description != null && !description.isBlank() && type != null && !type.isBlank()) {
                _cb.onAutoclefEvent(type, description);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onAutoclefEvent(String description) {
        try {
            if (IsCallbackServerStarted() && description != null && !description.isBlank()) {
                _cb.onAutoclefEvent("mc_executor_event", description);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onCaptchaSolveRequest(byte[] image_bytes) {
        try {
            if (IsCallbackServerStarted()) {
                Debug.logMessage("SENDING TO CALLBACK!");
                _cb.onCaptchaSolveRequest(image_bytes);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onDamage(float amount) {
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onDamage(amount);
            }
        });
    }

    public void onDamageConfirmed(String damaged, String attacker, float amount) {
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onDamageConfirmed(damaged, attacker, amount);
            }
        });
    }

    public Vec3d Nuller() {
        return null;
    }

    public Rotation getGoalRotation() {
        Rotation result = null;
        if (AltoClef.inGame()) {
            Vec3d goal = getCurrentGoal();
            if (goal != null) {
                Rotation targetrot = LookHelper.getLookRotation(_mod, goal);
                result = LookHelper.getLookRotation().subtract(targetrot);
            }
        }
        return result;
    }

    /**
     * Is the bot navigating somewhere right now?
     *
     * <p>An agent-facing primitive has to be honest, and this one answered NO always. It read the
     * legacy engine's path; tungsten drives, so that Optional is permanently empty and every agent
     * asking "are you going anywhere" was told no while the bot walked past it. The name is kept
     * because agents call it; the answer now comes from whichever engine is actually driving.
     */
    public boolean hasBaritoneGoal() {
        if (!AltoClef.inGame()) {
            return false;
        }
        if (adris.altoclef.util.helpers.TungstenHelper.isActive()
                || kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                || kaptainwutax.tungsten.task.BlockPathWalker.isRunning()) {
            return true;
        }
        // LEGACY FALLBACK DELETED (G-1.58: "sample pdLegacy, then delete the fallback").
        // pdLegacy read 0 on every run of every course measured -- the legacy engine never drives,
        // so asking it whether a path exists could only ever answer no.
        return Nav.hasGoal();
    }

    public Vec3d getCurrentGoal() {
        Vec3d result = null;
        if (AltoClef.inGame()) {
            // BARITONE WAS ASKED FIRST HERE, which was the wrong order and survived only because
            // it never had an answer: pdLegacy is 0 on every measured run. Tungsten is the engine;
            // it is now asked directly. (G-1.58: sample pdLegacy, then delete the fallback.)
            if (isTungstenActive()) {
                result = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.TARGET;
            }
        }
        return result;
    }

    public String getCurrentGoalString() {
        Vec3d goal = getCurrentGoal();
        if (goal == null) return "";
        return String.format("%.1f %.1f %.1f", goal.x, goal.y, goal.z);
    }

    public void callPythonMethod() {
        // stub
    }

    public String getLastTalkingPlayer() {
        return last_talking_player;
    }

    public double getHealth() {
        return _mod.getPlayer() == null ? 0 : (double) _mod.getPlayer().getHealth();
    }

    public double getSpeed() {
        return _mod.getPlayer() == null ? 0 : (double) _mod.getPlayer().getMovementSpeed();
    }

    public Vec3d getSpeedVector() {
        return _mod.getPlayer() == null ? new Vec3d(0, 0, 0) : _mod.getPlayer().getVelocity();
    }

    public double getPitch() {
        return _mod.getPlayer() == null ? 0 : _mod.getPlayer().getPitch();
    }

    public double getPitch(double TickDelta) {
        return _mod.getPlayer() == null ? 0 : _mod.getPlayer().getPitch((float) TickDelta);
    }

    public double getYaw() {
        return _mod.getPlayer() == null ? 0 : _mod.getPlayer().getYaw();
    }

    public double getYaw(double TickDelta) {
        return _mod.getPlayer() == null ? 0 : _mod.getPlayer().getYaw((float) TickDelta);
    }

    public Vec3d getAngVector() {
        return _mod.getPlayer() == null ? new Vec3d(0, 0, 0) : _mod.getPlayer().getRotationVector();
    }

    /** Last n per-tick yaw samples (oldest first) — for quantifying aim jitter (the shake):
     *  a test computes the Δyaw sign-reversal rate (few = smooth, many/s = shaky). */
    public java.util.List<Double> getAimSamples(int n) {
        float[] a = kaptainwutax.tungsten.util.AimSampler.last(n);
        java.util.List<Double> out = new java.util.ArrayList<>(a.length);
        for (float y : a) out.add((double) y);
        return out;
    }

    public double getSpeedX() {
        return _mod.getPlayer() == null ? 0 : _mod.getPlayer().getVelocity().getX();
    }

    public double getSpeedY() {
        return _mod.getPlayer() == null ? 0 : _mod.getPlayer().getVelocity().getY();
    }

    public double getSpeedZ() {
        return _mod.getPlayer() == null ? 0 : _mod.getPlayer().getVelocity().getZ();
    }

    public double getSpeedXZ() {
        return _mod.getPlayer() == null ? 0 : Math.sqrt(Math.pow(_mod.getPlayer().getVelocity().getX(), 2) + Math.pow(_mod.getPlayer().getVelocity().getZ(), 2));
    }

    public List<String> getTaskChain() {
        List<String> tasks_list = new ArrayList<>();
        if (_mod.getTaskRunner().getCurrentTaskChain() != null) {
            List<Task> tasks = _mod.getTaskRunner().getCurrentTaskChain().getTasks();
            if (tasks.size() > 0) {
                tasks_list.addAll(tasks.stream().map(task -> task.toString()).toList());
            }
        }
        return tasks_list;
    }

    public String getThreatStatus() {
        String threatStatus = _mod.getDamageTracker().getThreatStatus();
        if (threatStatus != null) {
            return threatStatus;
        }
        return "";
    }

    public int compareThreatsByDistance(PlayerThreat a, PlayerThreat b, PlayerThreat c) {
        if (a != null && a.lastPos != null && a.lastRotationVec != null && b != null && b.lastPos != null && b.lastRotationVec != null && c != null && c.lastPos != null) {
            double probA = c.lastPos.distanceTo(a.lastPos);
            double probB = c.lastPos.distanceTo(b.lastPos);
            return -Double.compare(probB, probA);
        }
        return 0;
    }

    public ArrayList<PlayerThreat> nearsetPlayerThreats(List<AbstractClientPlayerEntity> playerList, int limit) {
        PlayerEntity self = _mod.getPlayer();
        ArrayList<PlayerThreat> nearsetPlayerThreats = new ArrayList<>();
        if (playerList != null && self != null && self.getName() != null && _mod.getWorld() != null) {
            Vec3d selfPos = self.getPos();
            if (selfPos != null) {
                for (AbstractClientPlayerEntity player : playerList) {
                    if (player != null && player.getName() != null) {
                        PlayerThreat playerThreat = _mod.getDamageTracker().getThreatTable().getPlayerThreat(player.getName().getString());
                        if (limit > 0) {
                            if (playerThreat != null) {
                                nearsetPlayerThreats.add(playerThreat);
                            }
                            limit--;
                        } else {
                            break;
                        }
                    }
                }
                PlayerThreat selfThreat = _mod.getDamageTracker().getThreatTable().getPlayerThreat(self.getName().getString());
                if (selfThreat != null) {
                    nearsetPlayerThreats.sort((a, b) -> {
                        return compareThreatsByDistance(a, b, selfThreat);
                    });
                }
            }
        }
        return nearsetPlayerThreats;
    }

    public ArrayList<PlayerThreat> nearsetPlayerThreats(int limit) {
        return nearsetPlayerThreats(_mod.getDamageTracker().getPlayerList(), limit);
    }

    public List<String> nearestPlayersInfo(int limit) {
        List<String> playersStrings = new ArrayList<>();
        ArrayList<PlayerThreat> nearsetPlayerThreats = nearsetPlayerThreats(limit);
        int count = 0;
        for (PlayerThreat threat : nearsetPlayerThreats) {
            if (limit > 0) {
                count += 1;
                // playerThreatInfo is autoclef-specific; use toString() as fallback
                playersStrings.add(threat.toString());
                limit--;
            } else {
                break;
            }
        }
        return playersStrings;
    }

    public String nearestPlayersInfo(int limit, boolean _string) {
        List<String> nearestPlys = nearestPlayersInfo(limit);
        if (!nearestPlys.isEmpty()) {
            return "Nearest players info:\n\n"
                    + String.join("\n", nearestPlayersInfo(limit))
                    + "\n\n---";
        } else {
            return "";
        }
    }

    boolean attackable(AbstractClientPlayerEntity player) {
        return player != null && !player.isInCreativeMode() && !player.isSpectator() && !player.isInvulnerable();
    }

    GameMode getGameMode(AbstractClientPlayerEntity player) {
        if (player == null)
            return GameMode.SURVIVAL;
        if (player.isInCreativeMode())
            return GameMode.CREATIVE;
        if (player.isSpectator())
            return GameMode.SPECTATOR;
        return GameMode.SURVIVAL;
    }

    public List<Map<String, String>> getPlayersInfo(int limit) {
        PlayerEntity self = _mod.getPlayer();
        List<Map<String, String>> list = new ArrayList<>();
        if (self != null) {
            Vec3d selfPos = self.getPos();
            if (selfPos != null) {

                List<AbstractClientPlayerEntity> playerList = _mod.getDamageTracker().getPlayerList();
                Map<String, AbstractClientPlayerEntity> playerListMap = new HashMap<>();

                for (AbstractClientPlayerEntity player : playerList) {
                    if (player != null && player.getName() != null) {
                        playerListMap.put(player.getName().getString(), player);
                    }
                }

                ArrayList<PlayerThreat> nearsetPlayerThreats = nearsetPlayerThreats(playerList, limit);

                for (PlayerThreat threat : nearsetPlayerThreats) {
                    AbstractClientPlayerEntity player = playerListMap.get(threat.name);
                    if (player == null) continue;
                    Text nameText = player.getName();
                    Vec3d pos = threat.lastPos;
                    if (nameText != null && pos != null) {
                        Map<String, String> playerInfoMap = new HashMap<>();
                        playerInfoMap.put("name", threat.name);
                        playerInfoMap.put("health", String.valueOf(threat.lastHealth));
                        playerInfoMap.put("distance", String.valueOf(pos.distanceTo(self.getPos())));
                        playerInfoMap.put("is_looking_at_you_prob", String.valueOf(getLookingProbability(player, self)));
                        Item item = player.getMainHandStack().getItem();
                        if (item != null) {
                            playerInfoMap.put("hand_item", item.toString());
                        } else {
                            playerInfoMap.put("hand_item", "");
                        }
                        playerInfoMap.put("ground_block", getGroundBlockForPlayer(player));
                        playerInfoMap.put("weapon_threat", threat.weaponThreat.toString());
                        playerInfoMap.put("avoiding", String.valueOf(!threat.shouldAvoidTimer.elapsed()));
                        playerInfoMap.put("attacking", String.valueOf(!threat.shouldKillTimer.elapsed()));
                        playerInfoMap.put("in_combat", String.valueOf(!threat.combatEngagementTimer.elapsed()));
                        playerInfoMap.put("recently_damaged", String.valueOf(!threat.damagedTimer.elapsed()));
                        playerInfoMap.put("recently_attacked", String.valueOf(!threat.lastAttackTimer.elapsed()));
                        playerInfoMap.put("attackable", String.valueOf(attackable(player)));
                        playerInfoMap.put("gamemode", getGameMode(player).asString());
                        playerInfoMap.put("godmode", String.valueOf(player.isInvulnerable()));
                        playerInfoMap.put("is_operator", String.valueOf(player.isCreativeLevelTwoOp()));
                        playerInfoMap.put("position", String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z));
                        list.add(playerInfoMap);
                    }
                }
            }
        }
        return list;
    }

    /**
     * NON-PLAYER entities near the bot (mobs + dropped items), nearest first. Players are in
     * getPlayersInfo. Pure data exposure over the existing EntityTracker.getCloseEntities() —
     * closes the TARGET.md Level-0/1 gap "знать ГДЕ мобы/дропы рядом" (agent decides what to do).
     * py4j auto-converts List<Map> to a Python list of dicts. limit<=0 = no cap.
     */
    public List<Map<String, String>> getEntitiesInfo(int limit) {
        List<Map<String, String>> list = new ArrayList<>();
        PlayerEntity self = _mod.getPlayer();
        if (self == null) return list;
        Vec3d selfPos = self.getPos();
        if (selfPos == null) return list;
        List<net.minecraft.entity.Entity> close;
        try {
            close = _mod.getEntityTracker().getCloseEntities();
        } catch (Exception e) {
            return list;
        }
        List<net.minecraft.entity.Entity> ents = new ArrayList<>();
        for (net.minecraft.entity.Entity e : close) {
            if (e == null || e == self || e instanceof PlayerEntity) continue;
            ents.add(e);
        }
        ents.sort((a, b) -> Double.compare(a.squaredDistanceTo(selfPos), b.squaredDistanceTo(selfPos)));
        int n = 0;
        for (net.minecraft.entity.Entity e : ents) {
            if (limit > 0 && n >= limit) break;
            Vec3d pos = e.getPos();
            if (pos == null) continue;
            Map<String, String> m = new HashMap<>();
            try {
                m.put("type", net.minecraft.registry.Registries.ENTITY_TYPE.getId(e.getType()).getPath());
            } catch (Exception ex) {
                m.put("type", "unknown");
            }
            m.put("distance", String.format("%.1f", pos.distanceTo(selfPos)));
            m.put("position", String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z));
            boolean isItem = e instanceof net.minecraft.entity.ItemEntity;
            m.put("is_item", String.valueOf(isItem));
            if (isItem) {
                ItemStack st = ((net.minecraft.entity.ItemEntity) e).getStack();
                m.put("item", st.getItem().toString());
                m.put("count", String.valueOf(st.getCount()));
            }
            boolean living = e instanceof net.minecraft.entity.LivingEntity;
            m.put("is_hostile", String.valueOf(e instanceof net.minecraft.entity.mob.HostileEntity));
            m.put("is_mob", String.valueOf(living && !isItem));
            if (living) m.put("health", String.format("%.0f", ((net.minecraft.entity.LivingEntity) e).getHealth()));
            if (e.getName() != null) m.put("name", e.getName().getString());
            list.add(m);
            n++;
        }
        return list;
    }

    /**
     * Block at an exact coordinate (TARGET.md Level 1 "проверить тип блока по координате").
     * Mirrors getGroundBlock's world access. name+id+hardness+air+replaceable.
     */
    /** Shield-block primitive: raise the shield (must be in a hand) for N ticks. */
    public boolean shieldBlock(int ticks) {
        try {
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                    kaptainwutax.tungsten.task.ShieldBlocker.hold(ticks));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Bow-shot primitive: aim with the trajectory solver (moving-target lead),
     *  charge and release at the named player. Needs a bow in hand and arrows.
     *  Returns false if the player is not visible in the world. */
    public boolean shootArrowAt(String playerName) {
        try {
            if (_mod.getWorld() == null) return false;
            for (net.minecraft.entity.player.PlayerEntity p : _mod.getWorld().getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(playerName)) {
                    net.minecraft.entity.player.PlayerEntity target = p;
                    net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                            kaptainwutax.tungsten.task.BowShooter.shootAt(target));
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Trajectory prediction only (no shot): yaw/pitch/flightTicks for hitting
     *  the named player with a full-draw arrow, or empty map if unsolvable. */
    public Map<String, String> solveArrowAim(String playerName) {
        Map<String, String> m = new HashMap<>();
        try {
            if (_mod.getWorld() == null || _mod.getPlayer() == null) return m;
            for (net.minecraft.entity.player.PlayerEntity p : _mod.getWorld().getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(playerName)) {
                    kaptainwutax.tungsten.combat.TrajectorySolver.Solution sol =
                            kaptainwutax.tungsten.combat.TrajectorySolver.solve(
                                    _mod.getPlayer(), p, 1.0);
                    if (sol != null) {
                        m.put("yaw", String.format("%.2f", sol.yaw));
                        m.put("pitch", String.format("%.2f", sol.pitch));
                        m.put("flightTicks", String.valueOf(sol.flightTicks));
                    }
                    return m;
                }
            }
        } catch (Exception e) {
            m.put("error", String.valueOf(e));
        }
        return m;
    }

    /** Client-side kinematics of the named player as THIS bot sees them: position,
     *  getVelocity(), and onGround. Diagnostic/agent primitive — note getVelocity()
     *  is ~0 for a normally-walking remote player (only knockback sets it), so lead
     *  logic should difference position across ticks rather than trust it. */
    public Map<String, String> targetKinematics(String playerName) {
        return onClientThread(() -> {
            Map<String, String> m = new HashMap<>();
            if (_mod.getWorld() == null) return m;
            for (net.minecraft.entity.player.PlayerEntity p : _mod.getWorld().getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(playerName)) {
                    Vec3d pos = p.getPos();
                    Vec3d v = p.getVelocity();
                    m.put("x", String.format("%.4f", pos.x));
                    m.put("y", String.format("%.4f", pos.y));
                    m.put("z", String.format("%.4f", pos.z));
                    m.put("vx", String.format("%.5f", v.x));
                    m.put("vy", String.format("%.5f", v.y));
                    m.put("vz", String.format("%.5f", v.z));
                    m.put("onGround", String.valueOf(p.isOnGround()));
                    return m;
                }
            }
            return m;
        }, new HashMap<>());
    }

    /** Break-policy prediction: may the tungsten pathfinder mine this block?
     *  Consults BreakRules (config deny lists/zones, block entities,
     *  altoclef protection hook). */
    public boolean canBreakBlock(int x, int y, int z) {
        try {
            if (_mod.getWorld() == null) return false;
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
            return kaptainwutax.tungsten.path.BreakRules.canBreak(
                    _mod.getWorld(), pos, _mod.getWorld().getBlockState(pos));
        } catch (Exception e) {
            return false;
        }
    }

    /** Predict whether the mod is ALLOWED to place a block at a cell — the
     *  protection policy (allowPlace, protected/claim zones, altoclef
     *  place-avoiders via canPlaceHook). Complements canBreakBlock; the agent
     *  checks this before building. canPlace also requires the cell replaceable;
     *  policyAllows is the protection verdict alone (false = protected/claim). */
    public Map<String, Object> canPlaceBlock(int x, int y, int z) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(x, y, z);
            out.put("ok", true);
            out.put("canPlace", kaptainwutax.tungsten.path.PlaceRules.canPlace(client.world, p));
            out.put("policyAllows", kaptainwutax.tungsten.path.PlaceRules.allowedByPolicy(p));
            out.put("replaceable", client.world.getBlockState(p).isReplaceable());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Mark a protected area (claim / private) the mod must NOT build or mine in
     *  — a cube of radius r around (x,y,z). Mirrors the anti-cheat convention
     *  "can't break here → treat the surrounding area as claimed". The agent
     *  calls this for claims it knows about; the mod then routes/builds around
     *  it. Adds to both tungsten placeDenyZones and breakDenyZones. */
    public Map<String, Object> markProtectedArea(int x, int y, int z, int r) {
        int[] zone = new int[]{x - r, y - r, z - r, x + r, y + r, z + r};
        kaptainwutax.tungsten.TungstenConfig cfg = kaptainwutax.tungsten.TungstenConfig.get();
        cfg.placeDenyZones.add(zone);
        cfg.breakDenyZones.add(zone);
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("zone", (x - r) + "," + (y - r) + "," + (z - r) + " .. " + (x + r) + "," + (y + r) + "," + (z + r));
        out.put("protectedZones", cfg.placeDenyZones.size());
        return out;
    }

    /** Clear all runtime protected areas (place + break deny zones). */
    public Map<String, Object> clearProtectedAreas() {
        kaptainwutax.tungsten.TungstenConfig cfg = kaptainwutax.tungsten.TungstenConfig.get();
        cfg.placeDenyZones.clear();
        cfg.breakDenyZones.clear();
        return Map.of("ok", true);
    }

    /** Reachability prediction: can the block-space pathfinder find a route to
     *  (x,y,z) — optionally with wall-breaking allowed? Returns found flag,
     *  rough path size and how many blocks the plan would mine. Runs a real
     *  block-space search (up to ~5s); returns busy=true if the pathfinder is
     *  already searching. */
    public Map<String, String> canReach(int x, int y, int z, boolean withBreaking) {
        Map<String, String> m = new HashMap<>();
        try {
            if (_mod.getWorld() == null || _mod.getPlayer() == null) {
                m.put("error", "no world/player");
                return m;
            }
            if (kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get()) {
                m.put("busy", "true");
                return m;
            }
            kaptainwutax.tungsten.TungstenConfig cfg = kaptainwutax.tungsten.TungstenConfig.get();
            boolean saved = cfg.allowBreak;
            cfg.allowBreak = withBreaking;
            // a stale stop flag from a previous ;stop instantly kills the
            // search loop and returns a 2-node stub — clear it for the probe
            kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.stop.set(false);
            try {
                net.minecraft.util.math.Vec3d goal = new net.minecraft.util.math.Vec3d(x + 0.5, y, z + 0.5);
                // The block-space search occasionally returns a partial best-so-far
                // stub instead of a full route — retry a few times and keep the
                // best (a route that actually reaches the goal wins). Makes the
                // prediction reliable for the cognitive agent.
                java.util.Optional<java.util.List<kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode>> best = java.util.Optional.empty();
                double bestEnd = Double.MAX_VALUE;
                for (int attempt = 0; attempt < 4; attempt++) {
                    kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.stop.set(false);
                    var path = kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.search(
                            _mod.getWorld(), goal, _mod.getPlayer());
                    if (path.isPresent() && !path.get().isEmpty()) {
                        var lastN = path.get().get(path.get().size() - 1);
                        double ed = lastN.getPos(_mod.getWorld()).distanceTo(goal);
                        if (ed < bestEnd) { bestEnd = ed; best = path; }
                        if (ed < 2.5) break; // reached — good enough
                    }
                }
                m.put("found", String.valueOf(best.isPresent()));
                if (best.isPresent()) {
                    m.put("pathSize", String.valueOf(best.get().size()));
                    int breaks = 0;
                    for (kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode n : best.get()) {
                        if (n.hasBreaks()) breaks += n.toBreak.size();
                    }
                    m.put("breaks", String.valueOf(breaks));
                    m.put("endDistance", String.format("%.1f", bestEnd));
                    m.put("reached", String.valueOf(bestEnd < 2.5));
                }
            } finally {
                cfg.allowBreak = saved;
            }
        } catch (Exception e) {
            m.put("error", String.valueOf(e));
        }
        return m;
    }

    public Map<String, String> getBlockAt(int x, int y, int z) {
        Map<String, String> m = new HashMap<>();
        try {
            if (_mod.getWorld() == null) { m.put("error", "no world"); return m; }
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
            net.minecraft.block.BlockState bs = _mod.getWorld().getBlockState(pos);
            m.put("position", x + ", " + y + ", " + z);
            m.put("block", bs.getBlock().getName().getString().toLowerCase());
            try { m.put("id", net.minecraft.registry.Registries.BLOCK.getId(bs.getBlock()).toString()); } catch (Exception e) {}
            m.put("is_air", String.valueOf(bs.isAir()));
            try { m.put("hardness", String.format("%.2f", bs.getHardness(_mod.getWorld(), pos))); } catch (Exception e) {}
            try { m.put("replaceable", String.valueOf(bs.isReplaceable())); } catch (Exception e) {}
        } catch (Exception e) {
            m.put("error", String.valueOf(e));
        }
        return m;
    }

    /**
     * Solid (non-air) blocks in a cube of the given radius around the bot, with hardness —
     * a local terrain/hardness mini-map (TARGET.md Level 2). radius capped at 5 (payload + token
     * economy: only non-air returned). The agent reasons over it; no hardcoded behavior.
     */
    public List<Map<String, String>> getBlocksAround(int radius) {
        List<Map<String, String>> list = new ArrayList<>();
        try {
            if (_mod.getPlayer() == null || _mod.getWorld() == null) return list;
            int r = Math.max(1, Math.min(radius, 5));
            net.minecraft.util.math.BlockPos c = _mod.getPlayer().getBlockPos();
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        net.minecraft.util.math.BlockPos p = c.add(dx, dy, dz);
                        net.minecraft.block.BlockState bs = _mod.getWorld().getBlockState(p);
                        if (bs.isAir()) continue;
                        Map<String, String> m = new HashMap<>();
                        m.put("position", p.getX() + ", " + p.getY() + ", " + p.getZ());
                        m.put("block", bs.getBlock().getName().getString().toLowerCase());
                        try { m.put("hardness", String.format("%.2f", bs.getHardness(_mod.getWorld(), p))); } catch (Exception e) {}
                        list.add(m);
                    }
        } catch (Exception e) {}
        return list;
    }

    /**
     * Top-down SURFACE map around the bot (TARGET.md Level 2/3 — "карта поверхности").
     * For each (dx,dz) column within radius, the HIGHEST non-air block, scanning from
     * bot.y+8 down to bot.y-8. Gives a height + hardness grid: where the ground/roof is,
     * how hard it is to dig, so the agent can walk/dig/build over it. radius capped at 6.
     * Each row: {dx, dz, top_dy (height of surface relative to bot feet, "-" = nothing),
     * block, hardness}. The agent renders/reasons over it; no hardcoded behavior.
     */
    public List<Map<String, String>> getSurfaceMap(int radius) {
        List<Map<String, String>> list = new ArrayList<>();
        try {
            if (_mod.getPlayer() == null || _mod.getWorld() == null) return list;
            int r = Math.max(1, Math.min(radius, 6));
            net.minecraft.util.math.BlockPos c = _mod.getPlayer().getBlockPos();
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    Map<String, String> m = new HashMap<>();
                    m.put("dx", String.valueOf(dx));
                    m.put("dz", String.valueOf(dz));
                    boolean found = false;
                    for (int dy = 8; dy >= -8; dy--) {
                        net.minecraft.util.math.BlockPos p = c.add(dx, dy, dz);
                        net.minecraft.block.BlockState bs = _mod.getWorld().getBlockState(p);
                        if (bs.isAir()) continue;
                        m.put("top_dy", String.valueOf(dy));
                        m.put("block", bs.getBlock().getName().getString().toLowerCase());
                        try { m.put("hardness", String.format("%.2f", bs.getHardness(_mod.getWorld(), p))); } catch (Exception e) {}
                        found = true;
                        break;
                    }
                    if (!found) m.put("top_dy", "-");
                    list.add(m);
                }
        } catch (Exception e) {}
        return list;
    }

    public LinkedHashMap<String, Map<String, String>> getPlayersInfo(int limit, boolean dictFormat) {
        LinkedHashMap<String, Map<String, String>> map = new LinkedHashMap<>();
        for (Map<String, String> playerInfo : getPlayersInfo(limit)) {
            map.put(playerInfo.get("name"), playerInfo);
        }
        return map;
    }

    public String parsePlayersInfoToString(Map<String, Map<String, String>> playersInfo) {
        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, Map<String, String>> entry : playersInfo.entrySet()) {
            String playerName = entry.getKey();
            Map<String, String> playerInfo = entry.getValue();

            String health = playerInfo.get("health");
            String distance = playerInfo.get("distance");
            String item = playerInfo.get("item");
            String groundBlock = playerInfo.get("groundBlock");

            result.append("Name: ").append(playerName)
                    .append(", Health: ").append(health != null ? health : "N/A")
                    .append(", Distance: ").append(distance != null ? distance : "N/A")
                    .append(", Hand item: ").append(item != null ? item : "N/A")
                    .append(", Ground block: ").append(groundBlock != null ? groundBlock : "N/A")
                    .append("\n");
        }

        return result.toString();
    }

    public void setPipeline(String pipelineName) {
        try {
            Pipeline p = Pipeline.valueOf(pipelineName);
            AltoClef.setPipeline(p);
            Debug.logMessage("Pipeline set to: " + pipelineName);
        } catch (IllegalArgumentException e) {
            Debug.logMessage("Unknown pipeline: " + pipelineName);
        }
    }

    /**
     * Execute control actions from Python agent.
     * Receives a dictionary with control states (0 or 1).
     * Example: {"forward": 1, "jump": 0, "attack": 0, "camera": [0.0, 0.0]}
     *
     * @param controlDict Dictionary with button states
     */
    public void executeAgentActions(Map<String, Object> controlDict) {
        executeInNetworkThread(() -> {
            AgentActionButtons.executeActions(_mod, controlDict);
        });
    }

    // ── Low-level orientation & control toolkit ─────────────────────────────────
    // Generic primitives for the python agent: read the open screen / inventory as
    // data, click any slot, select hotbar, look anywhere, use/attack entities,
    // check reach. No game-specific logic here — the agent decides what to do.

    private <T> T onClientThread(java.util.function.Supplier<T> sup, T fallback) {
        try {
            CompletableFuture<T> fut = new CompletableFuture<>();
            MinecraftClient.getInstance().execute(() -> {
                try { fut.complete(sup.get()); } catch (Exception e) { fut.completeExceptionally(e); }
            });
            return fut.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Debug.logInternal("onClientThread error: " + e.getMessage());
            return fallback;
        }
    }

    private static Map<String, Object> slotEntry(int index, ItemStack st) {
        Map<String, Object> m = new HashMap<>();
        m.put("index", index);
        m.put("empty", st.isEmpty());
        if (!st.isEmpty()) {
            m.put("item", net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString());
            m.put("name", st.getName().getString());
            m.put("count", st.getCount());
        }
        return m;
    }

    /** Current in-game NICK (offline session username) — for verifying @nick / nick changes. */
    public String getUsername() {
        return AltoClef.getSelfName();
    }

    /** Open screen (chest/server menu/inventory) as data: title + every slot with
     *  item id, display name (server menus put their labels here) and count. */
    public Map<String, Object> getOpenScreen() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.currentScreen == null) {
                out.put("open", false);
                return out;
            }
            out.put("open", true);
            out.put("screen", client.currentScreen.getClass().getSimpleName());
            try { out.put("title", client.currentScreen.getTitle().getString()); } catch (Exception ignored) {}
            List<Map<String, Object>> slots = new ArrayList<>();
            net.minecraft.screen.ScreenHandler h = client.player.currentScreenHandler;
            for (int i = 0; i < h.slots.size(); i++) {
                slots.add(slotEntry(i, h.getSlot(i).getStack()));
            }
            out.put("slots", slots);
            out.put("syncId", h.syncId);
            return out;
        }, Map.of("open", false, "error", "client thread timeout"));
    }

    /** Full player inventory: 36 main slots + armor + offhand + selected hotbar. */
    public Map<String, Object> getInventoryFull() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            var player = MinecraftClient.getInstance().player;
            if (player == null) { out.put("ok", false); return out; }
            var inv = player.getInventory();
            List<Map<String, Object>> main = new ArrayList<>();
            for (int i = 0; i < inv.size(); i++) main.add(slotEntry(i, inv.getStack(i)));
            out.put("slots", main);                       // 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand
            int sel = adris.altoclef.multiversion.entity.PlayerVer.getSelectedSlot(inv);
            out.put("selectedHotbar", sel);
            out.put("heldItem", slotEntry(sel, player.getMainHandStack()));
            out.put("ok", true);
            return out;
        }, Map.of("ok", false, "error", "client thread timeout"));
    }

    /** Click a slot of the CURRENT screen by window index.
     *  button: 0=LMB 1=RMB; action: PICKUP | QUICK_MOVE (shift) | THROW | SWAP. */
    public boolean clickUiSlot(int windowSlot, int button, String action) {
        net.minecraft.screen.slot.SlotActionType type;
        try {
            type = net.minecraft.screen.slot.SlotActionType.valueOf(action.toUpperCase());
        } catch (Exception e) {
            type = net.minecraft.screen.slot.SlotActionType.PICKUP;
        }
        final var ftype = type;
        return Boolean.TRUE.equals(onClientThread(() -> {
            var slot = adris.altoclef.util.slots.Slot.getFromCurrentScreen(windowSlot);
            _mod.getSlotHandler().forceAllowNextSlotAction();
            _mod.getSlotHandler().clickSlot(slot, button, ftype);
            return true;
        }, false));
    }

    /** Robust "click the menu item named X" — the fix for the intermittent
     *  empty-slot reads on headless (the server re-sends the container and a
     *  single getOpenScreen sample can land in an empty window, breaking
     *  name-based navigation and autojoin). Retries the read across ticks
     *  (up to ~timeoutMs) until a slot's display name contains any of the
     *  given substrings (case-insensitive, color codes stripped), then clicks
     *  it. Reuses the same read/click plumbing — one source of truth.
     *  Returns the clicked slot index, or -1 if not found in time. */
    public int clickMenuByName(java.util.List<String> names, int button, String action, int timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
        net.minecraft.screen.slot.SlotActionType type;
        try { type = net.minecraft.screen.slot.SlotActionType.valueOf(action.toUpperCase()); }
        catch (Exception e) { type = net.minecraft.screen.slot.SlotActionType.PICKUP; }
        final var ftype = type;
        while (System.currentTimeMillis() < deadline) {
            Integer idx = onClientThread(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player == null || client.currentScreen == null) return null;
                var h = client.player.currentScreenHandler;
                for (int i = 0; i < h.slots.size(); i++) {
                    var st = h.getSlot(i).getStack();
                    if (st == null || st.isEmpty()) continue;
                    String nm = st.getName().getString();
                    if (nm == null) continue;
                    String low = nm.toLowerCase();
                    for (String want : names) {
                        if (want != null && low.contains(want.toLowerCase())) return i;
                    }
                }
                return null;
            }, null);
            if (idx != null) {
                final int fi = idx;
                Boolean ok = onClientThread(() -> {
                    var slot = adris.altoclef.util.slots.Slot.getFromCurrentScreen(fi);
                    _mod.getSlotHandler().forceAllowNextSlotAction();
                    _mod.getSlotHandler().clickSlot(slot, button, ftype);
                    return true;
                }, false);
                if (Boolean.TRUE.equals(ok)) return fi;
            }
            try { Thread.sleep(120); } catch (InterruptedException ie) { break; }
        }
        return -1;
    }

    /** Select hotbar slot 0-8. */
    public boolean selectHotbar(int slot) {
        if (slot < 0 || slot > 8) return false;
        return Boolean.TRUE.equals(onClientThread(() -> {
            var player = MinecraftClient.getInstance().player;
            if (player == null) return false;
            adris.altoclef.multiversion.entity.PlayerVer.setSelectedSlot(player.getInventory(), slot);
            return true;
        }, false));
    }

    /** Close any open screen. */
    public boolean closeOpenScreen() {
        return Boolean.TRUE.equals(onClientThread(() -> {
            adris.altoclef.util.helpers.StorageHelper.closeScreen();
            return true;
        }, false));
    }

    /** Absolute look at a world point (eyes follow x,y,z). */
    public boolean lookAt(double x, double y, double z) {
        return Boolean.TRUE.equals(onClientThread(() -> {
            var player = MinecraftClient.getInstance().player;
            if (player == null) return false;
            Rotation rot = LookHelper.getLookRotation(_mod, new Vec3d(x, y, z));
            _mod.getInputControls().forceLook(rot.getYaw(), rot.getPitch());
            return true;
        }, false));
    }

    /** Look straight at a player/entity by name. */
    public boolean lookAtPlayer(String playerName) {
        return Boolean.TRUE.equals(onClientThread(() -> {
            PlayerEntity p = getEntity(playerName);
            if (p == null) return false;
            Vec3d aim = LookHelper.getOptimalAimPoint(_mod, p);
            Rotation rot = LookHelper.getLookRotation(_mod, aim);
            _mod.getInputControls().forceLook(rot.getYaw(), rot.getPitch());
            return true;
        }, false));
    }

    /** Relative camera turn by degrees. (doubles: py4j maps python numbers to double) */
    public boolean rotateCamera(double dYaw, double dPitch) {
        return Boolean.TRUE.equals(onClientThread(() -> {
            var player = MinecraftClient.getInstance().player;
            if (player == null) return false;
            _mod.getInputControls().forceLook((float) (player.getYaw() + dYaw),
                    (float) Math.max(-90.0, Math.min(90.0, player.getPitch() + dPitch)));
            return true;
        }, false));
    }

    private static kaptainwutax.tungsten.path.movements.Input inputByName(String name) {
        switch (name.toLowerCase()) {
            case "forward": return kaptainwutax.tungsten.path.movements.Input.MOVE_FORWARD;
            case "back": return kaptainwutax.tungsten.path.movements.Input.MOVE_BACK;
            case "left": return kaptainwutax.tungsten.path.movements.Input.MOVE_LEFT;
            case "right": return kaptainwutax.tungsten.path.movements.Input.MOVE_RIGHT;
            case "jump": return kaptainwutax.tungsten.path.movements.Input.JUMP;
            case "sneak": return kaptainwutax.tungsten.path.movements.Input.SNEAK;
            case "sprint": return kaptainwutax.tungsten.path.movements.Input.SPRINT;
            case "attack": return kaptainwutax.tungsten.path.movements.Input.CLICK_LEFT;
            case "use": return kaptainwutax.tungsten.path.movements.Input.CLICK_RIGHT;
            default: return null;
        }
    }

    /** Tap a control once: forward/back/left/right/jump/sneak/sprint/attack/use. */
    public boolean tapKey(String name) {
        var input = inputByName(name);
        if (input == null) return false;
        return Boolean.TRUE.equals(onClientThread(() -> {
            _mod.getInputControls().tryPress(input);
            return true;
        }, false));
    }

    /** Hold a control for ms milliseconds (released by a scheduled client task). */
    public boolean holdKey(String name, int ms) {
        var input = inputByName(name);
        if (input == null) return false;
        Boolean started = onClientThread(() -> {
            _mod.getInputControls().hold(input);
            return true;
        }, false);
        if (!Boolean.TRUE.equals(started)) return false;
        new Thread(() -> {
            try { Thread.sleep(Math.max(20, Math.min(ms, 10000))); } catch (InterruptedException ignored) {}
            MinecraftClient.getInstance().execute(() -> _mod.getInputControls().release(input));
        }, "holdKey-release").start();
        return true;
    }

    /** Right-click (use=true) or left-click (use=false) an entity by name —
     *  looks at it first, fails honestly if out of reach. */
    public Map<String, Object> interactEntity(String playerName, boolean use) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            PlayerEntity p = getEntity(playerName);
            if (p == null) { out.put("ok", false); out.put("reason", "entity not found"); return out; }
            if (!LookHelper.canHitEntity(_mod, p)) {
                out.put("ok", false); out.put("reason", "out of reach");
                out.put("distance", MinecraftClient.getInstance().player.distanceTo(p));
                return out;
            }
            Vec3d aim = LookHelper.getOptimalAimPoint(_mod, p);
            Rotation rot = LookHelper.getLookRotation(_mod, aim);
            _mod.getInputControls().forceLook(rot.getYaw(), rot.getPitch());
            if (use) {
                var res = MinecraftClient.getInstance().interactionManager
                        .interactEntity(MinecraftClient.getInstance().player, p, net.minecraft.util.Hand.MAIN_HAND);
                out.put("result", res.toString());
            } else {
                _mod.getControllerExtras().attack(p, true);
                out.put("result", "attacked");
            }
            out.put("ok", true);
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** A physical mouse click via the KEY path (works headless, unlike the
     *  interactionManager wrapper). "left"=attack/break, "right"=use/interact,
     *  "middle"=pick block. One-frame press, auto-released by the altoclef tick.
     *  Aim first (lookAt/rotateCamera); vanilla routes the click to whatever is
     *  under the crosshair (block, entity, item frame…). Single source of truth
     *  is InputControls — no duplicated press logic. */
    public Map<String, Object> mouseClick(String button) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            String b = button == null ? "left" : button.toLowerCase();
            switch (b) {
                case "left" -> _mod.getInputControls().tryPress(kaptainwutax.tungsten.path.movements.Input.CLICK_LEFT);
                case "right" -> _mod.getInputControls().tryPress(kaptainwutax.tungsten.path.movements.Input.CLICK_RIGHT);
                case "middle" -> {
                    var k = client.options.pickItemKey;
                    k.setPressed(true);
                    net.minecraft.client.option.KeyBinding.onKeyPressed(k.getDefaultKey());
                    // released next tick by the client's own key handling
                }
                default -> { out.put("ok", false); out.put("reason", "button must be left/right/middle"); return out; }
            }
            out.put("ok", true);
            out.put("button", b);
            var t = client.crosshairTarget;
            out.put("hit", t == null ? "NONE" : t.getType().toString());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Click at pixel coordinates inside the currently OPEN GUI screen (menus,
     *  sign editors, custom plugin GUIs) — Screen.mouseClicked/Released in the
     *  scaled GUI space. For inventory SLOTS use clickUiSlot (don't duplicate).
     *  x/y are scaled-GUI pixels; read them off getScreenshot then divide by
     *  the GUI scale, or pass raw and set scaled=false to auto-convert. */
    public Map<String, Object> screenClickAt(double x, double y, String button, boolean scaled) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            net.minecraft.client.gui.screen.Screen s = client.currentScreen;
            if (s == null) { out.put("ok", false); out.put("reason", "no screen open"); return out; }
            double sx = x, sy = y;
            if (!scaled) {
                double f = client.getWindow().getScaleFactor();
                sx = x / f; sy = y / f;
            }
            int btn = switch (button == null ? "left" : button.toLowerCase()) {
                case "right" -> 1; case "middle" -> 2; default -> 0; };
            // Screen.mouseClicked's signature churns across MC versions
            // (1.21.11 wraps args in a Click record). Dispatch reflectively to
            // the (double,double,int) overload where it exists; degrade cleanly
            // otherwise (inventory GUIs should use clickUiSlot regardless).
            try {
                java.lang.reflect.Method mc2 = findMouse(s, "mouseClicked");
                java.lang.reflect.Method mr = findMouse(s, "mouseReleased");
                if (mc2 == null) {
                    out.put("ok", false);
                    out.put("reason", "screen click unsupported on this MC version — use clickUiSlot for inventory slots");
                    out.put("screen", s.getClass().getSimpleName());
                    return out;
                }
                mc2.setAccessible(true);
                mc2.invoke(s, sx, sy, btn);
                if (mr != null) { mr.setAccessible(true); mr.invoke(s, sx, sy, btn); }
            } catch (Throwable t) {
                out.put("ok", false); out.put("reason", "click failed: " + t); return out;
            }
            out.put("ok", true);
            out.put("screen", s.getClass().getSimpleName());
            out.put("at", String.format("%.0f,%.0f", sx, sy));
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Find a Screen mouse method taking (double,double,int) regardless of
     *  which MC version's signature it is. Null if this version uses the
     *  Click-record form (wired later). */
    private static java.lang.reflect.Method findMouse(Object screen, String name) {
        for (java.lang.reflect.Method m : screen.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == double.class && p[1] == double.class && p[2] == int.class) {
                return m;
            }
        }
        return null;
    }

    /** Right-click whatever ENTITY is currently under the crosshair (item
     *  frames, armor stands, mobs, lobby menu entities) — an interactEntity
     *  packet, not interactItem. Aim with lookAt first. Rotating a captcha
     *  item frame is exactly this. Returns the ActionResult + entity type. */
    public Map<String, Object> interactCrosshairEntity() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) {
                out.put("ok", false); out.put("reason", "not in game"); return out;
            }
            var hit = client.crosshairTarget;
            if (!(hit instanceof net.minecraft.util.hit.EntityHitResult ehr)) {
                out.put("ok", false);
                out.put("reason", "no entity under crosshair — lookAt it first");
                return out;
            }
            net.minecraft.entity.Entity target = ehr.getEntity();
            var res = client.interactionManager.interactEntity(
                    client.player, target, net.minecraft.util.Hand.MAIN_HAND);
            client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            out.put("ok", true);
            out.put("result", res.toString());
            out.put("entityType", target.getType().toString());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Can I reach/hit this entity from here? Distance + verdict. */
    public Map<String, Object> reachability(String playerName) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            PlayerEntity p = getEntity(playerName);
            var me = MinecraftClient.getInstance().player;
            if (p == null || me == null) { out.put("exists", false); return out; }
            out.put("exists", true);
            out.put("distance", me.distanceTo(p));
            out.put("canHit", LookHelper.canHitEntity(_mod, p));
            out.put("inRange", _mod.getControllerExtras().inRange(p));
            return out;
        }, Map.of("exists", false, "error", "client thread timeout"));
    }

    /** Right-click with the held item (use compass/menu items) — direct
     *  interactionManager call, works headless where key emulation does not. */
    public Map<String, Object> useHeldItem() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) {
                out.put("ok", false);
                out.put("reason", "not in game");
                return out;
            }
            var res = client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
            out.put("ok", true);
            out.put("result", res.toString());
            out.put("held", slotEntry(-1, client.player.getMainHandStack()));
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** PLACE the held block against the block face under the crosshair — a REAL block-place
     *  interaction (interactionManager.interactBlock), NOT interactItem (useHeldItem). Aim at a
     *  block face first (lookAt). Touches NO inventory, so it WORKS on anti-cheat servers that
     *  cancel altoclef's inventory slot actions (where @place/PlaceBlockNearbyTask gets stuck).
     *  The block must already be the HELD item (selectHotbar to its hotbar slot first).
     *  Returns count_before/after (after = before-1 means a block was placed) + the ActionResult. */
    public Map<String, Object> placeBlockLooking() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) {
                out.put("ok", false); out.put("reason", "not in game"); return out;
            }
            var hit = client.crosshairTarget;
            if (!(hit instanceof net.minecraft.util.hit.BlockHitResult bhr)
                    || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
                out.put("ok", false);
                out.put("reason", "no block under crosshair — lookAt a block face first");
                return out;
            }
            int before = client.player.getMainHandStack().getCount();
            var res = client.interactionManager.interactBlock(
                    client.player, net.minecraft.util.Hand.MAIN_HAND, bhr);
            client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            out.put("ok", true);
            out.put("result", res.toString());
            out.put("target", bhr.getBlockPos().toShortString());
            out.put("side", bhr.getSide().toString());
            out.put("held", client.player.getMainHandStack().getItem().toString());
            out.put("count_before", before);
            out.put("count_after", client.player.getMainHandStack().getCount());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Place a block AT world position (x,y,z) — the atomic building primitive
     *  (TODO block 7). Auto-selects a placeable block from the hotbar (unless
     *  one is already held), finds a solid neighbour to click against, aims at
     *  the shared face and interactBlocks so the new block lands in the target
     *  cell. Player must already be within reach (~4.5); tungsten pathfinding
     *  to get in range is the physics-integration layer on top. Returns the
     *  supporting side + whether the target cell became non-air. */
    public Map<String, Object> placeBlockAt(int x, int y, int z) {
        return onClientThread(() -> placeBlockAtRaw(x, y, z),
                Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Placement core — MUST be called ON the client thread. Shared by
     *  placeBlockAt() (wraps onClientThread) and fillSelection() (already on
     *  the client thread), so the placing logic stays single-source and we
     *  never nest onClientThread (nesting deadlocks the render thread). */
    private Map<String, Object> placeBlockAtRaw(int x, int y, int z) {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) {
                out.put("ok", false); out.put("reason", "not in game"); return out;
            }
            var world = client.player.getEntityWorld();
            net.minecraft.util.math.BlockPos target = new net.minecraft.util.math.BlockPos(x, y, z);
            if (!world.getBlockState(target).isReplaceable()) {
                out.put("ok", false); out.put("reason", "target not replaceable (already occupied)"); return out;
            }
            // protection policy (protected areas / claims / altoclef place-avoiders)
            if (!kaptainwutax.tungsten.path.PlaceRules.canPlace(world, target)) {
                out.put("ok", false); out.put("reason", "placing denied (protected area)"); return out;
            }
            // ensure a placeable block is held (else pick the first block item in the hotbar)
            if (!(client.player.getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem)) {
                int found = -1;
                for (int i = 0; i < 9; i++) {
                    if (client.player.getInventory().getStack(i).getItem() instanceof net.minecraft.item.BlockItem) { found = i; break; }
                }
                if (found < 0) { out.put("ok", false); out.put("reason", "no block item in hotbar"); return out; }
                adris.altoclef.multiversion.entity.PlayerVer.setSelectedSlot(client.player.getInventory(), found);
            }
            // find a solid neighbour to place against; aim at the shared face
            net.minecraft.util.math.Vec3d eye = client.player.getEyePos();
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
                net.minecraft.util.math.BlockPos support = target.offset(dir);
                if (world.getBlockState(support).isAir()
                        || world.getBlockState(support).getCollisionShape(world, support).isEmpty()) continue;
                // the face of `support` that borders target points opposite to dir
                net.minecraft.util.math.Direction side = dir.getOpposite();
                net.minecraft.util.math.Vec3d faceCenter = net.minecraft.util.math.Vec3d.ofCenter(support)
                        .add(net.minecraft.util.math.Vec3d.of(side.getVector()).multiply(0.5));
                if (eye.squaredDistanceTo(faceCenter) > 5.0 * 5.0) continue;
                // aim there and place against the crosshair block
                net.minecraft.util.math.Vec3d d = faceCenter.subtract(eye);
                // Humanized aim via the vanilla mouse pipeline (changeLookDirection,
                // pixel-quantized) — never setYaw/setPitch, which anti-cheats flag.
                // One-shot call, so apply the delta immediately (mouse-like packet).
                float wantYaw = (float) Math.toDegrees(-Math.atan2(d.x, d.z));
                float wantPitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                double sens = client.options.getMouseSensitivity().getValue();
                double f = sens * 0.6 + 0.2;
                double sensScale = f * f * f * 8.0;
                double degPerPixel = sensScale * 0.15;
                double dPitchDeg = net.minecraft.util.math.MathHelper.wrapDegrees(wantPitch - client.player.getPitch());
                double dYawDeg = net.minecraft.util.math.MathHelper.wrapDegrees(wantYaw - client.player.getYaw());
                client.player.changeLookDirection(
                        Math.round(dYawDeg / degPerPixel) * sensScale,
                        Math.round(dPitchDeg / degPerPixel) * sensScale);
                // THE LAST FORGED PLACEMENT IN THE PROJECT DIED HERE. What stood on this line
                // built a BlockHitResult out of the face centre and sent it — a packet claiming a
                // click the player had not made, on a face the camera had not yet reached. The
                // camera was turned one line above and the raytrace only agrees a frame later, so
                // the claim was not merely unverified, it was usually false. Three other sites did
                // the same and were fixed on 2026-07-30; this one was missed because it lives in
                // the py4j surface rather than in tungsten.
                //
                // Now: aim, then ask the game where the player is ACTUALLY looking
                // (RealPlacement.readyToPlace -> a live raytrace, baritone's
                // MovementHelper.attemptToPlaceABlock:840-851), and place with THAT hit through
                // the shared rate gate, so this lever cannot out-place a human either.
                var realHit = kaptainwutax.tungsten.helpers.RealPlacement.readyToPlace(client, target);
                if (realHit == null) {
                    out.put("ok", false);
                    out.put("reason", "aim has not converged on " + target.toShortString()
                            + " — the crosshair is not on a face that would fill it");
                    out.put("support", support.toShortString());
                    out.put("side", side.toString());
                    out.put("placed", false);
                    return out;
                }
                boolean sent = kaptainwutax.tungsten.helpers.BlockPlaceHelper.tryPlace(realHit);
                out.put("ok", true);
                out.put("sent", sent);
                out.put("cooldown", !sent);   // rate gate: one place per 4 ticks, as in vanilla
                out.put("support", support.toShortString());
                out.put("side", side.toString());
                out.put("placed", !world.getBlockState(target).isAir());
                return out;
            }
            out.put("ok", false); out.put("reason", "no reachable supporting face");
            return out;
    }

    /** Epic sneak-bridge: extend a floor of placed blocks across a gap in a
     *  cardinal direction ("north"/"south"/"east"/"west", or "" to infer from
     *  facing) for `blocks` blocks. Needs a block item in hand/hotbar. Ticks
     *  autonomously; poll bridgePlaced()/bridgeActive(). (TODO 7.2) */
    public boolean bridgeForward(String direction, int blocks) {
        try {
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                    kaptainwutax.tungsten.task.BridgeTask.start(direction, blocks));
            return true;
        } catch (Exception e) { return false; }
    }
    /** Godbridge TOWARD a target position (bedwars: bridge to enemy island). */
    public boolean bridgeTo(int x, int y, int z) {
        try {
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                    kaptainwutax.tungsten.task.BridgeTask.startTo(x, y, z));
            return true;
        } catch (Exception e) { return false; }
    }
    public boolean bridgeActive() { return kaptainwutax.tungsten.task.BridgeTask.isActive(); }
    public int bridgePlaced() { return kaptainwutax.tungsten.task.BridgeTask.getPlaced(); }

    /** Pillar up to Y by placing blocks under self (#46). Equip a block first. */
    public boolean pillarTo(int y) {
        try {
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                    kaptainwutax.tungsten.task.PillarTask.startTo(y));
            return true;
        } catch (Exception e) { return false; }
    }
    /**
     * Refuse to BREAK anything inside a cube, from now until the bot is told otherwise.
     *
     * <p>WHAT IT IS FOR. On a server with land claims the agent often knows a region is protected
     * before the bot has wasted a swing finding out -- a spawn area, someone's base, a plot border.
     * The mod already has the machinery (the survival chain installs exactly this when it believes a
     * break was refused); it simply had no lever the agent could pull. This is the lever: the agent
     * decides WHERE, the mod enforces it.
     *
     * <p>WHAT IT DOES NOT DO. It does not expire on its own and it does not stack -- installing a
     * new region REPLACES the previous one, because this uses the single "extra" avoid slot rather
     * than the push/pop stack. Clear it with {@link #allowBreakingAnywhere()}. A task ending also
     * clears it when {@code clearBansOnTaskEnd} is on, which is the point of that flag.
     *
     * <p>SECOND USE, and the reason it exists today: it makes a rare bug testable on demand. The
     * temporary ban leaking between runs needs a ban to have been installed in an earlier run, which
     * needs a break to have FAILED -- and on a clean arena breaks do not fail, so an A/B waits for a
     * trigger the course never produces. With this the trigger is caused rather than awaited: install
     * a region, end the task, and read whether the next task inherits it.
     *
     * @return the region and the resulting predicate count, so the caller can verify it applied
     *         rather than assume -- a setting that silently fails to apply is how an A/B ends up
     *         measuring a build against itself.
     */
    public Map<String, Object> avoidBreakingRegion(int x, int y, int z, int radius) {
        final net.minecraft.util.math.BlockPos centre =
                new net.minecraft.util.math.BlockPos(x, y, z);
        final int r = Math.max(0, radius);
        _mod.getBehaviour().avoidBlockBreakingExtra(pos ->
                Math.abs(pos.getX() - centre.getX()) <= r
                        && Math.abs(pos.getY() - centre.getY()) <= r
                        && Math.abs(pos.getZ() - centre.getZ()) <= r);
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("centre", x + "," + y + "," + z);
        out.put("radius", r);
        out.put("predicatesNow", baritone.altoclef.AltoClefSettings.avoidPredCount);
        return out;
    }

    /** Drop the region installed by {@link #avoidBreakingRegion}. Safe when none is installed. */
    public Map<String, Object> allowBreakingAnywhere() {
        _mod.getBehaviour().resetAvoidBlockBreakingExtra();
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("predicatesNow", baritone.altoclef.AltoClefSettings.avoidPredCount);
        return out;
    }

    /**
     * The prefix tungsten commands are typed with, e.g. ";" for {@code ;goto}.
     *
     * <p>An agent (or a bench course) driving tungsten by chat has to know this, and until now the
     * only way to get it was to hardcode the character and hope -- {@code stopPathing} reads it
     * from {@code TungstenMod.getCommandPrefix()} on the Java side, but nothing exposed it. A
     * hardcoded prefix is a silent failure the day it changes: the chat line simply is not a
     * command any more, and nothing reports that.
     */
    public String tungstenPrefix() {
        return kaptainwutax.tungsten.TungstenMod.getCommandPrefix();
    }

    public boolean pillarActive() { return kaptainwutax.tungsten.task.PillarTask.isActive(); }

    /**
     * Is a slime crossing running right now? A crossing is one manoeuvre — hold heading and
     * sprint across a whole chain of bounces — so it cannot be read off the walker's state.
     * Exposed here because the in-game chat is not a reliable channel for this: on the bounce
     * course the physics search floods it and the client reports "Chat overflow, message
     * dropped" dozens of times a run, so a task's log line going missing proves nothing.
     */
    public boolean slimeCrossActive() { return kaptainwutax.tungsten.task.SlimeBounceTask.isActive(); }

    /** How many times the walker's BFS tick ran — zero means the walker never drove at all. */
    public int walkerBfsTicks() { return kaptainwutax.tungsten.task.BlockPathWalker.bfsTicks; }

    /** How many times the walker saw a waypoint standing on slime (the crossing trigger). */
    public int walkerSlimeWpSeen() { return kaptainwutax.tungsten.task.BlockPathWalker.slimeWpSeen; }

    /** Swings that landed while falling, i.e. CRITS (+50% damage), and total swings. The
     *  ratio is the only honest way to tell whether crit timing works: the previous attempt
     *  at this shipped a helper with zero callers and no way to see that. */
    /** Where the punk task actually spends its ticks: called, inactive, no target, holding
     *  because the target is over void, in combat, or (re)starting the chase. */
    /** Context of the first few sub-2hp hits attributed to our swings: size, ms since the
     *  swing, the target's fall distance, whether it was on the ground, and how far away. */
    /** Causes of every sub-2hp hit THIS client took, straight from vanilla's DamageSource. */
    public String smallHitCauses() {
        return kaptainwutax.tungsten.combat.DamageWatch.smallHitCauses();
    }

    /** Shapes of the route edges the movement queue had to truncate, commonest first.
     *  Read as dx,dy,dz -- whatever tops this list is the movement class worth writing next. */
    public String noClassShapes() {
        return kaptainwutax.tungsten.path.movements.MovementQueue.noClassShapes();
    }

    /** Who released MOVE_FORWARD while the close walk was driving, commonest first. */
    public String forwardStealers() {
        return adris.altoclef.control.InputControls.forwardStealers();
    }

    /** What the close walk was up against when the body stopped moving. */
    public String blockedScenes() {
        return adris.altoclef.tasks.movement.GetToEntityTask.blockedScenes();
    }

    public String chipScenes() {
        return adris.altoclef.chains.MobDefenseChain.chipScenes();
    }

    public String punkStats() {
        var P = kaptainwutax.tungsten.task.PunkPlayerTask.class;
        // edgeSneak/edgeAir separate the two ways a duel ends in the void: the guard firing on the
        // ground, versus being near the edge AIRBORNE where sneak holds nothing. allround dies to
        // `fell out of the world` thirteen times a run and this is how we tell which one it is.
        return String.format("called=%d inactive=%d noTarget=%d voidHold=%d combat=%d approachRestart=%d armedEarly=%d"
                        + " edgeSneak=%d edgeAir=%d edgeSkipExec=%d",
                kaptainwutax.tungsten.task.PunkPlayerTask.pCalled,
                kaptainwutax.tungsten.task.PunkPlayerTask.pInactive,
                kaptainwutax.tungsten.task.PunkPlayerTask.pNoTarget,
                kaptainwutax.tungsten.task.PunkPlayerTask.pVoidHold,
                kaptainwutax.tungsten.task.PunkPlayerTask.pCombat,
                kaptainwutax.tungsten.task.PunkPlayerTask.pApproach,
                kaptainwutax.tungsten.task.PunkPlayerTask.pArmedEarly,
                kaptainwutax.tungsten.task.PunkPlayerTask.pEdgeSneak,
                kaptainwutax.tungsten.task.PunkPlayerTask.pEdgeAir,
                kaptainwutax.tungsten.task.PunkPlayerTask.pEdgeSkipExec);
    }

    /** Chase telemetry: ticks the follow task got, ticks it actually steered, and the gates
     *  that ate the rest (leap owning the body, the post-bail cooldown, no line of sight). */
    public String chaseStats() {
        var F = kaptainwutax.tungsten.task.FollowEntityTask.class;
        return String.format("called=%d inactive=%d active=%d | reached=%d steer=%d leap=%d cooldown=%d losBlocked=%d",
                kaptainwutax.tungsten.task.FollowEntityTask.tickCalled,
                kaptainwutax.tungsten.task.FollowEntityTask.tickInactive,
                kaptainwutax.tungsten.task.FollowEntityTask.tickActive,
                kaptainwutax.tungsten.task.FollowEntityTask.followTicks,
                kaptainwutax.tungsten.task.FollowEntityTask.steerTicks,
                kaptainwutax.tungsten.task.FollowEntityTask.leapTicks,
                kaptainwutax.tungsten.task.FollowEntityTask.cooldownTicks,
                kaptainwutax.tungsten.task.FollowEntityTask.losBlocked);
    }

    /** How often combat asked "is forward safe" and how often the edge guard said no. */
    /** Combat closing telemetry: ticks it WANTED to close, ticks the request was made after
     *  the edge test, and ticks it actually reached the keys after arbitration. */
    public String closeStats() {
        return String.format("wanted=%d asked=%d pressed=%d inReach=%d nearReach=%d lastDist=%.2f",
                kaptainwutax.tungsten.combat.CombatController.fwdWanted,
                kaptainwutax.tungsten.combat.CombatController.fwdAsked,
                kaptainwutax.tungsten.combat.CombatController.fwdPressed,
                kaptainwutax.tungsten.combat.CombatController.inReachTicks,
                kaptainwutax.tungsten.combat.CombatController.nearReachTicks,
                kaptainwutax.tungsten.combat.CombatController.lastDist);
    }

    public int dirAsked() { return kaptainwutax.tungsten.combat.CombatController.dirAsked; }

    /**
     * The tick-by-tick fight trace: one line per tick, oldest first, with distance, the keys that
     * actually reached the game, whether the dodge was driving, the running control counters and
     * the safety stage.
     *
     * <p>Empty unless {@code combatTrace} is pinned. The control counters are RUNNING TOTALS and
     * are meant to be diffed between consecutive lines -- see CombatTrace for why a per-tick flag
     * could not be used here.
     */
    public String combatTrace() { return kaptainwutax.tungsten.combat.CombatTrace.dump(); }
    public int dirBlockedFwd() { return kaptainwutax.tungsten.combat.CombatController.dirBlockedFwd; }

    /** Which trigger gate refuses the swing, counted rather than sampled. */
    public String gateStats() {
        var T = kaptainwutax.tungsten.combat.TriggerBot.class;
        var C = kaptainwutax.tungsten.combat.CombatController.class;
        int ga = kaptainwutax.tungsten.combat.TriggerBot.gAngle;
        int gr = kaptainwutax.tungsten.combat.TriggerBot.gReach;
        int gp = kaptainwutax.tungsten.combat.TriggerBot.gPassed;
        return String.format("total=%d click=%d cd=%d reach=%d ready=%d/%d/%d/%d held=%d/%d/%d wait=%d/%d angle=%d los=%d passed=%d"
                        + " | chargeMean=%.3f critWindowSwings=%d crits=%d weaponMean=%.2f noWeapon=%d deferred=%d"
                        + " | angleMean=%.1f angleMax=%.1f (thr 40) reachMean=%.2f reachMax=%.2f (thr 3.0)"
                        + " | aim: enemy=%d brake=%d reposition=%d(narrow=%d danger=%d(pred%.1f/true%.1f/flat%d/fly%.1f/land%d) escape=%d imm=%d forced=%d timer=%d) path=%d none=%d bowYield=%d bowGaveBack=%d bowNoDraw=%d drawMove=%d/%d meleeBow=%d/%d fightTicks=%d",
                kaptainwutax.tungsten.combat.TriggerBot.gTotal,
                kaptainwutax.tungsten.combat.TriggerBot.gClick,
                kaptainwutax.tungsten.combat.TriggerBot.gCooldown,
                kaptainwutax.tungsten.combat.TriggerBot.gReach,
                kaptainwutax.tungsten.combat.TriggerBot.gReadyFar,
                kaptainwutax.tungsten.combat.TriggerBot.gReadyNear,
                kaptainwutax.tungsten.combat.TriggerBot.gReadyFarDodging,
                kaptainwutax.tungsten.combat.TriggerBot.gReadyFarWalking,
                kaptainwutax.tungsten.combat.TriggerBot.gReadyFarFwdHeld,
                kaptainwutax.tungsten.combat.TriggerBot.gReadyFarSprintHeld,
                kaptainwutax.tungsten.combat.TriggerBot.gReadyFarStrafeHeld,
                kaptainwutax.tungsten.combat.TriggerBot.gNotReadyFar,
                kaptainwutax.tungsten.combat.TriggerBot.gNotReadyNear,
                kaptainwutax.tungsten.combat.TriggerBot.gAngle,
                kaptainwutax.tungsten.combat.TriggerBot.gLos,
                kaptainwutax.tungsten.combat.TriggerBot.gPassed,
                gp == 0 ? 0.0 : kaptainwutax.tungsten.combat.TriggerBot.gSwingChargeSum / gp,
                kaptainwutax.tungsten.combat.TriggerBot.gSwingCritWindow,
                kaptainwutax.tungsten.combat.TriggerBot.lifetimeCrits,
                gp == 0 ? 0.0 : kaptainwutax.tungsten.combat.TriggerBot.gSwingWeaponSum / gp,
                kaptainwutax.tungsten.combat.TriggerBot.gSwingNoWeapon,
                kaptainwutax.tungsten.combat.TriggerBot.gSwingDeferred,
                ga == 0 ? 0.0 : kaptainwutax.tungsten.combat.TriggerBot.gAngleSum / ga,
                kaptainwutax.tungsten.combat.TriggerBot.gAngleMax,
                gr == 0 ? 0.0 : kaptainwutax.tungsten.combat.TriggerBot.gReachDistSum / gr,
                kaptainwutax.tungsten.combat.TriggerBot.gReachDistMax,
                kaptainwutax.tungsten.combat.CombatController.aimEnemy,
                kaptainwutax.tungsten.combat.CombatController.aimBrake,
                kaptainwutax.tungsten.combat.CombatController.aimReposition,
                kaptainwutax.tungsten.combat.SafetySystem.rpNarrow,
                kaptainwutax.tungsten.combat.SafetySystem.rpDanger,
                kaptainwutax.tungsten.combat.SafetySystem.rpDanger == 0 ? 0.0
                        : kaptainwutax.tungsten.combat.SafetySystem.rpDangerPredSum
                                / kaptainwutax.tungsten.combat.SafetySystem.rpDanger,
                kaptainwutax.tungsten.combat.SafetySystem.rpDanger == 0 ? 0.0
                        : kaptainwutax.tungsten.combat.SafetySystem.rpDangerTrueSum
                                / kaptainwutax.tungsten.combat.SafetySystem.rpDanger,
                kaptainwutax.tungsten.combat.SafetySystem.rpDangerOnFlat,
                kaptainwutax.tungsten.combat.SafetySystem.rpDanger == 0 ? 0.0
                        : kaptainwutax.tungsten.combat.SafetySystem.rpDangerFlySum
                                / kaptainwutax.tungsten.combat.SafetySystem.rpDanger,
                kaptainwutax.tungsten.combat.SafetySystem.kbLandedOnSurface,
                kaptainwutax.tungsten.combat.SafetySystem.rpEscape,
                kaptainwutax.tungsten.combat.SafetySystem.rpImminent,
                kaptainwutax.tungsten.combat.SafetySystem.rpForcedNarrow,
                kaptainwutax.tungsten.combat.SafetySystem.rpForcedTimer,
                kaptainwutax.tungsten.combat.CombatController.aimPath,
                kaptainwutax.tungsten.combat.CombatController.aimNone,
                kaptainwutax.tungsten.combat.CombatController.aimYieldedToBow,
                kaptainwutax.tungsten.task.BowShooter.aimReleasedTooClose,
                kaptainwutax.tungsten.task.BowShooter.declinedClosing,
                kaptainwutax.tungsten.task.BowShooter.drawTicksMoving,
                kaptainwutax.tungsten.task.BowShooter.drawTicksStill,
                kaptainwutax.tungsten.combat.TriggerBot.gMeleeWithBow,
                kaptainwutax.tungsten.combat.TriggerBot.gMeleeArmed,
                kaptainwutax.tungsten.combat.CombatController.fightTicks);
    }

    /**
     * Read or write a tungsten runtime flag, and get the RESULTING value back.
     *
     * <p>Pass {@code value == null} to read. Returns {@code "<name>=<value>"} on success, or
     * {@code "unknown:<name>"} if no such field exists — so a caller can TELL whether the lever
     * moved. That return value is the whole point: the {@code @set} command reports failure only
     * into the in-game chat, and an experiment driven over py4j never sees it. Four interleaved
     * A/B batches in one session were run against a flag that was never applied, each came back
     * flat, and each flat result was read as evidence about the code rather than about the lever.
     *
     * <p>Runtime only — nothing is written to disk, so every run starts from the compiled default.
     *
     * @param name  field name on {@code TungstenConfig}, e.g. {@code chaseUsesQueue}
     * @param value new value as text ({@code "true"}, {@code "12"}), or null to just read
     */
    public String tungstenSetting(String name, String value) {
        Object cfg = kaptainwutax.tungsten.TungstenConfig.get();
        // ANSWER WITH THE FIELD THAT WAS ACTUALLY RESOLVED, NOT THE NAME THAT WAS ASKED FOR.
        // findSettingField falls back to a substring match and returns the FIRST hit in
        // declaration order, so "combatWindMouseWindDist" lands on "combatWindMouseWind". Echoing
        // the caller's name made a mis-resolved write look like a clean one — and the test
        // runner's --pin guard compares against exactly this string, so it would have confirmed
        // a pin that hit the wrong field. Now a typo reads back under the other field's name and
        // the guard fires.
        var field = adris.altoclef.util.helpers.SettingsReflectionHelper
                .findSettingField(cfg.getClass(), name);
        if (field.isEmpty()) {
            return "unknown:" + name;
        }
        String resolved = field.get().getName();
        // AN EMPTY VALUE IS A READ, NOT A WRITE OF "".
        // It used to be a write: "" is not null, so it reached setSetting and a boolean field
        // parsed it as FALSE. Calling this with "" to ask what a flag currently is therefore
        // SILENTLY TURNED THAT FLAG OFF and handed back "name=false" -- an answer the caller
        // then reasonably believed. It cost a contaminated run tonight: probing three combat
        // flags in the middle of a fight disabled the trigger bot, the movement controller and
        // the saver, and the "false" that came back was read as a finding about the engine
        // rather than as damage the probe had just done. Reading a lever must never move it.
        if (value != null && !value.isEmpty() && !adris.altoclef.util.helpers.SettingsReflectionHelper
                .setSetting(cfg, resolved, value)) {
            return "unsettable:" + resolved;
        }
        var got = adris.altoclef.util.helpers.SettingsReflectionHelper.getSetting(cfg, resolved);
        return got.isPresent() ? resolved + "=" + got.get() : "unknown:" + name;
    }

    /** Every settable tungsten runtime flag and its current value, one per line. */
    public String tungstenSettings() {
        StringBuilder sb = new StringBuilder();
        for (var s : adris.altoclef.util.helpers.SettingsReflectionHelper
                .getSettableFields(kaptainwutax.tungsten.TungstenConfig.get())) {
            sb.append(s).append(System.lineSeparator());
        }
        return sb.toString();
    }

    /**
     * ZERO EVERY ENGINE TELEMETRY COUNTER — the entry point a bench calls before it measures.
     *
     * <p>Each of these is a plain static that nothing resets, so what {@code placeStats()} and
     * {@code execState()} print is a sum over the CONTAINER's lifetime — dozens of scenarios —
     * while being read as if it described one run. Two conclusions have already been withdrawn
     * for exactly that reason. Every counter below is write-only in the engine ({@code x++}), so
     * zeroing them cannot change behaviour.
     */
    public java.util.Map<String, Object> resetRunCounters() {
        var q = kaptainwutax.tungsten.path.movements.MovementQueue.class;   // for the reader
        kaptainwutax.tungsten.path.movements.MovementQueue.qStarted = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qSteps = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qSuccess = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qUnreachable = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qTimeout = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qTicks = 0;
        // The nav-branch tallies zero here too, or they are container-lifetime sums wearing a
        // per-run label: three runs in a row reported pdNoVec=238 while pdEnter climbed, and the
        // only thing that ever really zeroed them was a client restart on redeploy.
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdEnter = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNotPrimary = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdPillar = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdBridge = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdStuckGiveUp = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdWalking = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNear = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityReleased = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityWandered = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNoGoal = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdFinished = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNoVec = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdLegacyToTungsten = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdLegacyDeclined = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdLegacyPath = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdStallWalker = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdStallReset = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdQueueTooShort = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNearBusy = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNearFind = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdPlanning = 0;
        adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdPlanGiveUp = 0;
        kaptainwutax.tungsten.path.PathExecutor.execArrived = 0;
        kaptainwutax.tungsten.path.PathExecutor.execRanOut = 0;
        // THE PUNK TALLIES WERE NEVER RESET, so every punkStats() ever read was a container-lifetime
        // sum wearing a per-run label — the exact failure the note fifteen lines above describes for
        // the nav branch. It matters right now: allround's `voidHold=406` was read as "this run held
        // 406 times", and it is not that. Reset them with everything else.
        kaptainwutax.tungsten.task.PunkPlayerTask.pCalled = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pInactive = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pNoTarget = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pVoidHold = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pCombat = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pApproach = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pLastKnown = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pEdgeSneak = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pEdgeAir = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.pEdgeSkipExec = 0;
        // A COUNTER WITHOUT A KNOWN ZERO IS NOT A MEASUREMENT — three conclusions died to that
        // tonight. These land here the same day they are born.
        kaptainwutax.tungsten.combat.SafetySystem.rpNarrow = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpDanger = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpDangerPredSum = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpDangerTrueSum = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpDangerOnFlat = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpDangerFlySum = 0;
        kaptainwutax.tungsten.combat.SafetySystem.kbLandedOnSurface = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpEscape = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpImminent = 0;
        kaptainwutax.tungsten.combat.SafetySystem.rpForcedNarrow = 0;
        kaptainwutax.tungsten.combat.CombatController.hurtTicks = 0;
        kaptainwutax.tungsten.combat.CombatController.hurtAdvancing = 0;
        kaptainwutax.tungsten.combat.CombatController.hurtBackingOff = 0;
        kaptainwutax.tungsten.combat.CombatController.controlTicks = 0;
        kaptainwutax.tungsten.combat.CombatController.cqEntry = 0;
        kaptainwutax.tungsten.combat.CombatController.cqTookFromPursue = 0;
        kaptainwutax.tungsten.combat.CombatTrace.reset();
        kaptainwutax.tungsten.combat.CombatController.cqNoLos = 0;
        kaptainwutax.tungsten.combat.SafetySystem.losCalls = 0;
        kaptainwutax.tungsten.combat.SafetySystem.losClosest = 0;
        kaptainwutax.tungsten.combat.SafetySystem.losSample = 0;
        kaptainwutax.tungsten.combat.SafetySystem.losNone = 0;
        kaptainwutax.tungsten.path.PathExecutor.execTicks = 0;
        kaptainwutax.tungsten.path.PathExecutor.execSprintTicks = 0;
        kaptainwutax.tungsten.path.PathExecutor.execYieldMiner = 0;
        adris.altoclef.control.Nav.legacyPathTicks = 0;
        adris.altoclef.control.Nav.legacyOverlapTicks = 0;
        adris.altoclef.control.Nav.exploreTicks = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbTick = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachMove = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachWater = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachPillager = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachNear = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachFar = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachDistSum = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbNearTick = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbNearNoReach = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbNearAirborne = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbNearHungry = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbNearUnsafe = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbTargetAir = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbLeafCleared = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbBlockedSelfFloor = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbBlockedUnclearable = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbStepOver = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbNoRetreat = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbBlockedNoReach = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbApproachStalled = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbBestDistTenths = Integer.MAX_VALUE;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbTargetsSeen = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbTargetsReached = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbReachGoal = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgTick = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgBigOpen = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgInvOpen = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgNoScreen = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgSent = 0;
        adris.altoclef.tasks.CraftInInventoryTask.ciTick = 0;
        adris.altoclef.tasks.CraftInInventoryTask.ciCollect = 0;
        adris.altoclef.tasks.CraftInInventoryTask.ciReceive = 0;
        adris.altoclef.tasks.CraftInInventoryTask.ciGridStranded = 0;
        adris.altoclef.chains.MobDefenseChain.mdPriorityCalls = 0;
        adris.altoclef.chains.MobDefenseChain.mdWon = 0;
        adris.altoclef.chains.MobDefenseChain.mdFlee = 0;
        adris.altoclef.chains.MobDefenseChain.mdFight = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet0 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet1 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet2 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet3 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet4 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet5 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet6 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet7 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet8 = 0;
        adris.altoclef.chains.MobDefenseChain.mdRet9 = 0;
        kaptainwutax.tungsten.combat.VoidGuard.vgCalls = 0;
        kaptainwutax.tungsten.combat.CombatController.rimAtBackTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.reachTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.nearTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.reachDrawingTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.nearDrawingTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.reachSprintTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.reachAwayTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeIdleInactive = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeIdleNoThreat = 0;
        kaptainwutax.tungsten.task.RunAwayTask.clientTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.nearThreatTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillNearThreatTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillExecutorTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillSearchTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillNobodyTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillMoveQueueTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillKeysDownTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillTouchingThreatTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillMaxRadius = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stillRadiusSum = 0;
        kaptainwutax.tungsten.task.RunAwayTask.stalledSeenAfterGuard = 0;
        kaptainwutax.tungsten.task.RunAwayTask.keysDownAfterGuardTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.hitDistMax = 0;
        kaptainwutax.tungsten.task.RunAwayTask.hitDistSum = 0;
        kaptainwutax.tungsten.task.RunAwayTask.hitDistN = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeSprintTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeAtRimTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintBowTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintOtherTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintTurningTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintUnexplained = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintHungryTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintSneakTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintCollideTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeCollideWithThreatTicks = 0;
        kaptainwutax.tungsten.task.RunAwayTask.fleeCollideMaxRadius = 0;
        kaptainwutax.tungsten.combat.VoidGuard.kbThrowMax = 0;
        kaptainwutax.tungsten.combat.VoidGuard.kbThrowSum = 0;
        kaptainwutax.tungsten.combat.VoidGuard.kbThrowN = 0;
        kaptainwutax.tungsten.combat.VoidGuard.kbThrowOverRadius = 0;
        kaptainwutax.tungsten.combat.VoidGuard.kbImpulseMax = 0;
        kaptainwutax.tungsten.combat.VoidGuard.kbImpulseSum = 0;
        kaptainwutax.tungsten.combat.VoidGuard.kbImpulseN = 0;
        kaptainwutax.tungsten.combat.VoidGuard.vgFallOnset = 0;
        kaptainwutax.tungsten.combat.VoidGuard.vgFallHurt = 0;
        kaptainwutax.tungsten.combat.VoidGuard.vgFallSprint = 0;
        kaptainwutax.tungsten.combat.VoidGuard.vgFallAfterEdge = 0;
        kaptainwutax.tungsten.combat.VoidGuard.vgEdgeSeen = 0;
        adris.altoclef.control.SlotHandler.shIssued = 0;
        adris.altoclef.control.SlotHandler.shDropped = 0;
        adris.altoclef.control.SlotHandler.shBlacklisted = 0;
        adris.altoclef.chains.PlayerInteractionFixChain.fixKeptCursor = 0;
        adris.altoclef.control.SlotHandler.shUnresolvedKept = 0;
        adris.altoclef.control.SlotHandler.shThrown = 0;
        adris.altoclef.chains.GameMenuTaskChain.gmDisconnect = 0;
        adris.altoclef.chains.GameMenuTaskChain.gmReconnectSet = 0;
        adris.altoclef.chains.GameMenuTaskChain.gmGuardBlocked = 0;
        adris.altoclef.chains.GameMenuTaskChain.gmConnectCalled = 0;
        adris.altoclef.control.SlotHandler.shLastBlacklistedSlot = -1;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgOutputReady = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgCraftable = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgNotCraftable = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgBookCraftable = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgBookNone = 0;
        adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgTooSmallGrid = 0;
        kaptainwutax.tungsten.path.movements.RotationHelper.rayLeaves = 0;
        kaptainwutax.tungsten.path.movements.RotationHelper.rayOtherBlock = 0;
        kaptainwutax.tungsten.path.movements.RotationHelper.rayMiss = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qLost = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qStatusFail = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qRefused = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qShort = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qVetoed = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qNoClass = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qParkour = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qAdmitMismatch = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qPrepTicks = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qRunTicks = 0;
        kaptainwutax.tungsten.path.movements.Movement.blindPrepGaveUp = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qNullRoute = 0;
        // ⛔ THESE WERE NEVER ZEROED, AND THEY ARE THE ONES EVERY ROUTE QUESTION IS ASKED WITH.
        // qRefused, qShort and qNoClass reset here; qStarted and qSteps did not, so they counted
        // from the moment the client launched. Any per-run reading of them was really a lifetime
        // average -- which is how 'steps per route start' came to be quoted as 1.00 against 2.37
        // between two classes of run when neither number belonged to a run at all. Same hole for
        // qNullEdge, which had already been quoted once per route start before being dropped for
        // an unrelated reason. Reset what the tools read, or the tools measure the session.
        kaptainwutax.tungsten.path.movements.MovementQueue.qStarted = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qSteps = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qSuccess = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qUnreachable = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qTimeout = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qTicks = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qBurnedInPlace = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qNullEdge = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qStuckNoMove = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qTeleported = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.walkerYieldedToMiner = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.walkerYieldedToExecutor = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.tickOff = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.tickBfs = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.tickDir = 0;
        kaptainwutax.tungsten.task.FastNavigator.navWatchdogUngagged = 0;
        kaptainwutax.tungsten.task.FastNavigator.navPlannedFromStaleTail = 0;
        kaptainwutax.tungsten.path.movements.MovementDiagonal.diagonalWalled = 0;
        kaptainwutax.tungsten.task.BlockPathWalker.walkerHoleHeld = 0;
        kaptainwutax.tungsten.task.FastNavigator.navBridgeRescued = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qExpandedCells = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qExpandedRuns = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qExpandRefused = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qRebased = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbFarRetried = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbFarCondemned = 0;
        kaptainwutax.tungsten.combat.CombatPathfinder.gridCornerRefused = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qUnreachReplan = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qOffRoute = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qExpandNoFloor = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qExpandSample = "-";
        kaptainwutax.tungsten.path.PathFinder.searchGaveUp = 0;
        kaptainwutax.tungsten.path.PathFinder.searchGaveUpSalvaged = 0;
        adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcTick = 0;
        adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcNewPursuit = 0;
        adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcRetryOld = 0;
        adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcHold = 0;
        adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcWander = 0;
        adris.altoclef.tasks.CraftGenericManuallyTask.mcFilled = 0;
        adris.altoclef.tasks.CraftGenericManuallyTask.mcShort = 0;
        adris.altoclef.tasks.CraftGenericManuallyTask.mcFromOutput = 0;
        adris.altoclef.tasks.CraftGenericManuallyTask.mcWait = 0;
        adris.altoclef.tasks.CraftGenericManuallyTask.mcInFlight = 0;
        adris.altoclef.tasks.CraftGenericManuallyTask.mcSlotSwitched = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.toolSwaps = 0;
        adris.altoclef.tasks.movement.TimeoutWanderTask.wanderTicks = 0;
        adris.altoclef.tasks.movement.TimeoutWanderTask.wanderMovedCm = 0;
        adris.altoclef.tasks.movement.TimeoutWanderTask.wanderCheckOk = 0;
        adris.altoclef.tasks.movement.TimeoutWanderTask.wanderCheckTrip = 0;
        adris.altoclef.tasks.movement.TimeoutWanderTask.wanderFailPeak = 0;
        adris.altoclef.tasks.movement.TimeoutWanderTask.wanderResetDenied = 0;
        adris.altoclef.tasks.movement.TimeoutWanderTask.wanderKeysKept = 0;
        adris.altoclef.tasks.construction.DestroyBlockTask.dbResetDenied = 0;
        adris.altoclef.util.progresscheck.MovementProgressChecker.airProgressDenied = 0;
        adris.altoclef.chains.WorldSurvivalChain.lavaEscapeTicks = 0;
        adris.altoclef.chains.WorldSurvivalChain.lavaCondHazard = 0;
        adris.altoclef.chains.WorldSurvivalChain.lavaCondAllowed = 0;
        adris.altoclef.chains.WorldSurvivalChain.survivalEntered = 0;
        adris.altoclef.chains.WorldSurvivalChain.survivalPastGuard = 0;
        adris.altoclef.tasks.container.CraftInTableTask.tblAsked = 0;
        adris.altoclef.tasks.container.CraftInTableTask.tblFound = 0;
        adris.altoclef.tasks.container.CraftInTableTask.tblLastDist = 0;
        adris.altoclef.trackers.BlockScanner.scanStarted = 0;
        adris.altoclef.trackers.BlockScanner.scanDone = 0;
        adris.altoclef.tasks.CraftGenericManuallyTask.mcInvalidSlot = 0;
        adris.altoclef.control.Nav.navUnsafeAir = 0;
        adris.altoclef.control.SlotHandler.clickTrace = 0;
        adris.altoclef.tasks.container.DoStuffInContainerTask.dsicTrace = 0;
        kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode.smSelected = 0;
        kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode.smMoves = 0;
        kaptainwutax.tungsten.path.blockSpaceSearchAssist.SmartMoves.waterMoves = 0;
        kaptainwutax.tungsten.path.PathFinder.physicsRanOut = 0;
        kaptainwutax.tungsten.path.PathFinder.fallGuardRetries = 0;
        kaptainwutax.tungsten.path.Node.fallMovesRejected = 0;
        kaptainwutax.tungsten.path.PathFinder.physicsRanOutSalvaged = 0;
        kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.blockRanOut = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.dropAsked = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanAccepted = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanUnreachable = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanNoBreak = 0;
        // Mechanism gates for mineStayOnSurface / mineAvoidUnderfoot. Declaring a gate and then
        // not exposing it is what made a 40-launch series void earlier today; wired with the flag
        // this time, and checked in a smoke test before any series was spent on it.
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanBelowFeet = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanUnderfoot = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanEnclosed = 0;
        adris.altoclef.control.Nav.navStopped = 0;
        adris.altoclef.control.Nav.navStoppedLive = 0;
        adris.altoclef.control.Nav.navSearchOnly = 0;
        adris.altoclef.util.goals.AltoGoal.FLEE_RELOCATED.set(0);
        adris.altoclef.util.goals.AltoGoal.FLEE_NO_SPOT.set(0);
        adris.altoclef.util.helpers.TungstenHelper.lockBarren = 0;
        adris.altoclef.util.helpers.TungstenHelper.lockProductive = 0;
        adris.altoclef.util.helpers.TungstenHelper.findRefused = 0;
        // AND THE GEOMETRY WITH THEM. Its first run reported two barren locks on a PASSING course
        // whose lockBarren read 0 -- the entries were the previous run's, carried over because the
        // deque was not in this reset. A recording that survives the run it describes is the same
        // lying instrument as a counter that never zeroes, and this file has paid for that before.
        adris.altoclef.util.helpers.TungstenHelper.clearBarrenGeometry();
        adris.altoclef.util.helpers.WorldHelper.BreakStats.cbHardness = 0;
        adris.altoclef.util.helpers.WorldHelper.BreakStats.cbAvoid = 0;
        adris.altoclef.util.helpers.WorldHelper.BreakStats.cbPlausible = 0;
        adris.altoclef.util.helpers.WorldHelper.BreakStats.cbReach = 0;
        baritone.altoclef.AltoClefSettings.avoidHitSet = 0;
        baritone.altoclef.AltoClefSettings.avoidHitPred = 0;
        adris.altoclef.BotBehaviour.breakAvoidersRegistered = 0;
        adris.altoclef.BotBehaviour.lastBreakAvoiderBy = "-";
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.dropSeen = 0;
        adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.dropNearExempt = 0;
        adris.altoclef.trackers.EntityTracker.etItemsSeen = 0;
        adris.altoclef.trackers.EntityTracker.etItemsGrounded = 0;
        adris.altoclef.trackers.EntityTracker.idAsked = 0;
        adris.altoclef.trackers.EntityTracker.idNoneTracked = 0;
        adris.altoclef.trackers.EntityTracker.idBlacklisted = 0;
        adris.altoclef.trackers.EntityTracker.idReturned = 0;
        adris.altoclef.tasks.ResourceTask.rtReached = 0;
        adris.altoclef.tasks.ResourceTask.rtAvoided = 0;
        adris.altoclef.tasks.ResourceTask.rtDropped = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalk = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkAbove = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkOnTop = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBeside = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow1 = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow2 = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow3 = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkDeepDeclined = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkLeaseCleared = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkSneakReleased = 0;
        // ⛔ FIVE COUNTERS ADDED TODAY AND NOT RESET, WHICH IS THE DEFECT TODAY WAS SPENT ON.
        // gamer_smoke was found calling a reset that zeroed nothing, and resetRunCounters was
        // found missing ten queue counters; both were fixed hours ago, and then five new counters
        // went in without one. It showed immediately: entBudget read 2 in BOTH arms of a paired
        // A/B, including the control where the branch cannot fire.
        adris.altoclef.tasks.entity.AbstractDoToEntityTask.entityBudgetSpent = 0;
        adris.altoclef.tasks.movement.PickupDroppedItemTask.dropBudgetSpent = 0;
        adris.altoclef.trackers.EntityTracker.idDeepPicks = 0;
        adris.altoclef.trackers.EntityTracker.idDeepBeatOthers = 0;
        adris.altoclef.trackers.EntityTracker.idDropPick = "-";
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkAimClaimed = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkJumped = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.clearBlockedScenes();
        kaptainwutax.tungsten.path.movements.MovementQueue.clearNoClassShapes();
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkFwdKept = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkFwdLost = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.closeWalkKeysKept = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkMoved = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkCloser = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkAimed = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkLeased = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkYawKept = 0;
        adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkRetarget = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeTraverse = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeAscend = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeDescend = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeDiagonal = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeOther = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeRequested = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeOnCooldown = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeNoHit = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeClicked = 0;
        // The PathExecutor place block was NEVER reset -- these were client-lifetime totals,
        // which is why captures read called=2579 and grew across a sweep. Per-run now, so the
        // inRange-to-clicked ratio means something within one run.
        kaptainwutax.tungsten.path.PathExecutor.placeCalled = 0;
        kaptainwutax.tungsten.path.PathExecutor.placeDeferred = 0;
        kaptainwutax.tungsten.path.PathExecutor.placeInRange = 0;
        kaptainwutax.tungsten.path.PathExecutor.placeClicked = 0;
        kaptainwutax.tungsten.path.PathExecutor.placeNoSupport = 0;
        kaptainwutax.tungsten.path.PathExecutor.placeDeniedPolicy = 0;
        kaptainwutax.tungsten.path.PathExecutor.placeDeniedShape = 0;
        kaptainwutax.tungsten.path.movements.Movement.motionSteered = 0;
        kaptainwutax.tungsten.path.movements.Movement.sprintTicks = 0;
        kaptainwutax.tungsten.path.movements.Movement.moveTicks = 0;
        kaptainwutax.tungsten.combat.CombatController.lowHpTicks = 0;
        kaptainwutax.tungsten.combat.CombatController.lowHpDeclined = 0;
        kaptainwutax.tungsten.combat.CombatController.aimYieldedToBow = 0;
        kaptainwutax.tungsten.combat.CombatController.standOffDeclined = 0;
        adris.altoclef.tasks.entity.AbstractKillEntityTask.kaTungstenTicks = 0;
        adris.altoclef.tasks.entity.AbstractKillEntityTask.kaTaskTicks = 0;
        adris.altoclef.tasks.entity.AbstractKillEntityTask.kaCanHitTicks = 0;
        adris.altoclef.tasks.entity.AbstractKillEntityTask.kaEquipTicks = 0;
        adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteGate = 0;
        adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteInRange = 0;
        adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteHungry = 0;
        adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteFalling = 0;
        adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteMlg = 0;
        adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteUnsafe = 0;
        adris.altoclef.chains.MobDefenseChain.mdTungstenTicks = 0;
        adris.altoclef.chains.MobDefenseChain.mdFleeStuck = 0;
        adris.altoclef.chains.MobDefenseChain.mdFleeShooter = 0;
        adris.altoclef.chains.MobDefenseChain.mdFarTicks = 0;
        adris.altoclef.chains.MobDefenseChain.mdFarGapMilli = 0;
        adris.altoclef.chains.MobDefenseChain.mdArrows = 0;
        adris.altoclef.chains.MobDefenseChain.mdArrowGapMilli = 0;
        adris.altoclef.chains.MobDefenseChain.mdArrowGapMaxMilli = 0;
        adris.altoclef.chains.MobDefenseChain.seenArrowIds.clear();
        adris.altoclef.chains.MobDefenseChain.mdDraws = 0;
        adris.altoclef.chains.MobDefenseChain.mdDrawTicks = 0;
        adris.altoclef.chains.MobDefenseChain.mdDrawMaxTicks = 0;
        adris.altoclef.chains.MobDefenseChain.mdDrawGapMilli = 0;
        adris.altoclef.chains.MobDefenseChain.mdBandTicks = 0;
        // -1, NOT 0. Zero is a real and interesting value here -- "our first swing passed on
        // the very tick the band was entered" -- so it cannot double as "never happened".
        adris.altoclef.chains.MobDefenseChain.mdBandToFirstSwing = -1;
        adris.altoclef.chains.MobDefenseChain.mdDodgeYielded = 0;
        kaptainwutax.tungsten.combat.ApproachLatch.reset();
        // THE SIXTH DEAD INSTRUMENT TODAY. These two count the "block failed to break ->
        // ban a radius-50 cube" decision that empties the minable-block list, and nothing has
        // ever read them. mine_stone scores 1 of 6 with the ban visible in the client log.
        adris.altoclef.chains.WorldSurvivalChain.breakFailClaimed = 0;
        adris.altoclef.chains.WorldSurvivalChain.breakFailOutOfReach = 0;
        adris.altoclef.chains.WorldSurvivalChain.breakFailBuried = 0;
        adris.altoclef.chains.WorldSurvivalChain.breakBanWide = 0;
        adris.altoclef.chains.WorldSurvivalChain.breakFailRetried = 0;
        // DECLARED AS A MECHANISM GATE AND THEN NOT EXPOSED -- which made the series that
        // depended on it VOID by its own rule. Seventh dead instrument found today and the only
        // one I created, in the same change as the gate that needed it.
        adris.altoclef.chains.UnstuckChain.strandedRescues = 0;
        adris.altoclef.chains.UnstuckChain.gpAppended = 0;
        adris.altoclef.chains.UnstuckChain.gpNotInGame = 0;
        adris.altoclef.chains.UnstuckChain.gpPaused = 0;
        adris.altoclef.chains.UnstuckChain.gpNoUserTask = 0;
        adris.altoclef.chains.UnstuckChain.gpContainer = 0;
        adris.altoclef.chains.MobDefenseChain.mdDodgeTaskTicks = 0;
        adris.altoclef.chains.MobDefenseChain.mdDodgeTaskGapMilli = 0;
        adris.altoclef.chains.MobDefenseChain.resetDamageLedger();
        kaptainwutax.tungsten.combat.CombatController.strafeFarTicks = 0;
        kaptainwutax.tungsten.combat.CombatController.strafeNearTicks = 0;
        kaptainwutax.tungsten.task.ProjectileDodge.driveTicks = 0;
        adris.altoclef.chains.MobDefenseChain.mdAuraTungstenTicks = 0;
        adris.altoclef.chains.MobDefenseChain.mdDamageTaken = 0f;
        kaptainwutax.tungsten.combat.DamageWatch.reset();
        adris.altoclef.chains.MobDefenseChain.mdHitFront = 0;
        adris.altoclef.chains.MobDefenseChain.mdHitBack = 0;
        adris.altoclef.chains.MobDefenseChain.mdHitLeft = 0;
        adris.altoclef.chains.MobDefenseChain.mdHitRight = 0;
        adris.altoclef.chains.MobDefenseChain.mdHitDistSum = 0.0;
        adris.altoclef.chains.MobDefenseChain.mdHitDistMax = 0.0;
        adris.altoclef.chains.MobDefenseChain.mdHitCount = 0;
        adris.altoclef.chains.MobDefenseChain.mdPillarDefence = 0;
        adris.altoclef.chains.MobDefenseChain.mdBowTicks = 0;
        kaptainwutax.tungsten.task.BowShooter.resetShotsFired();
        kaptainwutax.tungsten.task.RunAwayTask.resetCounters();
        // The gate ACCUMULATORS need zeroing with everything else, and the instrument caught its
        // own omission the first time it ran: angleMean=258 with angleMax=172 is impossible, and
        // only a sum carried over from a previous run can produce it.
        kaptainwutax.tungsten.combat.TriggerBot.gMeleeWithBow = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gMeleeArmed = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gAngleSum = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gAngleMax = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReachDistSum = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReachDistMax = 0;
        kaptainwutax.tungsten.combat.CombatController.resetAimCounters();
        // The give-back counter must die with its run like every other. It did not, and the
        // control arm of its own A/B printed bowGaveBack=9 with the flag OFF -- the seventh
        // counter this session to outlive the thing it describes.
        kaptainwutax.tungsten.task.BowShooter.aimReleasedTooClose = 0;
        kaptainwutax.tungsten.task.BowShooter.declinedClosing = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qBurnedInPlace = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qTeleported = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qStuckNoMove = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.clearStuckScenes();
        adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask.cursorGaveUp = 0;
        // THE SWING GATES HAVE TO BE RESET TOO, OR THEY REPORT AN OLD FIGHT.
        // They were left out, so a mob-combat probe read total=161 passed=8 -- values left over
        // from a pvp suite hours earlier -- and they never moved, which was mistaken for "eight
        // swings that dealt no damage". The truth was that the trigger bot never ticked at all.
        // A counter that survives its run is a counter that lies about the next one.
        // THESE FIVE WERE NEVER RESET WHILE EVERYTHING AROUND THEM WAS, WHICH MAKES THEM TRAPS.
        // closeStats' counters accumulated for the LIFETIME of the container while gTotal beside
        // them cleared every run, so reading the two together invites a comparison that cannot be
        // true: "in reach 259 ticks but only 71 trigger evaluations" is a lifetime number set
        // against a per-run one. That exact reading was taken as evidence tonight that the OFFENCE
        // was broken, and it is not evidence of anything. Same window or no comparison.
        kaptainwutax.tungsten.combat.CombatController.inReachTicks = 0;
        kaptainwutax.tungsten.combat.CombatController.nearReachTicks = 0;
        kaptainwutax.tungsten.combat.CombatController.fwdWanted = 0;
        kaptainwutax.tungsten.combat.CombatController.fwdAsked = 0;
        kaptainwutax.tungsten.combat.CombatController.fwdPressed = 0;
        // SEVEN, NOT FIVE. dirAsked/dirBlockedFwd answer "did the edge guard refuse the step
        // forward" -- the question directly beneath fwdWanted vs fwdAsked -- and they sat two lines
        // from this reset without being in it. Found while wiring closeStats into the mob courses,
        // which would have printed a container-lifetime total beside per-run counters and invited
        // the very comparison the paragraph above says cannot be true.
        kaptainwutax.tungsten.combat.CombatController.dirAsked = 0;
        kaptainwutax.tungsten.combat.CombatController.dirBlockedFwd = 0;
        kaptainwutax.tungsten.task.BowShooter.noSolution = 0;
        kaptainwutax.tungsten.task.BowShooter.restarts = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gSwingChargeSum = 0;
        // OUTLIVED ITS RUN. Read 30 and 63 in CONTROL arms that sent nothing, because nothing
        // ever zeroed it; only the deltas between runs were honest. Same class of bug as the
        // one this counter was added to guard against.
        kaptainwutax.tungsten.combat.WeaponSelector.slotSyncSent = 0;
        kaptainwutax.tungsten.combat.WeaponSelector.slotReasserted = 0;
        kaptainwutax.tungsten.combat.WeaponSelector.strayAttackTicks = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gSwingHeldForSwitch = 0;
        kaptainwutax.tungsten.combat.CombatController.lowHpByLosing = 0;
        kaptainwutax.tungsten.combat.CombatController.lowHpHeldWithBow = 0;
        kaptainwutax.tungsten.combat.WeaponSelector.strayAttackWithNonWeapon = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gCritSprintReleased = 0;
        kaptainwutax.tungsten.combat.DamageWatch.takeTiny = 0;
        kaptainwutax.tungsten.combat.DamageWatch.clearSmallHitCauses();
        kaptainwutax.tungsten.combat.DamageWatch.takePartial = 0;
        kaptainwutax.tungsten.combat.DamageWatch.takeFlat = 0;
        kaptainwutax.tungsten.combat.DamageWatch.takeBig = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gSwingWeaponSum = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gSwingNoWeapon = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gSwingDeferred = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gSwingCritWindow = 0;
        kaptainwutax.tungsten.combat.TriggerBot.lifetimeCrits = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gTotal = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gClick = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gCooldown = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReach = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReadyFar = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReadyNear = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReadyFarDodging = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReadyFarWalking = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReadyFarFwdHeld = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReadyFarSprintHeld = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gReadyFarStrafeHeld = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gNotReadyFar = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gNotReadyNear = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gAngle = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gLos = 0;
        kaptainwutax.tungsten.combat.TriggerBot.gPassed = 0;
        kaptainwutax.tungsten.path.PathFinder.staleRootRejections = 0;
        kaptainwutax.tungsten.task.FollowEntityTask.planCalls = 0;
        kaptainwutax.tungsten.task.FollowEntityTask.planUsable = 0;
        kaptainwutax.tungsten.task.FollowEntityTask.planTooShort = 0;
        kaptainwutax.tungsten.task.FollowEntityTask.physicsFallbacks = 0;
        kaptainwutax.tungsten.task.FollowEntityTask.traversableCells = 0;
        kaptainwutax.tungsten.task.FollowEntityTask.routeCells = 0;
        kaptainwutax.tungsten.task.FollowEntityTask.routeSamples = 0;
        kaptainwutax.tungsten.task.PunkPlayerTask.resetCounters();
        kaptainwutax.tungsten.helpers.BlockPlaceHelper.resetCounters();
        java.util.Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        return out;
    }

    /** Bridge execution telemetry: ticks the place logic ran, ticks deferred because the bot
     *  was still walking there, ticks actually in range, and blocks actually clicked. */
    /**
     * Ask the crafting-recipe tracker what it knows, and MAKE it answer.
     *
     * <h2>Why this exists as a lever rather than a counter</h2>
     *
     * Trackers rebuild lazily: {@code ensureUpdated()} runs only when one of their getters is
     * called. So a bare {@code recipesKnown} counter reads 0 in any course that never asks the
     * tracker anything -- which is every course on the flat arena -- and a reading that cannot
     * distinguish "the port is broken" from "nobody asked" is not a measurement at all.
     *
     * <p>This calls a getter, which forces the rebuild, and then reports. Deliberately probes a few
     * named items across both grid sizes: oak planks (2x2, one ingredient), a crafting table (2x2,
     * four), a stone pickaxe (3x3, mixed) and a bucket (3x3, the one BeatMinecraftTask asks the
     * tracker for by name).
     *
     * @return {@code recipesKnown=N oak_planks=true crafting_table=true stone_pickaxe=true bucket=true}
     */
    public String recipeProbe() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getCraftingRecipeTracker() == null) {
            return "recipeProbe: no mod";
        }
        net.minecraft.item.Item[] probes = {
                net.minecraft.item.Items.OAK_PLANKS,
                net.minecraft.item.Items.CRAFTING_TABLE,
                net.minecraft.item.Items.STONE_PICKAXE,
                net.minecraft.item.Items.BUCKET,
        };
        StringBuilder out = new StringBuilder();
        for (net.minecraft.item.Item probe : probes) {
            // hasRecipeForItem calls ensureUpdated, which is the whole point of asking.
            boolean known = mod.getCraftingRecipeTracker().hasRecipeForItem(probe);
            out.append(' ').append(net.minecraft.registry.Registries.ITEM.getId(probe).getPath())
                    .append('=').append(known);
        }
        return "recipesKnown=" + adris.altoclef.trackers.CraftingRecipeTracker.recipesKnown + out;
    }

    public String placeStats() {
        // Two engines, both reported. The first four are the SPLIT path (walker moves the body,
        // PathExecutor.tickPlacing aims and clicks) whose seam measured clicked=0 across eleven
        // thousand in-range ticks. The `mq`/`mv` numbers are the ported baritone MovementQueue that
        // replaces it for bridge legs: mq* is the chain (legs started, steps completed, handbacks,
        // timeouts, ticks owned), mv* is the one and only promotion to a click — a movement asked for
        // CLICK_RIGHT because its live crosshair agreed, and interactBlock said SUCCESS.
        return String.format(
                "called=%d deferred=%d inRange=%d clicked=%d noSup=%d/%d/%d"
                        + " | mqStarted=%d mqSteps=%d mqBack=%d mqTimeout=%d mqTicks=%d step=%d/%d"
                        + " pdEnter=%d pdNotPrim=%d pdPillar=%d pdBridge=%d pdStuck=%d pdWalking=%d pdNear=%d pdNoGoal=%d pdFinished=%d pdNoVec=%d pdStallWalk=%d pdStallReset=%d pdQueueShort=%d pdNearBusy=%d pdNearFind=%d pdPlan=%d/%d pdLegacy=%d pdLegacyTung=%d/%d exArrived=%d exRanOut=%d exSprint=%d/%d execYieldMiner=%d legacyDrive=%d/%d/%d unknownGoal=%s dbTick=%d dbUnreachMove=%d dbUnreachWater=%d dbUnreachPillager=%d dbNear=%d dbFar=%d dbDistSum=%d dbNearTick=%d noReach=%d air=%d hungry=%d unsafe=%d blockedBy=%s dbTargetAir=%d rayLeaves=%d rayOther=%d rayMiss=%d leafCleared=%d dbBlocked=%d/%d/%d dbNoRetreat=%d dbStepOver=%d dbApproachStall=%d dbBestDist=%d dbTargets=%d/%d dbReachGoal=%d cgTick=%d cgBig=%d cgInv=%d cgNoScreen=%d cgSent=%d cgOutReady=%d cgLastSent=%s cgCraftable=%d cgNotCraftable=%d cgBookOk=%d cgBookNone=%d cgSmall=%d cgScreen=%s ciTick=%d ciCollect=%d ciReceive=%d ciGrid=%d mdCalls=%d mdWon=%d mdFlee=%d mdFight=%d mdRet=%d/%d/%d/%d/%d/%d/%d/%d/%d/%d vgCalls=%d vgEdge=%d vgFall=%d/%d/%d/%d rimBack=%d kbThrow=%d/%d/%d/%d kbImp=%d/%d/%d shIssued=%d shDropped=%d shBlack=%d shThrown=%d shKept=%d fixKept=%d throwers=[%s] dropPick=[%s] deepPicks=%d/%d dropBudget=%d entBudget=%d gmDisc=%d gmRecSet=%d gmGuard=%d gmConn=%d shLastBlackSlot=%d slotYeet=%d"
                        + " mqLost=%d mqStatusFail=%d mqRefused=%d(short=%d vetoed=%d) mqNoClass=%d mqNullEdge=%d mqExpand=%d/%d/%d/%d mqExpandAt=%s qRebased=%d qOffRoute=%d qUnreachReplan=%d gridCorner=%d dbFarRetry=%d/%d navBridgeRescued=%d walkerHoleHeld=%d diagonalWalled=%d staleTail=%d ungagged=%d walkYield=%d walkYieldMiner=%d walkMode=%d/%d/%d mqParkour=%d mqAdmitMismatch=%d qPrep=%d/%d blindPrep=%d mqNull=%d gaveUp=%d/%d dc=%d/%d/%d/%d/%d mc=%d/%d/%d/%d/%d mcFlight=%d mcSwitch=%d toolSwap=%d recipesKnown=%d wander=%d wanderMoved=%d wanderChk=%d/%d wanderFail=%d wanderDenied=%d/%d dbDenied=%d airProg=%d lavaEsc=%d lavaCond=%d/%d surv=%d/%d tbl=%d/%d@%d bs=%d/%d/%d/%d@%dms navUnsafeAir=%d sm=%d/%d smWater=%d srch=%d/%d/%d fallRetry=%d/%d drop=%d/%d/%d scan=%d/%d/%d/%d/%d/%d navStop=%d/%d/%d fleeSpot=%d/%d lock=%d/%d/%d@%s cb=%d/%d/%d/%d avoidSrc=%d/%d/%d/%d@%s/%s et=%d/%d"
                        + " sprint=%d/%d lowHp=%d/%d standOff=%d hurt=%d/%d/%d hurtWin=%d/%d diseng=%d/%d ctl=%d cq=%d/%d/%d los=%d/%d/%d/%d kaTung=%d/%d/%d/%d dte=%d/%d/%d/%d/%d/%d mdTung=%d/%d mdFleeStuck=%d mdFleeShooter=%d mdFar=%d/%d arrows=%d/%.2f/%.2f draws=%d/%d/%d/%.2f band=%d/%d dodgeYield=%d latch=%d/%d breakFail=%d/%d/%d/%d/%d stranded=%d/%s/%s gp=%d/%d/%d/%d/%d dodgeTask=%d/%.2f dealt=%.1f/%.1f/%d hitSize=%d/%d/%d/%d takeSize=%d/%d/%d/%d slotSync=%d/%d critReset=%d stray=%d/%d heldSwing=%d loseKite=%d holdBow=%d punkKept=%d swingHits=%d dodgeDrive=%d hop=%d/%d/%d/%d/%d/%d/%d mdPillarD=%d dmgTaken=%.1f dw=%d/%.1f/%.2f/%.2f/%d/%d dwNoBlame=%d dmgWhy=%d/%d/%d/%d/%d@%.1f strafe=%d/%d voidEntries=%d voidTicks=%d lastFall=[%s] hits=%d/%d/%d/%d hitRange=%.2f/%.2f mdBow=%d bowShots=%d bowWild=%d bowNoSol=%d bowRestart=%d bowAimTO=%d bowDrawTO=%d bowBestMiss=%.2f bowFacing=%d bowNoRoom=%d flee=%d/%d/%d/%d/%d/%d qBurn=%d qTp=%d qNoMove=%d stuck=[%s] staleRoot=%d"
                        + " | mvRequested=%d mvCooldown=%d mvNoHit=%d mvClicked=%d mvSteered=%d"
                        + " | gateThrough=%d gateHeld=%d queued=%d queuePlaced=%d"
                        + " entityReleased=%d/%d idrop=%d/%d/%d/%d rt=%d/%d/%d entityCloseWalk=%d/%d/%d/%d/%d/%d/%d closeWalkGeom=%d/%d/%d/%d deep=%d/%d/%d cwJump=%d cwSneakRel=%d cwLease=%d cwDeep=%d closeWalkFwd=%d/%d/%d",
                kaptainwutax.tungsten.path.PathExecutor.placeCalled,
                kaptainwutax.tungsten.path.PathExecutor.placeDeferred,
                kaptainwutax.tungsten.path.PathExecutor.placeInRange,
                kaptainwutax.tungsten.path.PathExecutor.placeClicked,
                kaptainwutax.tungsten.path.PathExecutor.placeNoSupport,
                kaptainwutax.tungsten.path.PathExecutor.placeDeniedPolicy,
                kaptainwutax.tungsten.path.PathExecutor.placeDeniedShape,
                kaptainwutax.tungsten.path.movements.MovementQueue.qStarted,
                kaptainwutax.tungsten.path.movements.MovementQueue.qSteps,
                kaptainwutax.tungsten.path.movements.MovementQueue.qUnreachable,
                kaptainwutax.tungsten.path.movements.MovementQueue.qTimeout,
                kaptainwutax.tungsten.path.movements.MovementQueue.qTicks,
                kaptainwutax.tungsten.path.movements.MovementQueue.getIndex(),
                kaptainwutax.tungsten.path.movements.MovementQueue.size(),
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdEnter,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNotPrimary,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdPillar,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdBridge,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdStuckGiveUp,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdWalking,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNear,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNoGoal,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdFinished,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNoVec,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdStallWalker,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdStallReset,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdQueueTooShort,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNearBusy,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdNearFind,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdPlanning,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdPlanGiveUp,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdLegacyPath,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdLegacyToTungsten,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdLegacyDeclined,
                kaptainwutax.tungsten.path.PathExecutor.execArrived,
                kaptainwutax.tungsten.path.PathExecutor.execRanOut,
                // sprinted / total executor ticks. The sprint is chosen by the PATH NODE
                // (SprintPolicy at move-generation time), not by the executor, so a flee that never
                // requests it walks the whole way and no amount of shooting discipline can matter.
                kaptainwutax.tungsten.path.PathExecutor.execSprintTicks,
                kaptainwutax.tungsten.path.PathExecutor.execTicks,
                kaptainwutax.tungsten.path.PathExecutor.execYieldMiner,
                adris.altoclef.control.Nav.legacyPathTicks,
                adris.altoclef.control.Nav.legacyOverlapTicks,
                adris.altoclef.control.Nav.exploreTicks,
                adris.altoclef.tasks.movement.CustomBaritoneGoalTask.pdLastUnknownGoal,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbTick,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachMove,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachWater,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachPillager,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachNear,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachFar,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbUnreachDistSum,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbNearTick,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbNearNoReach,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbNearAirborne,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbNearHungry,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbNearUnsafe,
                kaptainwutax.tungsten.path.movements.RotationHelper.blockedBy,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbTargetAir,
                kaptainwutax.tungsten.path.movements.RotationHelper.rayLeaves,
                kaptainwutax.tungsten.path.movements.RotationHelper.rayOtherBlock,
                kaptainwutax.tungsten.path.movements.RotationHelper.rayMiss,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbLeafCleared,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbBlockedSelfFloor,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbBlockedUnclearable,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbBlockedNoReach,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbNoRetreat,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbStepOver,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbApproachStalled,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbBestDistTenths,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbTargetsSeen,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbTargetsReached,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbReachGoal,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgTick,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgBigOpen,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgInvOpen,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgNoScreen,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgSent,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgOutputReady,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgLastSent,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgCraftable,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgNotCraftable,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgBookCraftable,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgBookNone,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgTooSmallGrid,
                adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.cgScreen,
                adris.altoclef.tasks.CraftInInventoryTask.ciTick,
                adris.altoclef.tasks.CraftInInventoryTask.ciCollect,
                adris.altoclef.tasks.CraftInInventoryTask.ciReceive,
                adris.altoclef.tasks.CraftInInventoryTask.ciGridStranded,
                adris.altoclef.chains.MobDefenseChain.mdPriorityCalls,
                adris.altoclef.chains.MobDefenseChain.mdWon,
                adris.altoclef.chains.MobDefenseChain.mdFlee,
                adris.altoclef.chains.MobDefenseChain.mdFight,
                adris.altoclef.chains.MobDefenseChain.mdRet0,
                adris.altoclef.chains.MobDefenseChain.mdRet1,
                adris.altoclef.chains.MobDefenseChain.mdRet2,
                adris.altoclef.chains.MobDefenseChain.mdRet3,
                adris.altoclef.chains.MobDefenseChain.mdRet4,
                adris.altoclef.chains.MobDefenseChain.mdRet5,
                adris.altoclef.chains.MobDefenseChain.mdRet6,
                adris.altoclef.chains.MobDefenseChain.mdRet7,
                adris.altoclef.chains.MobDefenseChain.mdRet8,
                adris.altoclef.chains.MobDefenseChain.mdRet9,
                kaptainwutax.tungsten.combat.VoidGuard.vgCalls,
                // ORDER MATTERS AND I GOT IT WRONG ONCE: vgEdge=%d comes SECOND in the format, so
                // vgEdgeSeen must be the second arg. Inserting the vgFall four ahead of it shifted
                // every value one place and printed afterEdge=117 against onset=5 -- impossible,
                // since both increment in the same branch. That impossibility is the only reason
                // the mis-order was caught rather than believed.
                kaptainwutax.tungsten.combat.VoidGuard.vgEdgeSeen,
                kaptainwutax.tungsten.combat.VoidGuard.vgFallOnset,
                kaptainwutax.tungsten.combat.VoidGuard.vgFallHurt,
                kaptainwutax.tungsten.combat.VoidGuard.vgFallSprint,
                kaptainwutax.tungsten.combat.VoidGuard.vgFallAfterEdge,
                kaptainwutax.tungsten.combat.CombatController.rimAtBackTicks,
                kaptainwutax.tungsten.combat.VoidGuard.kbThrowMax,
                kaptainwutax.tungsten.combat.VoidGuard.kbThrowSum,
                kaptainwutax.tungsten.combat.VoidGuard.kbThrowN,
                kaptainwutax.tungsten.combat.VoidGuard.kbThrowOverRadius,
                kaptainwutax.tungsten.combat.VoidGuard.kbImpulseMax,
                kaptainwutax.tungsten.combat.VoidGuard.kbImpulseSum,
                kaptainwutax.tungsten.combat.VoidGuard.kbImpulseN,
                adris.altoclef.control.SlotHandler.shIssued,
                adris.altoclef.control.SlotHandler.shDropped,
                adris.altoclef.control.SlotHandler.shBlacklisted,
                adris.altoclef.control.SlotHandler.shThrown,
                adris.altoclef.control.SlotHandler.shUnresolvedKept,
                adris.altoclef.chains.PlayerInteractionFixChain.fixKeptCursor,
                adris.altoclef.control.SlotHandler.throwers(),
                adris.altoclef.trackers.EntityTracker.idDropPick,
                adris.altoclef.trackers.EntityTracker.idDeepPicks,
                adris.altoclef.trackers.EntityTracker.idDeepBeatOthers,
                adris.altoclef.tasks.movement.PickupDroppedItemTask.dropBudgetSpent,
                adris.altoclef.tasks.entity.AbstractDoToEntityTask.entityBudgetSpent,
                adris.altoclef.chains.GameMenuTaskChain.gmDisconnect,
                adris.altoclef.chains.GameMenuTaskChain.gmReconnectSet,
                adris.altoclef.chains.GameMenuTaskChain.gmGuardBlocked,
                adris.altoclef.chains.GameMenuTaskChain.gmConnectCalled,
                adris.altoclef.control.SlotHandler.shLastBlacklistedSlot,
                adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask.cursorGaveUp,
                kaptainwutax.tungsten.path.movements.MovementQueue.qLost,
                kaptainwutax.tungsten.path.movements.MovementQueue.qStatusFail,
                kaptainwutax.tungsten.path.movements.MovementQueue.qRefused,
                kaptainwutax.tungsten.path.movements.MovementQueue.qShort,
                kaptainwutax.tungsten.path.movements.MovementQueue.qVetoed,
                kaptainwutax.tungsten.path.movements.MovementQueue.qNoClass,
                kaptainwutax.tungsten.path.movements.MovementQueue.qNullEdge,
                kaptainwutax.tungsten.path.movements.MovementQueue.qExpandedCells,
                kaptainwutax.tungsten.path.movements.MovementQueue.qExpandedRuns,
                kaptainwutax.tungsten.path.movements.MovementQueue.qExpandRefused,
                kaptainwutax.tungsten.path.movements.MovementQueue.qExpandNoFloor,
                kaptainwutax.tungsten.path.movements.MovementQueue.qExpandSample,
                kaptainwutax.tungsten.path.movements.MovementQueue.qRebased,
                kaptainwutax.tungsten.path.movements.MovementQueue.qOffRoute,
                kaptainwutax.tungsten.path.movements.MovementQueue.qUnreachReplan,
                kaptainwutax.tungsten.combat.CombatPathfinder.gridCornerRefused,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbFarRetried,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbFarCondemned,
                kaptainwutax.tungsten.task.FastNavigator.navBridgeRescued,
                kaptainwutax.tungsten.task.BlockPathWalker.walkerHoleHeld,
                kaptainwutax.tungsten.path.movements.MovementDiagonal.diagonalWalled,
                kaptainwutax.tungsten.task.FastNavigator.navPlannedFromStaleTail,
                kaptainwutax.tungsten.task.FastNavigator.navWatchdogUngagged,
                kaptainwutax.tungsten.task.BlockPathWalker.walkerYieldedToExecutor,
                kaptainwutax.tungsten.task.BlockPathWalker.walkerYieldedToMiner,
                kaptainwutax.tungsten.task.BlockPathWalker.tickOff,
                kaptainwutax.tungsten.task.BlockPathWalker.tickBfs,
                kaptainwutax.tungsten.task.BlockPathWalker.tickDir,
                kaptainwutax.tungsten.path.movements.MovementQueue.qParkour,
                kaptainwutax.tungsten.path.movements.MovementQueue.qAdmitMismatch,
                kaptainwutax.tungsten.path.movements.MovementQueue.qPrepTicks,
                kaptainwutax.tungsten.path.movements.MovementQueue.qRunTicks,
                kaptainwutax.tungsten.path.movements.Movement.blindPrepGaveUp,
                kaptainwutax.tungsten.path.movements.MovementQueue.qNullRoute,
                kaptainwutax.tungsten.path.PathFinder.searchGaveUp,
                kaptainwutax.tungsten.path.PathFinder.searchGaveUpSalvaged,
                adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcTick,
                adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcNewPursuit,
                adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcRetryOld,
                adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcHold,
                adris.altoclef.tasks.AbstractDoToClosestObjectTask.dcWander,
                adris.altoclef.tasks.CraftGenericManuallyTask.mcFilled,
                adris.altoclef.tasks.CraftGenericManuallyTask.mcShort,
                adris.altoclef.tasks.CraftGenericManuallyTask.mcFromOutput,
                adris.altoclef.tasks.CraftGenericManuallyTask.mcWait,
                adris.altoclef.tasks.CraftGenericManuallyTask.mcInvalidSlot,
                adris.altoclef.tasks.CraftGenericManuallyTask.mcInFlight,
                adris.altoclef.tasks.CraftGenericManuallyTask.mcSlotSwitched,
                adris.altoclef.tasks.resources.MineAndCollectTask.toolSwaps,
                adris.altoclef.trackers.CraftingRecipeTracker.recipesKnown,
                adris.altoclef.tasks.movement.TimeoutWanderTask.wanderTicks,
                adris.altoclef.tasks.movement.TimeoutWanderTask.wanderMovedCm,
                adris.altoclef.tasks.movement.TimeoutWanderTask.wanderCheckOk,
                adris.altoclef.tasks.movement.TimeoutWanderTask.wanderCheckTrip,
                adris.altoclef.tasks.movement.TimeoutWanderTask.wanderFailPeak,
                adris.altoclef.tasks.movement.TimeoutWanderTask.wanderResetDenied,
                adris.altoclef.tasks.movement.TimeoutWanderTask.wanderKeysKept,
                adris.altoclef.tasks.construction.DestroyBlockTask.dbResetDenied,
                adris.altoclef.util.progresscheck.MovementProgressChecker.airProgressDenied,
                adris.altoclef.chains.WorldSurvivalChain.lavaEscapeTicks,
                adris.altoclef.chains.WorldSurvivalChain.lavaCondHazard,
                adris.altoclef.chains.WorldSurvivalChain.lavaCondAllowed,
                adris.altoclef.chains.WorldSurvivalChain.survivalEntered,
                adris.altoclef.chains.WorldSurvivalChain.survivalPastGuard,
                adris.altoclef.tasks.container.CraftInTableTask.tblAsked,
                adris.altoclef.tasks.container.CraftInTableTask.tblFound,
                adris.altoclef.tasks.container.CraftInTableTask.tblLastDist,
                adris.altoclef.trackers.BlockScanner.scanStarted,
                adris.altoclef.trackers.BlockScanner.scanDone,
                adris.altoclef.trackers.BlockScanner.scanChunks,
                adris.altoclef.trackers.BlockScanner.scanScanned,
                adris.altoclef.trackers.BlockScanner.scanMs,
                adris.altoclef.control.Nav.navUnsafeAir,
                kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode.smSelected,
                kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode.smMoves,
                kaptainwutax.tungsten.path.blockSpaceSearchAssist.SmartMoves.waterMoves,
                kaptainwutax.tungsten.path.PathFinder.physicsRanOut,
                kaptainwutax.tungsten.path.PathFinder.physicsRanOutSalvaged,
                kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.blockRanOut,
                kaptainwutax.tungsten.path.PathFinder.fallGuardRetries,
                kaptainwutax.tungsten.path.Node.fallMovesRejected,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.dropAsked,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.dropSeen,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.dropNearExempt,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanAccepted,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanUnreachable,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanNoBreak,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanBelowFeet,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanUnderfoot,
                adris.altoclef.tasks.resources.MineAndCollectTask.MineOrCollectTask.scanEnclosed,
                adris.altoclef.control.Nav.navStopped,
                adris.altoclef.control.Nav.navStoppedLive,
                adris.altoclef.control.Nav.navSearchOnly,
                adris.altoclef.util.goals.AltoGoal.FLEE_RELOCATED.get(),
                adris.altoclef.util.goals.AltoGoal.FLEE_NO_SPOT.get(),
                adris.altoclef.util.helpers.TungstenHelper.lockBarren,
                adris.altoclef.util.helpers.TungstenHelper.lockProductive,
                adris.altoclef.util.helpers.TungstenHelper.findRefused,
                adris.altoclef.util.helpers.TungstenHelper.barrenGeometry(),
                adris.altoclef.util.helpers.WorldHelper.BreakStats.cbHardness,
                adris.altoclef.util.helpers.WorldHelper.BreakStats.cbAvoid,
                adris.altoclef.util.helpers.WorldHelper.BreakStats.cbPlausible,
                adris.altoclef.util.helpers.WorldHelper.BreakStats.cbReach,
                baritone.altoclef.AltoClefSettings.avoidHitSet,
                baritone.altoclef.AltoClefSettings.avoidHitPred,
                baritone.altoclef.AltoClefSettings.avoidPredCount,
                adris.altoclef.BotBehaviour.breakAvoidersRegistered,
                adris.altoclef.BotBehaviour.lastBreakAvoiderBy,
                adris.altoclef.BotBehaviour.breakAvoiderInstalledBy,
                adris.altoclef.trackers.EntityTracker.etItemsSeen,
                adris.altoclef.trackers.EntityTracker.etItemsGrounded,
                kaptainwutax.tungsten.path.movements.Movement.sprintTicks,
                kaptainwutax.tungsten.path.movements.Movement.moveTicks,
                kaptainwutax.tungsten.combat.CombatController.lowHpTicks,
                kaptainwutax.tungsten.combat.CombatController.lowHpDeclined,
                kaptainwutax.tungsten.combat.CombatController.standOffDeclined,
                kaptainwutax.tungsten.combat.CombatController.hurtTicks,
                kaptainwutax.tungsten.combat.CombatController.hurtAdvancing,
                kaptainwutax.tungsten.combat.CombatController.hurtBackingOff,
                kaptainwutax.tungsten.combat.DamageWatch.hurtWindow,
                kaptainwutax.tungsten.combat.DamageWatch.hurtWhileControlled,
                kaptainwutax.tungsten.combat.DamageWatch.disengageTicks,
                kaptainwutax.tungsten.combat.DamageWatch.disengageSpells,
                kaptainwutax.tungsten.combat.CombatController.controlTicks,
                kaptainwutax.tungsten.combat.CombatController.cqEntry,
                kaptainwutax.tungsten.combat.CombatController.cqNoLos,
                kaptainwutax.tungsten.combat.CombatController.cqTookFromPursue,
                kaptainwutax.tungsten.combat.SafetySystem.losCalls,
                kaptainwutax.tungsten.combat.SafetySystem.losClosest,
                kaptainwutax.tungsten.combat.SafetySystem.losSample,
                kaptainwutax.tungsten.combat.SafetySystem.losNone,
                adris.altoclef.tasks.entity.AbstractKillEntityTask.kaTungstenTicks,
                adris.altoclef.tasks.entity.AbstractKillEntityTask.kaTaskTicks,
                adris.altoclef.tasks.entity.AbstractKillEntityTask.kaCanHitTicks,
                adris.altoclef.tasks.entity.AbstractKillEntityTask.kaEquipTicks,
                adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteGate,
                adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteInRange,
                adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteHungry,
                adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteFalling,
                adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteMlg,
                adris.altoclef.tasks.entity.AbstractDoToEntityTask.dteUnsafe,
                adris.altoclef.chains.MobDefenseChain.mdTungstenTicks,
                adris.altoclef.chains.MobDefenseChain.mdAuraTungstenTicks,
                adris.altoclef.chains.MobDefenseChain.mdFleeStuck,
                adris.altoclef.chains.MobDefenseChain.mdFleeShooter,
                adris.altoclef.chains.MobDefenseChain.mdFarTicks,
                adris.altoclef.chains.MobDefenseChain.mdFarGapMilli,
                adris.altoclef.chains.MobDefenseChain.mdArrows,
                adris.altoclef.chains.MobDefenseChain.mdArrows == 0 ? 0.0
                        : adris.altoclef.chains.MobDefenseChain.mdArrowGapMilli
                          / 1000.0 / adris.altoclef.chains.MobDefenseChain.mdArrows,
                adris.altoclef.chains.MobDefenseChain.mdArrowGapMaxMilli / 1000.0,
                adris.altoclef.chains.MobDefenseChain.mdDraws,
                adris.altoclef.chains.MobDefenseChain.mdDrawTicks,
                adris.altoclef.chains.MobDefenseChain.mdDrawMaxTicks,
                adris.altoclef.chains.MobDefenseChain.mdDraws == 0 ? 0.0
                        : adris.altoclef.chains.MobDefenseChain.mdDrawGapMilli
                          / 1000.0 / adris.altoclef.chains.MobDefenseChain.mdDraws,
                adris.altoclef.chains.MobDefenseChain.mdBandTicks,
                adris.altoclef.chains.MobDefenseChain.mdBandToFirstSwing,
                adris.altoclef.chains.MobDefenseChain.mdDodgeYielded,
                kaptainwutax.tungsten.combat.ApproachLatch.latched,
                kaptainwutax.tungsten.combat.ApproachLatch.declined,
                adris.altoclef.chains.WorldSurvivalChain.breakFailClaimed,
                adris.altoclef.chains.WorldSurvivalChain.breakFailOutOfReach,
                adris.altoclef.chains.WorldSurvivalChain.breakFailBuried,
                adris.altoclef.chains.WorldSurvivalChain.breakBanWide,
                adris.altoclef.chains.WorldSurvivalChain.breakFailRetried,
                adris.altoclef.chains.UnstuckChain.strandedRescues,
                adris.altoclef.chains.UnstuckChain.lastSkip
                        + "@" + adris.altoclef.chains.UnstuckChain.lastSkipSize,
                adris.altoclef.chains.UnstuckChain.lastRealSkip,
                adris.altoclef.chains.UnstuckChain.gpAppended,
                adris.altoclef.chains.UnstuckChain.gpNotInGame,
                adris.altoclef.chains.UnstuckChain.gpPaused,
                adris.altoclef.chains.UnstuckChain.gpNoUserTask,
                adris.altoclef.chains.UnstuckChain.gpContainer,
                adris.altoclef.chains.MobDefenseChain.mdDodgeTaskTicks,
                adris.altoclef.chains.MobDefenseChain.mdDodgeTaskTicks == 0 ? 0.0
                        : adris.altoclef.chains.MobDefenseChain.mdDodgeTaskGapMilli
                          / 1000.0 / adris.altoclef.chains.MobDefenseChain.mdDodgeTaskTicks,
                adris.altoclef.chains.MobDefenseChain.mdDamageDealtTenths / 10.0,
                adris.altoclef.chains.MobDefenseChain.mdDamageSeenTenths / 10.0,
                adris.altoclef.chains.MobDefenseChain.mdLedgerTicks,
                adris.altoclef.chains.MobDefenseChain.mdDropTiny,
                adris.altoclef.chains.MobDefenseChain.mdDropPartial,
                adris.altoclef.chains.MobDefenseChain.mdDropFlat,
                adris.altoclef.chains.MobDefenseChain.mdDropCrit,
                kaptainwutax.tungsten.combat.DamageWatch.takeTiny,
                kaptainwutax.tungsten.combat.DamageWatch.takePartial,
                kaptainwutax.tungsten.combat.DamageWatch.takeFlat,
                kaptainwutax.tungsten.combat.DamageWatch.takeBig,
                kaptainwutax.tungsten.combat.WeaponSelector.slotSyncSent,
                kaptainwutax.tungsten.combat.WeaponSelector.slotReasserted,
                kaptainwutax.tungsten.combat.TriggerBot.gCritSprintReleased,
                kaptainwutax.tungsten.combat.WeaponSelector.strayAttackTicks,
                kaptainwutax.tungsten.combat.WeaponSelector.strayAttackWithNonWeapon,
                kaptainwutax.tungsten.combat.TriggerBot.gSwingHeldForSwitch,
                kaptainwutax.tungsten.combat.CombatController.lowHpByLosing,
                kaptainwutax.tungsten.combat.CombatController.lowHpHeldWithBow,
                kaptainwutax.tungsten.task.PunkPlayerTask.pRestartKept,
                adris.altoclef.chains.MobDefenseChain.mdSwingHits,
                kaptainwutax.tungsten.task.ProjectileDodge.driveTicks,
                kaptainwutax.tungsten.combat.CombatController.hopWind,
                kaptainwutax.tungsten.combat.CombatController.hopAir,
                kaptainwutax.tungsten.combat.CombatController.hopEdge,
                kaptainwutax.tungsten.combat.CombatController.hopInterval,
                kaptainwutax.tungsten.combat.CombatController.hopUnsafe,
                kaptainwutax.tungsten.combat.CombatController.hopFired,
                kaptainwutax.tungsten.combat.CombatController.hopDodge,
                // ORDER MATTERS AND IT IS NOT COMMENTED ANYWHERE ELSE: these arguments are
                // positional against the format string above, so a float slotted where the %d
                // for mdPillarD sits makes String.format throw and placeStats return EMPTY --
                // which silently blanks EVERY counter the bench reads, not just this one.
                // Caught by the stats line coming back with length zero.
                adris.altoclef.chains.MobDefenseChain.mdPillarDefence,
                adris.altoclef.chains.MobDefenseChain.mdDamageTaken,
                // hits/damage/avgGap/maxGap/rangedHits, ticked from the CLIENT so it
                // survives courses where altoclef's chain loop never runs (mdCalls=0).
                kaptainwutax.tungsten.combat.DamageWatch.hits,
                kaptainwutax.tungsten.combat.DamageWatch.damage,
                kaptainwutax.tungsten.combat.DamageWatch.hits == 0 ? 0.0
                        : kaptainwutax.tungsten.combat.DamageWatch.gapSum / kaptainwutax.tungsten.combat.DamageWatch.hits,
                kaptainwutax.tungsten.combat.DamageWatch.gapMax,
                kaptainwutax.tungsten.combat.DamageWatch.rangedHits,
                kaptainwutax.tungsten.combat.DamageWatch.deathsSeen,
                kaptainwutax.tungsten.combat.DamageWatch.unattributedHits,
                kaptainwutax.tungsten.combat.DamageWatch.dmgFall,
                kaptainwutax.tungsten.combat.DamageWatch.dmgLava,
                kaptainwutax.tungsten.combat.DamageWatch.dmgFire,
                kaptainwutax.tungsten.combat.DamageWatch.dmgDrown,
                kaptainwutax.tungsten.combat.DamageWatch.dmgOther,
                kaptainwutax.tungsten.combat.DamageWatch.worstFallHeight,
                kaptainwutax.tungsten.combat.CombatController.strafeFarTicks,
                kaptainwutax.tungsten.combat.CombatController.strafeNearTicks,
                kaptainwutax.tungsten.combat.DamageWatch.voidEntries,
                kaptainwutax.tungsten.combat.DamageWatch.voidTicks,
                kaptainwutax.tungsten.combat.DamageWatch.lastFall,
                adris.altoclef.chains.MobDefenseChain.mdHitFront,
                adris.altoclef.chains.MobDefenseChain.mdHitBack,
                adris.altoclef.chains.MobDefenseChain.mdHitLeft,
                adris.altoclef.chains.MobDefenseChain.mdHitRight,
                adris.altoclef.chains.MobDefenseChain.mdHitCount == 0 ? 0.0
                        : adris.altoclef.chains.MobDefenseChain.mdHitDistSum
                                / adris.altoclef.chains.MobDefenseChain.mdHitCount,
                adris.altoclef.chains.MobDefenseChain.mdHitDistMax,
                adris.altoclef.chains.MobDefenseChain.mdBowTicks,
                // ARROWS ACTUALLY LOOSED. bow_flee and bow_flee_hard both record hits=0 over ~20
                // shot requests a run, and nothing could say which of three very different things
                // that means: never drew, drew and missed, or hit while the course's own detector
                // (hp_drop_events, min_dist=5) looked past it. BowShooter has counted this all
                // along in getShotsFired() -- and NOTHING called it, which is the same shape as
                // mdFleeStuck earlier today: a counter that exists and cannot be read is not
                // instrumentation. Reset with the others in resetRunCounters.
                kaptainwutax.tungsten.task.BowShooter.getShotsFired(),
                // ...and the arrows the ABORT paths threw, which shotsFired never counted. Vanilla
                // fires on key release past ~3 ticks of draw, so "aborted" draws are wild shots,
                // not cancelled ones. PREDICTION SET BEFORE THE RUN, per RULE ONE: on bow_flee,
                // which requests ~20 and reported bowShots=6, bowWild should land near 14. Zero
                // would mean the aborts are not happening and this reading is wrong.
                kaptainwutax.tungsten.task.BowShooter.getWildShots(),
                kaptainwutax.tungsten.task.BowShooter.getNoSolution(),
                kaptainwutax.tungsten.task.BowShooter.getRestarts(),
                // WHICH half of the shot ran out of time. A bow course that goes red cannot
                // otherwise say whether the TURN never arrived (bowAimTO) or the release gate was
                // unreachable (bowDrawTO) — and those want opposite fixes. bowBestMiss is how close
                // the last draw ever came to the gate, in blocks: a value hovering just above the
                // threshold means the gate is nearly reachable, a large one means it is not.
                kaptainwutax.tungsten.task.BowShooter.getAimTimeouts(),
                kaptainwutax.tungsten.task.BowShooter.getDrawTimeouts(),
                kaptainwutax.tungsten.task.BowShooter.getBestMiss(),
                // Ticks spent FACING the target. Vanilla refuses to sprint without forward input,
                // so this is time spent walking backwards — measured on bow_flee at 1.47 blocks/s
                // of ground lost. Against a 60s course that requests a shot every 3s, this number
                // decides whether holding 12 blocks is even possible.
                kaptainwutax.tungsten.task.BowShooter.getFacingTicks(),
                // Shots refused because a live flee order had no distance to spare. Reading this
                // beside bowShots says whether the bot is kiting in bursts or just not shooting.
                kaptainwutax.tungsten.task.BowShooter.getDeclinedTooClose(),
                // held / ran / plans -- see RunAwayTask.fleeHeld for what decides bow_flee's
                // last two reds. Story first, counter second: this exists to refute or confirm
                // "flight is stop-start" before a line of that logic is touched.
                kaptainwutax.tungsten.task.RunAwayTask.fleeHeld,
                // held / SEARCHING / running / plans. Searching used to be folded into
                // running, which hid ~14s of a 60s course spent standing still looking
                // for a path -- PathFinder.active means a search is in flight, not motion.
                kaptainwutax.tungsten.task.RunAwayTask.fleeSearch,
                kaptainwutax.tungsten.task.RunAwayTask.fleeRan,
                kaptainwutax.tungsten.task.RunAwayTask.fleePlans,
                kaptainwutax.tungsten.task.RunAwayTask.fleeDriveTicks,
                kaptainwutax.tungsten.task.RunAwayTask.fleeDriveBlocked,
                kaptainwutax.tungsten.path.movements.MovementQueue.qBurnedInPlace,
                kaptainwutax.tungsten.path.movements.MovementQueue.qTeleported,
                kaptainwutax.tungsten.path.movements.MovementQueue.qStuckNoMove,
                kaptainwutax.tungsten.path.movements.MovementQueue.stuckScenes(),
                kaptainwutax.tungsten.path.PathFinder.staleRootRejections,
                kaptainwutax.tungsten.path.movements.Movement.placeRequested,
                kaptainwutax.tungsten.path.movements.Movement.placeOnCooldown,
                kaptainwutax.tungsten.path.movements.Movement.placeNoHit,
                kaptainwutax.tungsten.path.movements.Movement.placeClicked,
                kaptainwutax.tungsten.path.movements.Movement.motionSteered,
                // The shared rate gate every placement now passes through — gateHeld is how many
                // clicks it swallowed, i.e. proof the limit is doing something rather than compiling.
                kaptainwutax.tungsten.helpers.BlockPlaceHelper.gatedThrough,
                kaptainwutax.tungsten.helpers.BlockPlaceHelper.gatedByCooldown,
                kaptainwutax.tungsten.helpers.BlockPlaceHelper.queued(),
                kaptainwutax.tungsten.helpers.BlockPlaceHelper.placedFromQueue(),
                // Rule ONE for the entity-approach release: proof it RAN, not that it compiled.
                adris.altoclef.tasks.movement.GetToEntityTask.entityReleased,
                adris.altoclef.tasks.movement.GetToEntityTask.entityWandered,
                adris.altoclef.trackers.EntityTracker.idAsked,
                adris.altoclef.trackers.EntityTracker.idNoneTracked,
                adris.altoclef.trackers.EntityTracker.idBlacklisted,
                adris.altoclef.trackers.EntityTracker.idReturned,
                adris.altoclef.tasks.ResourceTask.rtReached,
                adris.altoclef.tasks.ResourceTask.rtAvoided,
                adris.altoclef.tasks.ResourceTask.rtDropped,
                adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalk,
                adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkMoved,
                adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkCloser,
                adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkAimed,
                adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkLeased,
                adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkYawKept,
                adris.altoclef.tasks.movement.GetToEntityTask.entityCloseWalkRetarget,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkAbove,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkOnTop,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBeside,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow1,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow2,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkBelow3,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkJumped,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkSneakReleased,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkLeaseCleared,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkDeepDeclined,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkFwdKept,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkFwdLost,
                adris.altoclef.tasks.movement.GetToEntityTask.closeWalkKeysKept);
    }

    public int critHits() { return kaptainwutax.tungsten.combat.TriggerBot.lifetimeCrits; }
    public int totalHits() { return kaptainwutax.tungsten.combat.TriggerBot.lifetimeHits; }

    /**
     * How many separate blows the bot has TAKEN this run -- one per hurt onset, not per tick.
     *
     * <p>Exists because the bench cannot classify a fall without it. The runner samples once a
     * second (scenario.py:450) and asks for {@code hurtTime}, which lasts 10 ticks -- half a
     * second. A blow whose whole hurt window falls between two samples is invisible, and the
     * fall it caused is then recorded as "SELF (walked off)" because no hit was seen. Roughly
     * half of knockback falls can be mislabelled that way, and "self-falls == 0" is a GATED
     * criterion on edge_duel while knockback falls are not gated at all.
     *
     * <p>A monotonic count cannot be missed by a slow poll: the sampler compares the value
     * against the one it read a moment ago, and any increase means a blow landed in between,
     * whenever inside that second it actually happened.
     *
     * <p>This does NOT relax the criterion -- both kinds of fall still count, and both are still
     * reported. It only decides which of the two a fall belongs to, using evidence that does not
     * depend on catching a 10-tick flag with a 20-tick sampler.
     */
    public int hitsTaken() { return kaptainwutax.tungsten.combat.VoidGuard.kbImpulseN; }

    /**
     * Ticks spent fighting with a rim at the bot's back -- the knockback guard's exposure.
     *
     * <p>Reset per run, so it is a measurement rather than a lifetime tally (a counter that does
     * not reset was compared against one that did earlier tonight, and produced a confident and
     * completely wrong conclusion).
     *
     * <p>Read it on courses where the geometry is tight. On a 5x5 platform a centred fighter has
     * clear ground behind and the guard rarely fires; on a bridge the rim is always behind, and
     * the same guard could hold the bot in permanent retreat. The counter is what distinguishes
     * those two worlds, and it gates nothing -- a number that gates nothing cannot be gamed.
     */
    public int rimAtBackTicks() { return kaptainwutax.tungsten.combat.CombatController.rimAtBackTicks; }

    /** Ticks the flee threat spent inside melee reach (3.0) this run. See RunAwayTask.reachTicks. */
    public int fleeReachTicks() { return kaptainwutax.tungsten.task.RunAwayTask.reachTicks; }

    /** Ticks the flee threat spent within a stride of reach (3.0-4.5) this run. */
    public int fleeNearTicks() { return kaptainwutax.tungsten.task.RunAwayTask.nearTicks; }

    /** Of the in-reach ticks, how many had the bow drawn (sprint unavailable). */
    public int fleeReachDrawingTicks() { return kaptainwutax.tungsten.task.RunAwayTask.reachDrawingTicks; }

    /** Of the near-reach ticks, how many had the bow drawn. */
    public int fleeNearDrawingTicks() { return kaptainwutax.tungsten.task.RunAwayTask.nearDrawingTicks; }

    /** Of the in-reach ticks, how many were spent sprinting. */
    public int fleeReachSprintTicks() { return kaptainwutax.tungsten.task.RunAwayTask.reachSprintTicks; }

    /** Of the in-reach ticks, how many had the bot moving AWAY from the threat. */
    public int fleeReachAwayTicks() { return kaptainwutax.tungsten.task.RunAwayTask.reachAwayTicks; }

    /**
     * Ticks this run in which the bow owned the camera -- the whole shot, aiming included.
     *
     * <p>The number that decides whether a flee may ever turn. BowShooter.tick is gated on
     * {@code active}, not on {@code drawing}, and it drives the look pipeline at the target every
     * tick it runs; its own comment calls this "the kiting cost". Two attempts to turn the flee
     * away from a closing threat failed because they were guarded on !isDrawing() and were
     * overwritten inside the aiming phase. Before widening that guard to !isActive(), this says
     * whether such a guard would leave the flee any ticks at all.
     */
    public int bowFacingTicks() { return kaptainwutax.tungsten.task.BowShooter.getFacingTicks(); }

    /**
     * Ticks this run in which the flee actually DROVE the player, versus ticks it chose to hold.
     *
     * <p>The question four failed fixes pointed at: the exposure profile barely moved whatever I
     * changed about HOW the flee drives (27% moving away, 3-5% sprinting, across two camera
     * guards, a rotation route and the hold's standstill). If the flee only owns a fraction of the
     * 1200-tick course, no change to its driving can matter, and that would explain four flat
     * results in a row far better than any property of the driving itself.
     */
    public int fleeDriveTicks() { return kaptainwutax.tungsten.task.RunAwayTask.fleeDriveTicks; }

    /** Ticks the flee spent holding position instead of driving. */
    public int fleeHeldTicks() { return kaptainwutax.tungsten.task.RunAwayTask.fleeHeld; }

    /** Ticks the flee declined because no flee order was active. */
    public int fleeIdleInactive() { return kaptainwutax.tungsten.task.RunAwayTask.fleeIdleInactive; }

    /** Ticks the flee declined because the threat could not be resolved (gone or out of view). */
    public int fleeIdleNoThreat() { return kaptainwutax.tungsten.task.RunAwayTask.fleeIdleNoThreat; }

    /** Client ticks seen this run -- the denominator every flee share must be divided by. */
    public int fleeClientTicks() { return kaptainwutax.tungsten.task.RunAwayTask.clientTicks; }

    /** Ticks with a threat within 5 blocks. */
    public int fleeNearThreatTicks() { return kaptainwutax.tungsten.task.RunAwayTask.nearThreatTicks; }

    /** Of those, ticks the bot was motionless (speed &lt; 0.02) -- standing, measured not inferred. */
    public int fleeStillNearThreat() { return kaptainwutax.tungsten.task.RunAwayTask.stillNearThreatTicks; }

    /** Motionless ticks attributed: executor running / search in flight / nobody driving. */
    public int fleeStillExecutor() { return kaptainwutax.tungsten.task.RunAwayTask.stillExecutorTicks; }
    public int fleeStillSearch()   { return kaptainwutax.tungsten.task.RunAwayTask.stillSearchTicks; }
    public int fleeStillNobody()   { return kaptainwutax.tungsten.task.RunAwayTask.stillNobodyTicks; }
    /** Of the nobody-ticks, how many had a MovementQueue claiming the tick without moving. */
    public int fleeStillMoveQueue() { return kaptainwutax.tungsten.task.RunAwayTask.stillMoveQueueTicks; }

    /** Of the nobody-ticks, how many had a movement key held -- collision if high, key-clearing if 0. */
    public int fleeStillKeysDown() { return kaptainwutax.tungsten.task.RunAwayTask.stillKeysDownTicks; }

    /** Of the stalled keys-down ticks, how many had the threat in body contact (&lt;1.5 blocks). */
    public int fleeStillTouching() { return kaptainwutax.tungsten.task.RunAwayTask.stillTouchingThreatTicks; }

    /** Largest distance from arena centre seen at a stalled tick, in tenths of a block. */
    public int fleeStillMaxRadius() { return kaptainwutax.tungsten.task.RunAwayTask.stillMaxRadius; }
    /** Sum of those distances, for a mean. */
    public int fleeStillRadiusSum() { return kaptainwutax.tungsten.task.RunAwayTask.stillRadiusSum; }

    /** Stalled ticks seen after VoidGuard, and how many still had a movement key held then. */
    public int fleeStalledAfterGuard() { return kaptainwutax.tungsten.task.RunAwayTask.stalledSeenAfterGuard; }
    public int fleeKeysAfterGuard()    { return kaptainwutax.tungsten.task.RunAwayTask.keysDownAfterGuardTicks; }

    /** Blows the bot has TAKEN this run, and the total damage of them. */
    public int dwHits()      { return kaptainwutax.tungsten.combat.DamageWatch.hits; }
    public double dwDamage() { return kaptainwutax.tungsten.combat.DamageWatch.damage; }

    /** Distance to the threat when blows landed: max, sum and count, in hundredths of a block. */
    public int hitDistMax() { return kaptainwutax.tungsten.task.RunAwayTask.hitDistMax; }
    public int hitDistSum() { return kaptainwutax.tungsten.task.RunAwayTask.hitDistSum; }
    public int hitDistN()   { return kaptainwutax.tungsten.task.RunAwayTask.hitDistN; }

    /** Of the flee's driving ticks, how many were spent sprinting. */
    public int fleeSprintTicks() { return kaptainwutax.tungsten.task.RunAwayTask.fleeSprintTicks; }
    /** Ticks spent hugging the rim -- accumulates, so it can judge a metre of clearance. */
    public int fleeAtRimTicks() { return kaptainwutax.tungsten.task.RunAwayTask.fleeAtRimTicks; }

    /**
     * The reach band the mod counts exposure at, so the bench does not have to restate it.
     *
     * <p>This constant drifted between mod and harness TWICE tonight -- 3.0 against 3.6, then 3.6
     * against 5.5 -- each time because one side was corrected and the other was not, and both
     * print on the same line under the same word. Care at the moment of editing prevented neither.
     * A value duplicated across two repositories of truth will drift; one that is read cannot.
     */
    public double fleeReachBand() { return kaptainwutax.tungsten.task.RunAwayTask.REACH_BAND; }

    /** Of the flee's non-sprinting drive ticks: with a shot running, and without one. */
    public int fleeNoSprintBow()   { return kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintBowTicks; }
    public int fleeNoSprintOther() { return kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintOtherTicks; }
    /** Of those, how many were mid-turn (not yet facing away enough for vanilla to sprint). */
    public int fleeNoSprintTurning() { return kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintTurningTicks; }
    /** Facing away with the key down while vanilla refuses, and how many of those were hungry. */
    public int fleeNoSprintUnexplained() { return kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintUnexplained; }
    public int fleeNoSprintHungry()      { return kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintHungryTicks; }
    /** Of the refused ticks, how many had sneak held (sneak cancels sprint). */
    public int fleeNoSprintSneak()       { return kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintSneakTicks; }
    /** Of the refused ticks, how many had a horizontal collision. */
    public int fleeNoSprintCollide()     { return kaptainwutax.tungsten.task.RunAwayTask.fleeNoSprintCollideTicks; }
    /** Of those collisions: how many touched the chaser, and the furthest radius one happened at. */
    public int fleeCollideThreat()       { return kaptainwutax.tungsten.task.RunAwayTask.fleeCollideWithThreatTicks; }
    public int fleeCollideMaxRadius()    { return kaptainwutax.tungsten.task.RunAwayTask.fleeCollideMaxRadius; }

    /** Arm the tick-rate Y probe: records the lowest and highest Y until stopped. Sampling
     *  position over rcon gives about three points a second and walks past the apex of a
     *  bounce — measured -59.85 that way where the tick trace says -55.4. */
    public void probeYStart() { kaptainwutax.tungsten.task.SlimeBounceTask.probeStart(); }
    public void probeYStop() { kaptainwutax.tungsten.task.SlimeBounceTask.probeStop(); }
    public double probeYMin() { return kaptainwutax.tungsten.task.SlimeBounceTask.probeMin(); }
    public double probeYMax() { return kaptainwutax.tungsten.task.SlimeBounceTask.probeMax(); }

    /** Crossings STARTED — separates "never triggered" from "ran but bounced zero times". */
    public int slimeStarts() { return kaptainwutax.tungsten.task.SlimeBounceTask.starts; }

    /** Bounces counted in the last/current crossing — non-zero means the task really ran. */
    public int slimeBounces() { return kaptainwutax.tungsten.task.SlimeBounceTask.getBounces(); }
    public int pillarPlaced() { return kaptainwutax.tungsten.task.PillarTask.getPlaced(); }

    // Movement lever for the cognitive agent — the keystone that ties perception
    // (getGameState) to action. Fire-and-poll: gotoXYZ() then pathStatus() until
    // arrived. This is also how the agent repositions to reach far fillSelection
    // cells. Server-agnostic (pure coords), single-source (wraps the existing
    // goto command through the configured prefix).
    private int[] _gotoGoal = null;

    /** Navigate to a world coordinate via the TUNGSTEN physics pathfinder (the
     *  project's unified pather — walks/parkours/bridges on the movement model).
     *  Returns immediately; poll pathStatus() until arrived. The agent can also
     *  drive tungsten directly (ChatMessage(prefix+"goto x y z"), bridgeTo) or
     *  use altoclef @goto for the baritone/shredder navigator — this lever is
     *  the clean default. Server-agnostic (pure coords). */
    public Map<String, Object> gotoXYZ(int x, int y, int z) {
        _gotoGoal = new int[]{x, y, z};
        String p = kaptainwutax.tungsten.TungstenMod.getCommandPrefix();
        ChatMessage(p + "goto " + x + " " + y + " " + z);   // tungsten intercepts chat send
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("goal", x + "," + y + "," + z);
        return out;
    }

    /** Navigate toward a FAR target with a RECEDING HORIZON — never asks the
     *  pathfinder for the whole route (huge goals freeze the search). Picks a
     *  waypoint at most `horizon` blocks toward the target and gotos it. The
     *  agent loops: gotoFar → pathStatus (until arrived) → gotoFar … until
     *  finalSegment=true. Each call advances one segment; the intermediate hops
     *  keep the current Y (only the final segment aims at the real Y). This is
     *  the "roughly get there, refine as you approach" lever (server-agnostic). */
    public Map<String, Object> gotoFar(int x, int y, int z, int horizon) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            var me = MinecraftClient.getInstance().player;
            if (me == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            int h = Math.max(4, horizon);
            double dx = x + 0.5 - me.getX(), dz = z + 0.5 - me.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            int wx, wy, wz;
            boolean finalSeg;
            if (horiz <= h) {
                wx = x; wy = y; wz = z; finalSeg = true;                 // last hop → real target
            } else {
                double f = h / horiz;
                wx = (int) Math.round(me.getX() + dx * f);
                wz = (int) Math.round(me.getZ() + dz * f);
                wy = (int) Math.round(me.getY());                        // stay near current Y mid-route
                finalSeg = false;
            }
            gotoXYZ(wx, wy, wz);                                         // issue this segment via tungsten
            out.put("ok", true);
            out.put("waypoint", wx + "," + wy + "," + wz);
            out.put("finalSegment", finalSeg);
            out.put("remainingDist", String.format("%.1f", horiz));
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Client performance snapshot — the number you need before and after any
     *  perf work: {fps, renderers, pathfinderActive, executorRunning, walker,
     *  combatActive}. `renderers` is how many render objects tungsten is
     *  currently drawing (paths, plans, trajectories); on a software renderer
     *  that count is the single biggest FPS lever, so a measurement that does
     *  not report it cannot tell "the mod is slow" from "llvmpipe is slow".
     *  Sample it with visualisation ON and OFF to separate the two. */
    public Map<String, Object> getPerfStats() {
        Map<String, Object> out = new HashMap<>();
        try {
            var mc = MinecraftClient.getInstance();
            out.put("fps", mc.getCurrentFps());
            int renderers = kaptainwutax.tungsten.TungstenModRenderContainer.RENDERERS.size()
                    + kaptainwutax.tungsten.TungstenModRenderContainer.BLOCK_PATH_RENDERER.size()
                    + kaptainwutax.tungsten.TungstenModRenderContainer.RUNNING_PATH_RENDERER.size()
                    + kaptainwutax.tungsten.TungstenModRenderContainer.COMBAT_TRAJECTORY.size()
                    + kaptainwutax.tungsten.TungstenModRenderContainer.BREAK_PLAN.size()
                    + kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.size()
                    + kaptainwutax.tungsten.TungstenModRenderContainer.SELECTION.size();
            out.put("renderers", renderers);
            out.put("renderVisualization",
                    kaptainwutax.tungsten.TungstenConfig.get().renderVisualization);
            out.put("pathfinderActive",
                    kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get());
            out.put("blockSearchActive",
                    kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.active);
            out.put("executorRunning",
                    kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR != null
                            && kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR.isRunning());
            out.put("walkerRunning", kaptainwutax.tungsten.task.BlockPathWalker.isRunning());
            out.put("combatActive", kaptainwutax.tungsten.task.PunkPlayerTask.isActive());
            out.put("ok", true);
        } catch (Throwable e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** Respawn after death: close the death screen and send the respawn request.
     *  Returns true if the request went out. autoRespawn normally handles this,
     *  but a bot that dies with no task running (or on a server where the death
     *  screen sticks) stays a corpse forever — a whole 3-minute stand run was
     *  spent measuring a dead body lying on the ground. Safe to call when alive
     *  (no-op). */
    public boolean respawnPlayer() {
        return Boolean.TRUE.equals(onClientThread(() -> {
            var client = MinecraftClient.getInstance();
            if (client.player == null) return false;
            if (client.player.getHealth() > 0 && client.currentScreen == null) return false;
            if (client.currentScreen != null) client.setScreen(null);
            client.player.requestRespawn();
            return true;
        }, false));
    }

    /** Reset the WHOLE tungsten config to shipped defaults and persist it.
     *  tungsten.json is rewritten in full by every `;settings x y`, so any value
     *  saved once shadows new shipped defaults forever — stands silently ran
     *  months-old combat tuning with visualisation OFF. Call this at the start of
     *  a test run (or after experimenting) to get a known-clean state, then pin
     *  only what the scenario needs. Returns the visualisation + combat-aim values
     *  that are live afterwards. */
    public Map<String, Object> resetTungstenConfig() {
        Map<String, Object> out = new HashMap<>();
        try {
            kaptainwutax.tungsten.TungstenConfig.resetToDefaults();
            var c = kaptainwutax.tungsten.TungstenConfig.get();
            out.put("ok", true);
            out.put("renderVisualization", c.renderVisualization);
            out.put("renderPathMoves", c.renderPathMoves);
            out.put("renderCombat", c.renderCombat);
            out.put("combatWindMouseMaxStep", c.combatWindMouseMaxStep);
            out.put("combatWindMouseGravity", c.combatWindMouseGravity);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** Drop-in-swap toggle (goal 13.1): route altoclef/shredder navigation through
     *  tungsten's physics executor. On = useTungsten (delegate flat segments) +
     *  experimentalPathfinding (also ascend/descend). With it on, @goto/@get/
     *  @gamer paths hand qualifying segments to tungsten. Returns the flags. */
    public Map<String, Object> setTungstenPathing(boolean on) {
        Map<String, Object> out = new HashMap<>();
        try {
            var s = _mod.getClientBaritoneSettings();
            s.useTungsten.value = on;
            s.experimentalPathfinding.value = on;
            adris.altoclef.util.helpers.TungstenHelper.setPrimary(on); // route altoclef goals straight to tungsten
            // Couple smartMoves: tungsten-primary NEEDS the SmartMoves neighbour generation
            // (parkour/jump-up moves) to climb terrain. Without it, @goto+primary follows a
            // grid-BFS stub that can't route staircases/steep climbs (terrain_test A/B FAIL
            // -> PASS once smartMoves is on). They belong together for altoclef nav.
            kaptainwutax.tungsten.TungstenConfig.get().smartMoves = on;
            out.put("ok", true);
            out.put("useTungsten", s.useTungsten.value);
            out.put("experimentalPathfinding", s.experimentalPathfinding.value);
            out.put("tungstenPrimary", adris.altoclef.util.helpers.TungstenHelper.isPrimary());
            out.put("smartMoves", kaptainwutax.tungsten.TungstenConfig.get().smartMoves);
            out.put("tungstenMinSegment", s.tungstenMinSegment.value);
        } catch (Exception e) { out.put("ok", false); out.put("reason", e.getMessage()); }
        return out;
    }

    /** EXPERIMENTAL (#1.6.1): toggle tungsten-native SmartMoves block-space neighbour
     *  generation (Traverse/Ascend/Descend/Parkour) vs the blind r=8 scan. Returns the
     *  new state. Default off — for terrain-routing tests / the search rework. */
    public Map<String, Object> setTungstenSmartMoves(boolean on) {
        Map<String, Object> out = new HashMap<>();
        try {
            kaptainwutax.tungsten.TungstenConfig.get().smartMoves = on;
            out.put("ok", true);
            out.put("smartMoves", kaptainwutax.tungsten.TungstenConfig.get().smartMoves);
        } catch (Exception e) { out.put("ok", false); out.put("reason", e.getMessage()); }
        return out;
    }

    /** Toggle the block-space pathfinder's plan-bridging move (place-as-a-move in the
     *  search — the mirror of break-through). Off by default; the executor still needs a
     *  block in hand to actually pave. Test lever for #46 core place-as-a-move. */
    public Map<String, Object> setTungstenPlanPlaceMoves(boolean on) {
        Map<String, Object> out = new HashMap<>();
        try {
            kaptainwutax.tungsten.TungstenConfig.get().planPlaceMoves = on;
            out.put("ok", true);
            out.put("planPlaceMoves", kaptainwutax.tungsten.TungstenConfig.get().planPlaceMoves);
        } catch (Exception e) { out.put("ok", false); out.put("reason", e.getMessage()); }
        return out;
    }

    /** Toggle the BlockPathWalker's per-tick white-box debug log (waypoint, dist, onGround,
     *  jump, playerYaw vs target yaw, velocity) to the chat buffer — used to understand a
     *  FAILING terrain climb mechanism-first. Off by default. */
    public Map<String, Object> setWalkerDebug(boolean on) {
        Map<String, Object> out = new HashMap<>();
        try {
            kaptainwutax.tungsten.task.BlockPathWalker.DEBUG = on;
            out.put("ok", true);
            out.put("walkerDebug", on);
        } catch (Exception e) { out.put("ok", false); out.put("reason", e.getMessage()); }
        return out;
    }

    /** Read the current pathing-delegation mode (goal 13). */
    /**
     * Read a TungstenConfig boolean back by NAME, so a pinned flag can be PROVEN to have landed.
     *
     * <p>⛔ WHY: the bench pins a flag by sending {@code ;settings NAME VALUE} and prints the line
     * it sent. That proves the command was SENT, not that it took effect -- and settings persist
     * in tungsten.json across runs, so a pin that quietly fails leaves the PREVIOUS arm's value in
     * place and both arms measure the same thing. Caught on the fall-guard A/B: a control run with
     * pathAvoidsFallDamage pinned false reported 1387 moves rejected by the guard, which is only
     * possible if the guard was on. Another control run in the same sweep read 0. An A/B whose
     * arms cannot be shown to differ is not a measurement.
     */
    public Map<String, Object> readFlag(String name) {
        try {
            java.lang.reflect.Field f = kaptainwutax.tungsten.TungstenConfig.class.getField(name);
            return Map.of("ok", true, "name", name,
                    "value", String.valueOf(f.get(kaptainwutax.tungsten.TungstenConfig.get())));
        } catch (Exception e) {
            return Map.of("ok", false, "name", name, "reason", String.valueOf(e.getMessage()));
        }
    }

    public Map<String, Object> pathingMode() {
        Map<String, Object> out = new HashMap<>();
        try {
            var s = _mod.getClientBaritoneSettings();
            out.put("ok", true);
            out.put("useTungsten", s.useTungsten.value);
            out.put("experimentalPathfinding", s.experimentalPathfinding.value);
            out.put("tungstenPrimary", adris.altoclef.util.helpers.TungstenHelper.isPrimary());
            out.put("tungstenMinSegment", s.tungstenMinSegment.value);
        } catch (Exception e) { out.put("ok", false); out.put("reason", e.getMessage()); }
        return out;
    }

    /** Poll the current navigation: busy (still pathing/task running), current
     *  pos, distance to the last gotoXYZ goal, and arrived (within 1.5). The
     *  agent loops gotoXYZ → pathStatus until arrived, then acts. */
    public Map<String, Object> pathStatus() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            var me = MinecraftClient.getInstance().player;
            if (me == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            out.put("ok", true);
            out.put("busy", hasActiveTask());
            out.put("pos", String.format("%.1f,%.1f,%.1f", me.getX(), me.getY(), me.getZ()));
            if (_gotoGoal != null) {
                double dx = _gotoGoal[0] + 0.5 - me.getX(), dy = _gotoGoal[1] - me.getY(), dz = _gotoGoal[2] + 0.5 - me.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                out.put("goal", _gotoGoal[0] + "," + _gotoGoal[1] + "," + _gotoGoal[2]);
                out.put("distance", String.format("%.1f", dist));
                out.put("arrived", dist < 1.5);
            }
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Cancel current navigation / tasks — stops BOTH the tungsten pather
     *  (;stop) and any altoclef task (@stop), so the agent's "stop" is total. */
    public Map<String, Object> stopPathing() {
        String p = kaptainwutax.tungsten.TungstenMod.getCommandPrefix();
        ChatMessage(p + "stop");                                                            // tungsten
        executeInNetworkThread(() -> _mod.getCommandExecutor().executeWithPrefix("stop"));  // altoclef
        _gotoGoal = null;
        return Map.of("ok", true);
    }

    // Combat targeting levers (tungsten PunkPlayerTask). The agent (brain) decides
    // WHO to hit; tungsten executes approach A* + aura. Distinct from altoclef's
    // threat-table (attackPlayer/avoidPlayer) — these drive the tungsten engine.

    /** Hunt one player by name via the tungsten combat engine (approach + aura). */
    public Map<String, Object> punk(String name) {
        net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                kaptainwutax.tungsten.task.PunkPlayerTask.start(name));
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true); out.put("target", name);
        return out;
    }

    /** Multi-target hunt: attack the NEAREST player in `allow` (empty = any),
     *  never hitting anyone in `avoid`. Re-targets automatically as the fight
     *  evolves. The agent picks the sets (brain); the mod executes. */
    public Map<String, Object> punkAny(java.util.List<String> allow, java.util.List<String> avoid) {
        net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                kaptainwutax.tungsten.task.PunkPlayerTask.startAny(allow, avoid));
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("allow", allow == null ? java.util.List.of() : allow);
        out.put("avoid", avoid == null ? java.util.List.of() : avoid);
        return out;
    }

    /** Update the avoid-list mid-fight (never hit these players). */
    public Map<String, Object> punkAvoid(java.util.List<String> avoid) {
        kaptainwutax.tungsten.task.PunkPlayerTask.setAvoid(avoid);
        return Map.of("ok", true);
    }

    /** Stop the tungsten combat engine. */
    public Map<String, Object> punkStop() {
        net.minecraft.client.MinecraftClient.getInstance().execute(
                kaptainwutax.tungsten.task.PunkPlayerTask::stop);
        return Map.of("ok", true);
    }

    /** Flee a player, keeping at least {@code distance} blocks (min 3). The mirror
     *  of punk: tungsten paths to the safest reachable point AWAY from the threat,
     *  re-planning as it chases, and never backs off into the void. Stops any punk
     *  in progress. The agent decides when to run; the mod keeps the distance. */
    public Map<String, Object> runAwayPlayer(String name, double distance) {
        net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                kaptainwutax.tungsten.task.RunAwayTask.start(name, distance));
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true); out.put("threat", name); out.put("keepDistance", Math.max(3.0, distance));
        return out;
    }

    /** Stop fleeing. */
    public Map<String, Object> runAwayStop() {
        net.minecraft.client.MinecraftClient.getInstance().execute(
                kaptainwutax.tungsten.task.RunAwayTask::stop);
        return Map.of("ok", true);
    }

    /** Flee status: active + the threat currently tracked (null if none). */
    public Map<String, Object> runAwayStatus() {
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("active", kaptainwutax.tungsten.task.RunAwayTask.isActive());
        out.put("threat", kaptainwutax.tungsten.task.RunAwayTask.getCurrentThreat());
        return out;
    }

    /** Combat status: active + the player currently being fought (null if none). */
    public Map<String, Object> punkStatus() {
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("active", kaptainwutax.tungsten.task.PunkPlayerTask.isActive());
        out.put("target", kaptainwutax.tungsten.task.PunkPlayerTask.getCurrentTarget());
        return out;
    }

    /** #29 test lever: simulate a task that set a mine/combat aim and then DIED
     *  without clearing it (the root of the frozen-camera bug). Sets the WindMouse
     *  target ONCE to current facing + dyaw and does not refresh it. The aim must
     *  auto-release within STALE_MS; poll {@link #windMouseHasTarget()} to verify. */
    public Map<String, Object> pokeStaleAim(double dyaw) {
        net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
            var p = net.minecraft.client.MinecraftClient.getInstance().player;
            if (p != null)
                kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(
                        p.getYaw() + (float) dyaw, p.getPitch());
        });
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        return out;
    }

    /** Whether the WindMouse aim currently holds a target. Used to verify the
     *  stale-aim auto-release (#29): a target left by a dead task must clear itself. */
    public Map<String, Object> windMouseHasTarget() {
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("hasTarget", kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.hasTarget());
        return out;
    }

    // WorldEdit-like region selection + fill (TODO block 9). A lever for the
    // agent: select a region, then fill/clear it — the mod places/breaks via
    // the physics primitives; the agent repositions to reach far cells. No
    // server commands (works in survival). Server-agnostic (pure coordinates).
    private int[] _selMin = null, _selMax = null;

    /** Set the WorldEdit-like selection region (inclusive corners, any order). */
    public Map<String, Object> select(int x1, int y1, int z1, int x2, int y2, int z2) {
        _selMin = new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)};
        _selMax = new int[]{Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)};
        // visualize the selection box (gated by renderVisualization)
        try {
            kaptainwutax.tungsten.TungstenModRenderContainer.SELECTION.clear();
            kaptainwutax.tungsten.TungstenModRenderContainer.SELECTION.add(new kaptainwutax.tungsten.render.Cuboid(
                    new net.minecraft.util.math.Vec3d(_selMin[0], _selMin[1], _selMin[2]),
                    new net.minecraft.util.math.Vec3d(_selMax[0] - _selMin[0] + 1, _selMax[1] - _selMin[1] + 1, _selMax[2] - _selMin[2] + 1),
                    new kaptainwutax.tungsten.render.Color(255, 230, 60)));
        } catch (Exception ignored) {}
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("min", _selMin[0] + "," + _selMin[1] + "," + _selMin[2]);
        out.put("max", _selMax[0] + "," + _selMax[1] + "," + _selMax[2]);
        int vol = (_selMax[0] - _selMin[0] + 1) * (_selMax[1] - _selMin[1] + 1) * (_selMax[2] - _selMin[2] + 1);
        out.put("volume", vol);
        return out;
    }

    public Map<String, Object> clearSelection() {
        _selMin = _selMax = null;
        try { kaptainwutax.tungsten.TungstenModRenderContainer.SELECTION.clear(); } catch (Exception ignored) {}
        return Map.of("ok", true);
    }

    /** WorldEdit-like //set — place `blockName` at every empty cell of the
     *  selection currently within reach (bottom-up so each cell has support).
     *  The block must be in the hotbar. Returns filled / remaining / total so
     *  the agent knows to reposition and call again for out-of-reach cells. */
    public Map<String, Object> fillSelection(String blockName) {
        return fillCells(blockName, "//set", c -> true);
    }

    /** WorldEdit-like //walls — fill only the 4 VERTICAL walls of the selection
     *  (x==min/max or z==min/max), leaving floor, ceiling and interior open.
     *  A flat 1-high region becomes a perimeter ring. Good for fencing an area
     *  or a wall segment. Same reach + per-call cap semantics as fillSelection;
     *  the agent repositions for out-of-reach cells. (For a full 6-face shell,
     *  wall it then fillSelection the top/bottom layers.) */
    public Map<String, Object> wallsSelection(String blockName) {
        return fillCells(blockName, "//walls", c ->
                c[0] == _selMin[0] || c[0] == _selMax[0] ||
                c[2] == _selMin[2] || c[2] == _selMax[2]);
    }

    /** WorldEdit-like //hollow — the 6-face SHELL of the selection box (walls + floor +
     *  ceiling), interior left open. A cell is on the shell if it sits on any min/max face.
     *  Same reach + per-call cap + reposition semantics as fillSelection. */
    public Map<String, Object> hollowSelection(String blockName) {
        return fillCells(blockName, "//hollow", c ->
                c[0] == _selMin[0] || c[0] == _selMax[0] ||
                c[1] == _selMin[1] || c[1] == _selMax[1] ||
                c[2] == _selMin[2] || c[2] == _selMax[2]);
    }

    /** WorldEdit-like //cyl — the SOLID cylinder inscribed in the selection: every cell
     *  whose XZ position is inside the ellipse that fits the box (all Y layers). Radii come
     *  from the box half-extents, so a square selection gives a circle. */
    public Map<String, Object> cylSelection(String blockName) {
        return fillCells(blockName, "//cyl", c -> {
            double cx = (_selMin[0] + _selMax[0]) / 2.0, cz = (_selMin[2] + _selMax[2]) / 2.0;
            double rx = Math.max(0.5, (_selMax[0] - _selMin[0]) / 2.0 + 0.5);
            double rz = Math.max(0.5, (_selMax[2] - _selMin[2]) / 2.0 + 0.5);
            double dx = (c[0] - cx) / rx, dz = (c[2] - cz) / rz;
            return dx * dx + dz * dz <= 1.0;
        });
    }

    /** WorldEdit-like //sphere — the SOLID ellipsoid inscribed in the selection: every cell
     *  within the box's inscribed sphere/ellipsoid (radii = the 3 half-extents). */
    public Map<String, Object> sphereSelection(String blockName) {
        return fillCells(blockName, "//sphere", c -> {
            double cx = (_selMin[0] + _selMax[0]) / 2.0, cy = (_selMin[1] + _selMax[1]) / 2.0,
                   cz = (_selMin[2] + _selMax[2]) / 2.0;
            double rx = Math.max(0.5, (_selMax[0] - _selMin[0]) / 2.0 + 0.5);
            double ry = Math.max(0.5, (_selMax[1] - _selMin[1]) / 2.0 + 0.5);
            double rz = Math.max(0.5, (_selMax[2] - _selMin[2]) / 2.0 + 0.5);
            double dx = (c[0] - cx) / rx, dy = (c[1] - cy) / ry, dz = (c[2] - cz) / rz;
            return dx * dx + dy * dy + dz * dz <= 1.0;
        });
    }

    /** BREAK primitive: mine the given blocks (in reach) via the tungsten executor's break
     *  queue — the same drift-immune "mine without a physics leg" path @goto uses, so tool
     *  equip + gravity re-mine + BreakRules protection all apply. The bot must be within
     *  reach; out-of-reach blocks stay queued (reposition with gotoXYZ, poll mineStatus()).
     *  positions = list of [x,y,z]. Agent primitive for //replace, clearing, mineTo. */
    public Map<String, Object> mineBlocks(Object positionsObj) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (ex == null) { out.put("ok", false); out.put("reason", "no executor"); return out; }
            java.util.List<net.minecraft.util.math.BlockPos> queue = new java.util.ArrayList<>();
            // Take Object (py4j reflection wouldn't match a List<...> param) and coerce; py4j
            // also hands Python ints across as Java Long, so read them via Number.intValue().
            if (positionsObj instanceof java.util.List<?> positions) for (Object po : positions) {
                if (po instanceof java.util.List<?> p && p.size() >= 3) {
                    queue.add(new net.minecraft.util.math.BlockPos(
                            ((Number) p.get(0)).intValue(),
                            ((Number) p.get(1)).intValue(),
                            ((Number) p.get(2)).intValue()));
                }
            }
            if (queue.isEmpty()) { out.put("ok", false); out.put("reason", "no positions"); return out; }
            // Point the mining-resume at the bot itself so it doesn't wander to a stale goal.
            // getPos() (not getEntityPos()) — version-safe across 1.21.1..1.21.11 shared src.
            kaptainwutax.tungsten.TungstenMod.TARGET = mc.player.getPos();
            kaptainwutax.tungsten.TungstenMod.markGotoTarget();
            ex.setPath(new java.util.ArrayList<>());
            ex.startBreaking(queue);
            ex.stop = false;
            out.put("ok", true); out.put("queued", queue.size());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Poll the break queue: {mining, remaining}. mining=false && remaining=0 -> done. */
    public Map<String, Object> mineStatus() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            int rem = (ex != null && ex.breakQueue != null) ? ex.breakQueue.size() : 0;
            out.put("ok", true);
            out.put("mining", rem > 0);
            out.put("remaining", rem);
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Mine out the tungsten navigation SCAFFOLDING (pillar-up / bridge blocks it placed to
     *  reach a goal) — the "garbage" left around a build/route. Queues the recorded scaffold
     *  blocks TOP-DOWN into the break queue (so removing a support doesn't strand the bot) and
     *  clears the registry; poll mineStatus, reposition (gotoXYZ) for out-of-reach. Finite set,
     *  mined once each, no re-placing during cleanup -> cannot loop. Agent primitive; ;;cleanup. */
    public Map<String, Object> cleanupScaffold() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (ex == null) { out.put("ok", false); out.put("reason", "no executor"); return out; }
            java.util.List<net.minecraft.util.math.BlockPos> scaffold =
                    kaptainwutax.tungsten.util.ScaffoldRegistry.snapshotTopDown();
            if (scaffold.isEmpty()) { out.put("ok", true); out.put("queued", 0);
                out.put("reason", "no scaffold recorded"); return out; }
            kaptainwutax.tungsten.TungstenMod.TARGET = mc.player.getPos();
            kaptainwutax.tungsten.TungstenMod.markGotoTarget();
            ex.setPath(new java.util.ArrayList<>());
            ex.startBreaking(scaffold);
            ex.stop = false;
            kaptainwutax.tungsten.util.ScaffoldRegistry.clear();   // now owned by the break queue
            out.put("ok", true); out.put("queued", scaffold.size());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** How many scaffold blocks are currently recorded (not yet cleaned). */
    public int scaffoldCount() {
        return kaptainwutax.tungsten.util.ScaffoldRegistry.size();
    }

    // ── //replace — stateful break-then-place over the selection ──────────────
    private java.util.List<net.minecraft.util.math.BlockPos> _replaceCells = null;
    private String _replaceToName = null;

    private boolean blockNameMatches(net.minecraft.block.BlockState st, String name) {
        if (name == null || name.isEmpty() || name.equals("*") || name.equalsIgnoreCase("any"))
            return !st.isAir();   // "any"/"*" = replace any non-air block
        String want = name.contains(":") ? name : "minecraft:" + name;
        return net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).toString().equals(want);
    }

    /** //replace: swap every selection cell whose block is `fromName` (or "*"/"any" =
     *  any non-air) for `toName`. TWO phases via the executor: BREAK the matching cells
     *  (in reach; same drift-immune break queue as mineBlocks), then poll replaceStatus()
     *  which PLACES `toName` once the breaks drain. Real survival placement (not a server
     *  fill). Reposition (gotoXYZ) if remaining stalls out of reach. Agent primitive. */
    public Map<String, Object> replaceSelection(String fromName, String toName) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            if (_selMin == null) { out.put("ok", false); out.put("reason", "no selection — call select() first"); return out; }
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (ex == null) { out.put("ok", false); out.put("reason", "no executor"); return out; }
            java.util.List<net.minecraft.util.math.BlockPos> cells = new java.util.ArrayList<>();
            for (int y = _selMin[1]; y <= _selMax[1]; y++)
                for (int x = _selMin[0]; x <= _selMax[0]; x++)
                    for (int z = _selMin[2]; z <= _selMax[2]; z++) {
                        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(x, y, z);
                        if (blockNameMatches(client.world.getBlockState(p), fromName)) cells.add(p);
                    }
            if (cells.isEmpty()) { out.put("ok", true); out.put("matched", 0); out.put("phase", "done");
                out.put("reason", "no cells match " + fromName); return out; }
            _replaceCells = cells;
            _replaceToName = toName;
            // point mining-resume at the bot so it doesn't wander to a stale goal
            kaptainwutax.tungsten.TungstenMod.TARGET = client.player.getPos();
            kaptainwutax.tungsten.TungstenMod.markGotoTarget();
            ex.setPath(new java.util.ArrayList<>());
            ex.startBreaking(cells);
            out.put("ok", true); out.put("matched", cells.size()); out.put("phase", "breaking");
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Poll //replace: phase = breaking (breaks draining; reposition if remaining stalls) ->
     *  placing (fills toName bottom-up, in reach, capped per call) -> done. */
    public Map<String, Object> replaceStatus() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            int breaking = (ex != null && ex.breakQueue != null) ? ex.breakQueue.size() : 0;
            if (breaking > 0) { out.put("ok", true); out.put("phase", "breaking"); out.put("remaining", breaking); return out; }
            if (_replaceCells == null) { out.put("ok", true); out.put("phase", "idle"); return out; }

            // A CELL THAT IS STILL THE OLD BLOCK IS NOT FINISHED WITH — it is waiting to be
            // BROKEN. This loop used to skip anything non-replaceable as "already filled" and
            // then null the whole list, so every cell the break phase had not yet reached was
            // thrown away silently. Measured on the stand: //replace on a 3-cell column reported
            // matched=3, queued=1, placed=1 — one cell converted, two lost, and the caller was
            // told "placing" as though nothing had gone wrong.
            //
            // The break queue going empty does NOT mean every block broke; the executor drops
            // what it cannot reach. So each poll re-derives the state from the WORLD, which is
            // the only honest source, and keeps the list until every cell actually holds the
            // wanted block.
            _replaceCells.sort(java.util.Comparator.comparingInt(net.minecraft.util.math.BlockPos::getY));
            java.util.List<net.minecraft.util.math.BlockPos> toBreak = new java.util.ArrayList<>();
            java.util.List<net.minecraft.util.math.BlockPos> toPlace = new java.util.ArrayList<>();
            for (net.minecraft.util.math.BlockPos p : _replaceCells) {
                net.minecraft.block.BlockState st = client.world.getBlockState(p);
                if (blockNameMatches(st, _replaceToName)) continue;        // done: it is the new block
                if (st.isReplaceable()) toPlace.add(p); else toBreak.add(p);
            }
            if (toBreak.isEmpty() && toPlace.isEmpty()) {
                _replaceCells = null;
                _replaceToName = null;
                out.put("ok", true); out.put("phase", "done");
                return out;
            }
            if (!toBreak.isEmpty()) {
                // OUT OF REACH IS NOT A REASON TO SPIN. The executor abandons a dig the moment the
                // block is further than 4.5 away, and this poll used to just hand the same job
                // back, forever: measured as "Mining aborted: ticks=1 dist=5.12" on repeat, with
                // the bot four and a half blocks too far and nobody walking it in. The build queue
                // learned to walk (C5.10); the break side never did, so it does it here — same
                // question, same helper.
                net.minecraft.util.math.BlockPos first = toBreak.get(0);
                double reach = client.player.getEyePos()
                        .distanceTo(net.minecraft.util.math.Vec3d.ofCenter(first));
                if (reach > 4.0) {
                    net.minecraft.util.math.BlockPos stand =
                            kaptainwutax.tungsten.helpers.BlockPlaceHelper.workStand(client.world, first);
                    if (stand != null && !client.player.getBlockPos().equals(stand)) {
                        kaptainwutax.tungsten.task.FastNavigator.startExact(stand);
                        out.put("ok", true); out.put("phase", "walking");
                        out.put("remaining", toBreak.size());
                        out.put("walkingTo", stand.toShortString());
                        out.put("dist", String.format("%.2f", reach));
                        return out;
                    }
                }
                if (ex != null) {
                    ex.startBreaking(toBreak);
                }
                out.put("ok", true); out.put("phase", "breaking");
                out.put("remaining", toBreak.size());
                out.put("toPlace", toPlace.size());
                return out;
            }
            // Only placing left. Enqueue once — re-issuing every poll would clear the queue and
            // cancel a walk that is already under way.
            if (kaptainwutax.tungsten.helpers.BlockPlaceHelper.queued() == 0) {
                kaptainwutax.tungsten.helpers.BlockPlaceHelper.beginBatch(toPlace, _replaceToName);
            }
            out.put("ok", true); out.put("phase", "placing");
            out.put("queued", toPlace.size());
            out.put("poll", "buildQueue(), then replaceStatus() again to confirm");
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** SCHEMATIC placement core: place a batch of TYPED blocks — a list of
     *  [x, y, z, blockName] (absolute world coords) — bottom-up so each cell has support,
     *  in reach, capped per call. The cognitive agent parses a .schem/.litematic/.nbt into
     *  this block list (anchored at a chosen origin) and drives the build: buildBlocks ->
     *  reposition (gotoXYZ) for `remaining` (out of reach / no support yet) -> buildBlocks
     *  again. Real survival placement (placeBlockAtRaw, protection rules apply). Equips each
     *  named block from the hotbar (skips re-equip when the type is unchanged). The
     *  build-order PLANNING (which origin, layering, don't-wall-yourself-in) and material
     *  sourcing stay the agent's job — this is the executor primitive. */
    public Map<String, Object> buildBlocks(Object blocksObj) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            java.util.List<int[]> pos = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            if (blocksObj instanceof java.util.List<?> list) for (Object o : list) {
                if (o instanceof java.util.List<?> b && b.size() >= 4) {
                    pos.add(new int[]{((Number) b.get(0)).intValue(), ((Number) b.get(1)).intValue(),
                            ((Number) b.get(2)).intValue()});
                    names.add(String.valueOf(b.get(3)));
                }
            }
            if (pos.isEmpty()) { out.put("ok", false); out.put("reason", "no blocks (expect [[x,y,z,name],...])"); return out; }
            // place bottom-up so a cell's support (floor / already-placed block below) exists
            Integer[] order = new Integer[pos.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(pos.get(a)[1], pos.get(b)[1]));
            // Hand the batch to the tick-driven build queue, in bottom-up order, grouping runs of
            // the same block type so the queue re-equips only when the type actually changes.
            // (It used to place up to 64 of them inside this one client tick.)
            int queued = 0, already = 0;
            String runName = null;
            boolean first = true;   // the first run REPLACES the previous batch, the rest append
            java.util.List<net.minecraft.util.math.BlockPos> run = new java.util.ArrayList<>();
            for (int oi : order) {
                int[] p = pos.get(oi);
                String name = names.get(oi);
                net.minecraft.util.math.BlockPos bp = new net.minecraft.util.math.BlockPos(p[0], p[1], p[2]);
                if (!client.world.getBlockState(bp).isReplaceable()) { already++; continue; }
                if (runName != null && !runName.equals(name)) {
                    if (first) { kaptainwutax.tungsten.helpers.BlockPlaceHelper.beginBatch(run, runName); first = false; }
                    else kaptainwutax.tungsten.helpers.BlockPlaceHelper.enqueue(run, runName);
                    queued += run.size();
                    run = new java.util.ArrayList<>();
                }
                runName = name;
                run.add(bp);
            }
            if (!run.isEmpty()) {
                if (first) kaptainwutax.tungsten.helpers.BlockPlaceHelper.beginBatch(run, runName);
                else kaptainwutax.tungsten.helpers.BlockPlaceHelper.enqueue(run, runName);
                queued += run.size();
            }
            out.put("ok", true);
            out.put("queued", queued);
            out.put("already", already);         // already occupied (skipped)
            out.put("queueTotal", kaptainwutax.tungsten.helpers.BlockPlaceHelper.queued());
            out.put("poll", "buildQueue()");
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Test / agent entry for the `;;` WorldEdit command handler (the in-game chat path
     *  fires the same handler from SendChatEvent). Reads the player + crosshair block on
     *  the client thread, then runs the handler. E.g. we("pos1"), we("set stone"),
     *  we("replace stone cobblestone"), we("cyl glass"). */
    public Map<String, Object> we(String cmd) {
        int[][] pos = onClientThread(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
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
            return new int[][]{pb, cb};
        }, new int[][]{null, null});
        Map<String, Object> res = adris.altoclef.commands.worldedit.WorldEditCommands.handle(_mod, cmd, pos[0], pos[1]);
        Map<String, Object> out = new HashMap<>(res == null ? Map.of() : res);
        out.put("cmd", cmd);
        out.put("pb", pos[0] == null ? "null" : (pos[0][0] + "," + pos[0][1] + "," + pos[0][2]));
        return out;
    }

    // ── //copy + //paste clipboard ────────────────────────────────────────────
    private java.util.List<Object> _clipboard = null;   // [dx,dy,dz,name] relative to sel min

    /** //copy — snapshot the NON-AIR blocks of the selection into a clipboard, as offsets
     *  from the selection's min corner. Paste re-anchors them at the player. */
    public Map<String, Object> copySelection() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            if (_selMin == null) { out.put("ok", false); out.put("reason", "no selection"); return out; }
            java.util.List<Object> clip = new java.util.ArrayList<>();
            int ox = _selMin[0], oy = _selMin[1], oz = _selMin[2];
            for (int y = _selMin[1]; y <= _selMax[1]; y++)
                for (int x = _selMin[0]; x <= _selMax[0]; x++)
                    for (int z = _selMin[2]; z <= _selMax[2]; z++) {
                        net.minecraft.block.BlockState st = client.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
                        if (st.isAir()) continue;
                        clip.add(java.util.List.of(x - ox, y - oy, z - oz,
                                net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).toString()));
                    }
            _clipboard = clip;
            out.put("ok", true); out.put("copied", clip.size());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** //paste — place the clipboard at the player's block position (min corner anchor).
     *  Reuses buildBlocks (own onClientThread — no nesting since we are off it here). */
    public Map<String, Object> pasteClipboard() {
        if (_clipboard == null || _clipboard.isEmpty()) return Map.of("ok", false, "reason", "clipboard empty (//copy first)");
        int[] anchor = onClientThread(() -> {
            var p = MinecraftClient.getInstance().player;
            if (p == null) return null;
            net.minecraft.util.math.BlockPos b = p.getBlockPos();
            return new int[]{b.getX(), b.getY(), b.getZ()};
        }, null);
        if (anchor == null) return Map.of("ok", false, "reason", "not in game");
        java.util.List<Object> abs = new java.util.ArrayList<>();
        for (Object o : _clipboard) {
            java.util.List<?> c = (java.util.List<?>) o;
            abs.add(java.util.List.of(anchor[0] + ((Number) c.get(0)).intValue(),
                    anchor[1] + ((Number) c.get(1)).intValue(),
                    anchor[2] + ((Number) c.get(2)).intValue(), c.get(3)));
        }
        return buildBlocks(abs);
    }

    // ── //undo — snapshot the selection before a modifying op, restore on undo ──
    private final java.util.Deque<java.util.List<Object>> _undoStack = new java.util.ArrayDeque<>();
    private java.util.List<Object> _undoBuildPending = null;   // non-air blocks to rebuild after break
    private static final int UNDO_MAX_CELLS = 4096;
    private static final int UNDO_MAX_STACK = 10;

    /** Snapshot the selection's blocks (all cells, incl. air) so //undo can restore them.
     *  Auto-called before a modifying WE op. Skips huge selections (cap). */
    public Map<String, Object> undoSnapshot() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || _selMin == null) { out.put("ok", false); out.put("reason", "no selection"); return out; }
            long vol = (long) (_selMax[0] - _selMin[0] + 1) * (_selMax[1] - _selMin[1] + 1) * (_selMax[2] - _selMin[2] + 1);
            if (vol > UNDO_MAX_CELLS) { out.put("ok", false); out.put("reason", "selection too big for undo (" + vol + ")"); return out; }
            java.util.List<Object> snap = new java.util.ArrayList<>();
            for (int y = _selMin[1]; y <= _selMax[1]; y++)
                for (int x = _selMin[0]; x <= _selMax[0]; x++)
                    for (int z = _selMin[2]; z <= _selMax[2]; z++) {
                        net.minecraft.block.BlockState st = client.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
                        snap.add(java.util.List.of(x, y, z,
                                st.isAir() ? "air" : net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).toString()));
                    }
            _undoStack.push(snap);
            while (_undoStack.size() > UNDO_MAX_STACK) _undoStack.removeLast();
            out.put("ok", true); out.put("snapshot", snap.size()); out.put("depth", _undoStack.size());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** //undo — restore the last snapshot: break the whole region, then poll undoStatus which
     *  rebuilds the snapshot's non-air blocks. Reuses the break queue + buildBlocks. */
    public Map<String, Object> undoLast() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            if (_undoStack.isEmpty()) { out.put("ok", false); out.put("reason", "nothing to undo"); return out; }
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (ex == null) { out.put("ok", false); out.put("reason", "no executor"); return out; }
            java.util.List<Object> snap = _undoStack.pop();
            java.util.List<net.minecraft.util.math.BlockPos> breakAll = new java.util.ArrayList<>();
            java.util.List<Object> rebuild = new java.util.ArrayList<>();
            for (Object o : snap) {
                java.util.List<?> c = (java.util.List<?>) o;
                int x = ((Number) c.get(0)).intValue(), y = ((Number) c.get(1)).intValue(), z = ((Number) c.get(2)).intValue();
                breakAll.add(new net.minecraft.util.math.BlockPos(x, y, z));
                if (!"air".equals(c.get(3))) rebuild.add(o);
            }
            kaptainwutax.tungsten.TungstenMod.TARGET = client.player.getPos();
            kaptainwutax.tungsten.TungstenMod.markGotoTarget();
            ex.setPath(new java.util.ArrayList<>());
            ex.startBreaking(breakAll);
            ex.stop = false;
            _undoBuildPending = rebuild;
            out.put("ok", true); out.put("phase", "breaking"); out.put("rebuild", rebuild.size());
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Poll //undo: breaking (region clearing) -> placing (rebuild snapshot) -> done. */
    public Map<String, Object> undoStatus() {
        var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
        int breaking = (ex != null && ex.breakQueue != null) ? ex.breakQueue.size() : 0;
        if (breaking > 0) return Map.of("ok", true, "phase", "breaking", "remaining", breaking);
        if (_undoBuildPending == null) return Map.of("ok", true, "phase", "idle");
        java.util.List<Object> b = _undoBuildPending;
        _undoBuildPending = null;
        Map<String, Object> r = buildBlocks(b);   // rebuild the snapshot's non-air blocks
        Map<String, Object> out = new HashMap<>(r);
        out.put("phase", Boolean.TRUE.equals(r.get("complete")) ? "done" : "placing");
        if (!Boolean.TRUE.equals(r.get("complete"))) _undoBuildPending = b;   // reposition + poll again
        return out;
    }

    /** @@schem load — read a .schem / .schematic / .litematic from the game dir's `schematics`
     *  folder (via baritone's parsers), convert to blocks anchored at the player, and BUILD via
     *  buildBlocks. Download real ones from minecraft-schematics.com into <gamedir>/schematics/.
     *  Parse+convert on the client thread (registry-safe); build off it (no onClientThread nest). */
    public Map<String, Object> loadSchem(String name) {
        Map<String, Object> parsed = onClientThread(() -> {
            Map<String, Object> o = new HashMap<>();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) { o.put("ok", false); o.put("reason", "not in game"); return o; }
            java.io.File dir = new java.io.File(mc.runDirectory, "schematics");
            java.io.File f = new java.io.File(dir, name);
            if (!f.exists()) {
                for (String ext : new String[]{".schem", ".schematic", ".litematic"}) {
                    java.io.File cand = new java.io.File(dir, name + ext);
                    if (cand.exists()) { f = cand; break; }
                }
            }
            if (!f.exists()) { o.put("ok", false); o.put("reason", "not found in " + dir.getAbsolutePath()); return o; }
            String fn = f.getName().toLowerCase();
            baritone.utils.schematic.format.DefaultSchematicFormats fmt =
                    fn.endsWith(".litematic") ? baritone.utils.schematic.format.DefaultSchematicFormats.LITEMATICA :
                    fn.endsWith(".schematic") ? baritone.utils.schematic.format.DefaultSchematicFormats.MCEDIT :
                    baritone.utils.schematic.format.DefaultSchematicFormats.SPONGE;
            baritone.api.schematic.IStaticSchematic schem;
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                schem = fmt.parse(in);
            } catch (Exception e) { o.put("ok", false); o.put("reason", "parse: " + e.getMessage()); return o; }
            net.minecraft.util.math.BlockPos a = mc.player.getBlockPos();
            java.util.List<Object> blocks = new java.util.ArrayList<>();
            for (int y = 0; y < schem.heightY(); y++)
                for (int x = 0; x < schem.widthX(); x++)
                    for (int z = 0; z < schem.lengthZ(); z++) {
                        net.minecraft.block.BlockState st = schem.getDirect(x, y, z);
                        if (st == null || st.isAir()) continue;
                        blocks.add(java.util.List.of(a.getX() + x, a.getY() + y, a.getZ() + z,
                                net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).toString()));
                    }
            o.put("ok", true); o.put("blocks", blocks); o.put("file", f.getName());
            o.put("size", schem.widthX() + "x" + schem.heightY() + "x" + schem.lengthZ());
            return o;
        }, Map.of("ok", false, "reason", "client thread timeout"));
        if (!Boolean.TRUE.equals(parsed.get("ok"))) return parsed;
        @SuppressWarnings("unchecked")
        java.util.List<Object> blocks = (java.util.List<Object>) parsed.get("blocks");
        if (blocks.isEmpty()) return Map.of("ok", false, "reason", "no non-air blocks in schematic");
        Map<String, Object> r = buildBlocks(blocks);   // build off the client thread
        Map<String, Object> out = new HashMap<>(parsed);
        out.remove("blocks");
        out.put("blockCount", blocks.size());
        out.put("build", r);
        return out;
    }

    /** //size — the selection's dimensions + volume (or a no-selection note). */
    public Map<String, Object> selectionSize() {
        Map<String, Object> out = new HashMap<>();
        if (_selMin == null) { out.put("ok", false); out.put("reason", "no selection"); return out; }
        out.put("ok", true);
        out.put("min", _selMin[0] + "," + _selMin[1] + "," + _selMin[2]);
        out.put("max", _selMax[0] + "," + _selMax[1] + "," + _selMax[2]);
        int dx = _selMax[0] - _selMin[0] + 1, dy = _selMax[1] - _selMin[1] + 1, dz = _selMax[2] - _selMin[2] + 1;
        out.put("size", dx + "x" + dy + "x" + dz);
        out.put("volume", dx * dy * dz);
        out.put("clipboard", _clipboard == null ? 0 : _clipboard.size());
        return out;
    }

    /** Shared fill core for //set, //walls, //hollow, //cyl and //sphere. HANDS the matching
     *  selection cells to the tick-driven build queue, bottom-up so each cell has support (the
     *  floor, or a block this same queue placed below it) by the time it is reached.
     *
     *  <p>This used to place them itself, in a loop, inside one client tick — up to 96 blocks
     *  between two frames, which is the clip where six panes of glass appeared at once. A block
     *  costs four ticks to place (helpers/BlockPlaceHelper, ported from baritone), so "fill this
     *  region" is not a question anyone can answer inside a single call. It is work handed over:
     *  the queue aims and places one cell at a time, and the agent polls buildQueue().
     *
     *  Returns queued (cells accepted) / already (non-replaceable, nothing owed) / queueTotal. */
    private Map<String, Object> fillCells(String blockName, String op, java.util.function.Predicate<int[]> include) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            out.put("op", op);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            if (_selMin == null) { out.put("ok", false); out.put("reason", "no selection — call select() first"); return out; }
            java.util.List<net.minecraft.util.math.BlockPos> cells = new java.util.ArrayList<>();
            int already = 0;
            for (int y = _selMin[1]; y <= _selMax[1]; y++) {   // bottom-up
                for (int x = _selMin[0]; x <= _selMax[0]; x++) {
                    for (int z = _selMin[2]; z <= _selMax[2]; z++) {
                        if (!include.test(new int[]{x, y, z})) continue;
                        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(x, y, z);
                        if (!client.world.getBlockState(p).isReplaceable()) { already++; continue; }
                        cells.add(p);
                    }
                }
            }
            kaptainwutax.tungsten.helpers.BlockPlaceHelper.beginBatch(cells, blockName);
            out.put("ok", true);
            out.put("queued", cells.size());
            out.put("already", already);
            out.put("queueTotal", kaptainwutax.tungsten.helpers.BlockPlaceHelper.queued());
            out.put("poll", "buildQueue()");
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Poll the tick-driven build queue (//set, //walls, //hollow, //cyl, //sphere, buildBlocks,
     *  //replace). Placement runs at the human rate — one block per 4 ticks, baritone's
     *  rightClickSpeed — so a big region takes as long as it takes; this is how the agent watches
     *  it. `deferred` are cells the queue could not reach or had nothing to place against: walk
     *  closer (gotoXYZ) and re-issue for those. `done` = nothing left queued. */
    public Map<String, Object> buildQueue() {
        Map<String, Object> out = new HashMap<>();
        var deferred = kaptainwutax.tungsten.helpers.BlockPlaceHelper.deferred();
        out.put("ok", true);
        out.put("queued", kaptainwutax.tungsten.helpers.BlockPlaceHelper.queued());
        out.put("placed", kaptainwutax.tungsten.helpers.BlockPlaceHelper.placedFromQueue());
        out.put("already", kaptainwutax.tungsten.helpers.BlockPlaceHelper.alreadyFilled());
        out.put("deferredCount", deferred.size());
        // WHY they were deferred: noFace = nothing placeable was visible from where the bot
        // stood (walk closer), timeout = a face existed but the aim never got there (an aim bug).
        out.put("deferNoFace", kaptainwutax.tungsten.helpers.BlockPlaceHelper.deferNoFace);
        out.put("deferTimeout", kaptainwutax.tungsten.helpers.BlockPlaceHelper.deferTimeout);
        out.put("deferProtected", kaptainwutax.tungsten.helpers.BlockPlaceHelper.deferProtected);
        out.put("walkStarted", kaptainwutax.tungsten.helpers.BlockPlaceHelper.walkStarted);
        out.put("walkDebug", kaptainwutax.tungsten.helpers.BlockPlaceHelper.walkDebug);
        // The two reasons that are NOT "stand somewhere else": the block cannot survive in that
        // cell, and the named block is not in the hotbar at all.
        out.put("deferNoSupport", kaptainwutax.tungsten.helpers.BlockPlaceHelper.deferNoSupport);
        out.put("deferNoMaterial", kaptainwutax.tungsten.helpers.BlockPlaceHelper.deferNoMaterial);
        out.put("blockedByOwnBody", kaptainwutax.tungsten.helpers.BlockPlaceHelper.blockedByOwnBody);
        out.put("walkToBuild", kaptainwutax.tungsten.helpers.BlockPlaceHelper.walkToBuild());
        java.util.List<String> d = new java.util.ArrayList<>();
        for (var p : deferred) d.add(p.getX() + "," + p.getY() + "," + p.getZ());
        out.put("deferred", d);
        out.put("done", kaptainwutax.tungsten.helpers.BlockPlaceHelper.queued() == 0);
        return out;
    }

    /** Who moves the bot while it builds. ON (default): the queue walks itself to a position
     *  each cell is placeable from — the port of baritone's BuilderProcess placement goal, which
     *  is what lets it reach the top of a column or the far side of a wall. OFF: the queue places
     *  only what is visible from where it stands and hands the rest back through
     *  buildQueue().deferred, for an agent that wants to own movement itself. */
    public Map<String, Object> setBuilderWalks(boolean on) {
        kaptainwutax.tungsten.helpers.BlockPlaceHelper.setWalkToBuild(on);
        return Map.of("ok", true, "walkToBuild", on);
    }

    /** Executor + task state in one string — for telling "this run is broken" apart from
     *  "the previous run left something behind". */
    public String execState() {
        var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
        var pf = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
        return (ex == null ? "no-exec" : ex.debugState())
                + " pfStop=" + (pf == null ? "?" : pf.stop.get())
                + " pfActive=" + (pf == null ? "?" : pf.active.get())
                + " pillar=" + kaptainwutax.tungsten.task.PillarTask.isActive()
                + " nav=" + kaptainwutax.tungsten.task.FastNavigator.isActive()
                + " buildQ=" + kaptainwutax.tungsten.helpers.BlockPlaceHelper.queued()
                + " | planCalls=" + kaptainwutax.tungsten.task.FollowEntityTask.planCalls
                + " usable=" + kaptainwutax.tungsten.task.FollowEntityTask.planUsable
                + " tooShort=" + kaptainwutax.tungsten.task.FollowEntityTask.planTooShort
                + " physFallback=" + kaptainwutax.tungsten.task.FollowEntityTask.physicsFallbacks
                // Does the follow task RUN at all? tick() returns immediately when inactive, so a
                // climbing tickInactive means the chase is switched off, not stuck.
                + " | followCalled=" + kaptainwutax.tungsten.task.FollowEntityTask.tickCalled
                + " active=" + kaptainwutax.tungsten.task.FollowEntityTask.tickActive
                + " inactive=" + kaptainwutax.tungsten.task.FollowEntityTask.tickInactive
                // The live-steer branch suppresses BOTH planners, resets the replan clock and
                // returns — so if steerTicks is what climbs during a stall, the chase is not
                // stuck for want of a plan, it is steering into something.
                + " steer=" + kaptainwutax.tungsten.task.FollowEntityTask.steerTicks
                + " los0=" + kaptainwutax.tungsten.task.FollowEntityTask.losBlocked
                + " cooldown=" + kaptainwutax.tungsten.task.FollowEntityTask.cooldownTicks
                + " | routeCells=" + kaptainwutax.tungsten.task.FollowEntityTask.routeCells
                + " traversable=" + kaptainwutax.tungsten.task.FollowEntityTask.traversableCells
                + " routes=" + kaptainwutax.tungsten.task.FollowEntityTask.routeSamples
                + " | edges trav=" + kaptainwutax.tungsten.path.movements.MovementQueue.edgeTraverse
                + " asc=" + kaptainwutax.tungsten.path.movements.MovementQueue.edgeAscend
                + " desc=" + kaptainwutax.tungsten.path.movements.MovementQueue.edgeDescend
                + " diag=" + kaptainwutax.tungsten.path.movements.MovementQueue.edgeDiagonal
                + " other=" + kaptainwutax.tungsten.path.movements.MovementQueue.edgeOther;
    }

    /** Abandon whatever the build queue still owes (//set gone wrong, wrong selection). */
    public Map<String, Object> buildQueueClear() {
        kaptainwutax.tungsten.helpers.BlockPlaceHelper.clearQueue();
        return Map.of("ok", true, "queued", 0);
    }

    /** Compact battle game-state for a cognitive agent (TODO 6.1) — one call
     *  that lets the agent "see" the fight without pixel screenshots: self
     *  (hp/pos/held/onGround/blocks), and nearby players (name/pos/distance/
     *  health-visible/hostile-facing). The agent uses this to pick tactics
     *  (attack/retreat/bridge/buy) and drives the mod primitives. Read-only. */
    public Map<String, Object> getGameState() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            var me = client.player;
            if (me == null || client.world == null) { out.put("inGame", false); return out; }
            out.put("inGame", true);

            Map<String, Object> self = new HashMap<>();
            self.put("hp", me.getHealth());
            self.put("maxHp", me.getMaxHealth());
            self.put("armor", me.getArmor());
            self.put("pos", String.format("%.1f,%.1f,%.1f", me.getX(), me.getY(), me.getZ()));
            self.put("onGround", me.isOnGround());
            // WHICH WORLD AM I IN? There was no way to ask over py4j at all, which a playthrough
            // needs constantly -- overworld, nether, end are three different sets of rules, and an
            // agent that cannot tell them apart cannot plan across a portal. Found while writing the
            // first End course: the course had to infer the dimension from the bot's Y coordinate,
            // which is exactly the kind of guess a primitive is supposed to remove.
            self.put("dimension", adris.altoclef.util.helpers.WorldHelper.getCurrentDimension().toString());
            self.put("held", me.getMainHandStack().isEmpty() ? "empty"
                    : net.minecraft.registry.Registries.ITEM.getId(me.getMainHandStack().getItem()).toString());
            int blocks = 0;
            for (int i = 0; i < 36; i++) {
                var st = me.getInventory().getStack(i);
                if (st.getItem() instanceof net.minecraft.item.BlockItem) blocks += st.getCount();
            }
            self.put("blocks", blocks);
            out.put("self", self);

            List<Map<String, Object>> players = new ArrayList<>();
            for (net.minecraft.entity.player.PlayerEntity p : client.world.getPlayers()) {
                if (p == me) continue;
                double dx = me.getX() - p.getX(), dy = me.getY() - p.getY(), dz = me.getZ() - p.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                Map<String, Object> pm = new HashMap<>();
                pm.put("name", p.getName().getString());
                pm.put("pos", String.format("%.1f,%.1f,%.1f", p.getX(), p.getY(), p.getZ()));
                pm.put("distance", String.format("%.1f", dist));
                pm.put("hp", p.getHealth());          // visible for tracked players
                pm.put("sprinting", p.isSprinting());
                players.add(pm);
            }
            players.sort((a, b) -> Double.compare(
                    Double.parseDouble((String) a.get("distance")),
                    Double.parseDouble((String) b.get("distance"))));
            out.put("players", players);
            out.put("playerCount", players.size());

            // bed scan (bedwars perception) — nearest bed blocks in a radius, so
            // the agent knows where to attack (enemy bed) / defend (own bed)
            List<Map<String, Object>> beds = new ArrayList<>();
            net.minecraft.util.math.BlockPos base = me.getBlockPos();
            int R = 40;
            for (int dx = -R; dx <= R && beds.size() < 8; dx += 1) {
                for (int dz = -R; dz <= R && beds.size() < 8; dz += 1) {
                    for (int dy = -8; dy <= 8; dy++) {
                        net.minecraft.util.math.BlockPos bp = base.add(dx, dy, dz);
                        if (client.world.getBlockState(bp).getBlock() instanceof net.minecraft.block.BedBlock) {
                            // count once per bed: skip the FOOT if its HEAD neighbour
                            // is also a bed (already will be / was reported)
                            try {
                                var st = client.world.getBlockState(bp);
                                if (st.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.FOOT) {
                                    var facing = st.get(net.minecraft.block.HorizontalFacingBlock.FACING);
                                    if (client.world.getBlockState(bp.offset(facing)).getBlock()
                                            instanceof net.minecraft.block.BedBlock) break;
                                }
                            } catch (Exception ignored) {}
                            Map<String, Object> bm = new HashMap<>();
                            bm.put("pos", bp.getX() + "," + bp.getY() + "," + bp.getZ());
                            bm.put("distance", String.format("%.1f", Math.sqrt(dx * dx + dy * dy + dz * dz)));
                            beds.add(bm);
                            break;
                        }
                    }
                }
            }
            beds.sort((a, b) -> Double.compare(
                    Double.parseDouble((String) a.get("distance")),
                    Double.parseDouble((String) b.get("distance"))));
            out.put("beds", beds);
            return out;
        }, Map.of("inGame", false, "error", "client thread timeout"));
    }

    /** Bed / point defense (TODO 6.4): wall up the target cell by placing
     *  blocks on its exposed sides + top (a protective shell), covering every
     *  cell currently in reach. Returns which cells were placed and which
     *  remain (out of reach — reposition and call again, or the cognitive
     *  agent / tungsten pathfinder walks around to finish the shell). Reuses
     *  placeBlockAt so the placing logic stays single-source. */
    public Map<String, Object> buildDefenseAround(int x, int y, int z) {
        Map<String, Object> out = new HashMap<>();
        // shell = 4 horizontal neighbours + top, at the bed cell and one above
        // (beds are ~1 tall; covering y and y+1 sides blocks approach + top)
        java.util.List<int[]> shell = new java.util.ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {
            shell.add(new int[]{x + 1, y + dy, z});
            shell.add(new int[]{x - 1, y + dy, z});
            shell.add(new int[]{x, y + dy, z + 1});
            shell.add(new int[]{x, y + dy, z - 1});
        }
        shell.add(new int[]{x, y + 2, z}); // roof
        java.util.List<String> placed = new java.util.ArrayList<>();
        java.util.List<String> remaining = new java.util.ArrayList<>();
        for (int[] c : shell) {
            Map<String, Object> r = placeBlockAt(c[0], c[1], c[2]);
            String tag = c[0] + "," + c[1] + "," + c[2];
            if (Boolean.TRUE.equals(r.get("ok")) && Boolean.TRUE.equals(r.get("placed"))) {
                placed.add(tag);
            } else if ("target not replaceable (already occupied)".equals(r.get("reason"))) {
                placed.add(tag); // already solid — counts as covered
            } else {
                remaining.add(tag);
            }
        }
        out.put("ok", true);
        out.put("placed", placed);
        out.put("remaining", remaining);
        out.put("complete", remaining.isEmpty());
        return out;
    }

    /** Inventory capacity + material accounting (TODO 7.3). free = empty main
     *  slots (0-35); blockCount = total placeable blocks; per-item counts of
     *  block stacks so a builder can plan without over-promising. */
    public Map<String, Object> inventorySpace() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            var player = MinecraftClient.getInstance().player;
            if (player == null) { out.put("ok", false); return out; }
            var inv = player.getInventory();
            int free = 0, blockTotal = 0;
            Map<String, Integer> blocks = new HashMap<>();
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getStack(i);
                if (st.isEmpty()) { free++; continue; }
                if (st.getItem() instanceof net.minecraft.item.BlockItem) {
                    blockTotal += st.getCount();
                    String id = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString();
                    blocks.merge(id, st.getCount(), Integer::sum);
                }
            }
            out.put("ok", true);
            out.put("freeSlots", free);
            out.put("totalSlots", 36);
            out.put("blockCount", blockTotal);
            out.put("blocks", blocks);
            return out;
        }, Map.of("ok", false, "error", "client thread timeout"));
    }

    /** Is the in-game voice chat (Plasmo/SVC) connected on this server? */
    public boolean isVoiceChatConnected() {
        return AltoclefVoicechat.VOICE_CONNECTED;
    }

    /** What is under my crosshair right now (block or entity). */
    public Map<String, Object> getCrosshairTarget() {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            var hit = MinecraftClient.getInstance().crosshairTarget;
            if (hit == null) { out.put("type", "none"); return out; }
            out.put("type", hit.getType().toString());
            if (hit instanceof net.minecraft.util.hit.BlockHitResult bhr) {
                out.put("block", MinecraftClient.getInstance().world
                        .getBlockState(bhr.getBlockPos()).getBlock().getName().getString());
                out.put("pos", bhr.getBlockPos().toShortString());
            } else if (hit instanceof net.minecraft.util.hit.EntityHitResult ehr) {
                out.put("entity", ehr.getEntity().getName().getString());
            }
            return out;
        }, Map.of("type", "error"));
    }
}
