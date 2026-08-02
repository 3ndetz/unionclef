package adris.altoclef;

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
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;

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

    public boolean hasBaritoneGoal() {
        if (AltoClef.inGame()) {
            Optional<IPath> pathq;
            if (_mod.getClientBaritone().getCustomGoalProcess().isActive())
                pathq = _mod.getClientBaritone().getPathingBehavior().getPath();
            else
                pathq = Optional.empty();

            if (pathq.isPresent()) {
                List<BetterBlockPos> pathlist = pathq.get().positions();
                return !pathlist.isEmpty();
            }
        }
        return false;
    }

    public Vec3d getCurrentGoal() {
        Vec3d result = null;
        if (AltoClef.inGame()) {
            // Try baritone path endpoint first
            Optional<IPath> pathq = _mod.getClientBaritone().getPathingBehavior().getPath();
            if (pathq.isPresent()) {
                List<BetterBlockPos> pathlist = pathq.get().positions();
                if (!pathlist.isEmpty()) {
                    BetterBlockPos goalpos = pathlist.get(pathlist.size() - 1);
                    result = new Vec3d(goalpos.getX(), goalpos.getY(), goalpos.getZ());
                }
            }
            // Fallback to tungsten target
            if (result == null && isTungstenActive()) {
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
                                    _mod.getPlayer().getEyePos(), p, 1.0);
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

    private static baritone.api.utils.input.Input inputByName(String name) {
        switch (name.toLowerCase()) {
            case "forward": return baritone.api.utils.input.Input.MOVE_FORWARD;
            case "back": return baritone.api.utils.input.Input.MOVE_BACK;
            case "left": return baritone.api.utils.input.Input.MOVE_LEFT;
            case "right": return baritone.api.utils.input.Input.MOVE_RIGHT;
            case "jump": return baritone.api.utils.input.Input.JUMP;
            case "sneak": return baritone.api.utils.input.Input.SNEAK;
            case "sprint": return baritone.api.utils.input.Input.SPRINT;
            case "attack": return baritone.api.utils.input.Input.CLICK_LEFT;
            case "use": return baritone.api.utils.input.Input.CLICK_RIGHT;
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
                case "left" -> _mod.getInputControls().tryPress(baritone.api.utils.input.Input.CLICK_LEFT);
                case "right" -> _mod.getInputControls().tryPress(baritone.api.utils.input.Input.CLICK_RIGHT);
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
    public String punkStats() {
        var P = kaptainwutax.tungsten.task.PunkPlayerTask.class;
        return String.format("called=%d inactive=%d noTarget=%d voidHold=%d combat=%d approachRestart=%d",
                kaptainwutax.tungsten.task.PunkPlayerTask.pCalled,
                kaptainwutax.tungsten.task.PunkPlayerTask.pInactive,
                kaptainwutax.tungsten.task.PunkPlayerTask.pNoTarget,
                kaptainwutax.tungsten.task.PunkPlayerTask.pVoidHold,
                kaptainwutax.tungsten.task.PunkPlayerTask.pCombat,
                kaptainwutax.tungsten.task.PunkPlayerTask.pApproach);
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
    public int dirBlockedFwd() { return kaptainwutax.tungsten.combat.CombatController.dirBlockedFwd; }

    /** Which trigger gate refuses the swing, counted rather than sampled. */
    public String gateStats() {
        var T = kaptainwutax.tungsten.combat.TriggerBot.class;
        return String.format("total=%d click=%d cd=%d reach=%d angle=%d los=%d passed=%d",
                kaptainwutax.tungsten.combat.TriggerBot.gTotal,
                kaptainwutax.tungsten.combat.TriggerBot.gClick,
                kaptainwutax.tungsten.combat.TriggerBot.gCooldown,
                kaptainwutax.tungsten.combat.TriggerBot.gReach,
                kaptainwutax.tungsten.combat.TriggerBot.gAngle,
                kaptainwutax.tungsten.combat.TriggerBot.gLos,
                kaptainwutax.tungsten.combat.TriggerBot.gPassed);
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
        if (value != null && !adris.altoclef.util.helpers.SettingsReflectionHelper
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
        kaptainwutax.tungsten.path.movements.MovementQueue.qLost = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qStatusFail = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qRefused = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qShort = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.qVetoed = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeTraverse = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeAscend = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeDescend = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeDiagonal = 0;
        kaptainwutax.tungsten.path.movements.MovementQueue.edgeOther = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeRequested = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeOnCooldown = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeNoHit = 0;
        kaptainwutax.tungsten.path.movements.Movement.placeClicked = 0;
        kaptainwutax.tungsten.path.movements.Movement.motionSteered = 0;
        kaptainwutax.tungsten.path.movements.Movement.sprintTicks = 0;
        kaptainwutax.tungsten.path.movements.Movement.moveTicks = 0;
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
    public String placeStats() {
        // Two engines, both reported. The first four are the SPLIT path (walker moves the body,
        // PathExecutor.tickPlacing aims and clicks) whose seam measured clicked=0 across eleven
        // thousand in-range ticks. The `mq`/`mv` numbers are the ported baritone MovementQueue that
        // replaces it for bridge legs: mq* is the chain (legs started, steps completed, handbacks,
        // timeouts, ticks owned), mv* is the one and only promotion to a click — a movement asked for
        // CLICK_RIGHT because its live crosshair agreed, and interactBlock said SUCCESS.
        return String.format(
                "called=%d deferred=%d inRange=%d clicked=%d"
                        + " | mqStarted=%d mqSteps=%d mqBack=%d mqTimeout=%d mqTicks=%d step=%d/%d"
                        + " mqLost=%d mqStatusFail=%d mqRefused=%d(short=%d vetoed=%d)"
                        + " sprint=%d/%d"
                        + " | mvRequested=%d mvCooldown=%d mvNoHit=%d mvClicked=%d mvSteered=%d"
                        + " | gateThrough=%d gateHeld=%d queued=%d queuePlaced=%d",
                kaptainwutax.tungsten.path.PathExecutor.placeCalled,
                kaptainwutax.tungsten.path.PathExecutor.placeDeferred,
                kaptainwutax.tungsten.path.PathExecutor.placeInRange,
                kaptainwutax.tungsten.path.PathExecutor.placeClicked,
                kaptainwutax.tungsten.path.movements.MovementQueue.qStarted,
                kaptainwutax.tungsten.path.movements.MovementQueue.qSteps,
                kaptainwutax.tungsten.path.movements.MovementQueue.qUnreachable,
                kaptainwutax.tungsten.path.movements.MovementQueue.qTimeout,
                kaptainwutax.tungsten.path.movements.MovementQueue.qTicks,
                kaptainwutax.tungsten.path.movements.MovementQueue.getIndex(),
                kaptainwutax.tungsten.path.movements.MovementQueue.size(),
                kaptainwutax.tungsten.path.movements.MovementQueue.qLost,
                kaptainwutax.tungsten.path.movements.MovementQueue.qStatusFail,
                kaptainwutax.tungsten.path.movements.MovementQueue.qRefused,
                kaptainwutax.tungsten.path.movements.MovementQueue.qShort,
                kaptainwutax.tungsten.path.movements.MovementQueue.qVetoed,
                kaptainwutax.tungsten.path.movements.Movement.sprintTicks,
                kaptainwutax.tungsten.path.movements.Movement.moveTicks,
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
                kaptainwutax.tungsten.helpers.BlockPlaceHelper.placedFromQueue());
    }

    public int critHits() { return kaptainwutax.tungsten.combat.TriggerBot.lifetimeCrits; }
    public int totalHits() { return kaptainwutax.tungsten.combat.TriggerBot.lifetimeHits; }

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
