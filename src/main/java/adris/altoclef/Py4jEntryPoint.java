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
    private static final int CHAT_LOG_MAX = 300;
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
                net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(faceCenter, side, support, false);
                var res = client.interactionManager.interactBlock(client.player, net.minecraft.util.Hand.MAIN_HAND, hit);
                client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                out.put("ok", true);
                out.put("result", res.toString());
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

    /** Shared fill core for //set and //walls. Places `blockName` at every
     *  replaceable selection cell matching `include`, bottom-up (so each cell
     *  has support: the floor or an already-placed block below), capped per
     *  call so a big region never stalls a tick. Equips the named block from
     *  the hotbar if present (else placeBlockAtRaw auto-picks any block item).
     *  Returns filled / remaining (out of reach — reposition + call again) /
     *  already (non-replaceable) / truncated (hit cap) / complete. */
    private Map<String, Object> fillCells(String blockName, String op, java.util.function.Predicate<int[]> include) {
        return onClientThread(() -> {
            Map<String, Object> out = new HashMap<>();
            out.put("op", op);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { out.put("ok", false); out.put("reason", "not in game"); return out; }
            if (_selMin == null) { out.put("ok", false); out.put("reason", "no selection — call select() first"); return out; }
            equipHotbarBlock(client, blockName);   // honest lever: hold the named block
            // Cap placements per call so a big region never stalls the render
            // thread inside one tick — the agent just calls again (remaining>0).
            final int MAX_PLACEMENTS = 96;
            int filled = 0, remaining = 0, already = 0;
            boolean truncated = false;
            for (int y = _selMin[1]; y <= _selMax[1] && !truncated; y++) {   // bottom-up
                for (int x = _selMin[0]; x <= _selMax[0] && !truncated; x++) {
                    for (int z = _selMin[2]; z <= _selMax[2]; z++) {
                        if (!include.test(new int[]{x, y, z})) continue;
                        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(x, y, z);
                        if (!client.world.getBlockState(p).isReplaceable()) { already++; continue; }
                        // placeBlockAtRaw (not placeBlockAt) — we are already on
                        // the client thread; nesting onClientThread would deadlock.
                        Map<String, Object> r = placeBlockAtRaw(x, y, z);
                        if (Boolean.TRUE.equals(r.get("placed"))) filled++;
                        else remaining++;
                        if (filled >= MAX_PLACEMENTS) { truncated = true; break; }
                    }
                }
            }
            out.put("ok", true);
            out.put("filled", filled);
            out.put("remaining", remaining);   // out of reach — reposition + call again
            out.put("already", already);
            out.put("truncated", truncated);   // hit per-call cap — call again to continue
            out.put("complete", remaining == 0 && !truncated);
            return out;
        }, Map.of("ok", false, "reason", "client thread timeout"));
    }

    /** Select the first hotbar slot (0-8) holding `blockName` (item-id match,
     *  with or without the "minecraft:" namespace). No-op if blank or absent —
     *  the caller then keeps whatever block is held. Must run on client thread. */
    private void equipHotbarBlock(MinecraftClient client, String blockName) {
        if (blockName == null || blockName.isEmpty()) return;
        String want = blockName.contains(":") ? blockName : "minecraft:" + blockName;
        for (int i = 0; i < 9; i++) {
            ItemStack st = client.player.getInventory().getStack(i);
            if (st.isEmpty()) continue;
            if (net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString().equals(want)) {
                adris.altoclef.multiversion.entity.PlayerVer.setSelectedSlot(client.player.getInventory(), i);
                return;
            }
        }
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
