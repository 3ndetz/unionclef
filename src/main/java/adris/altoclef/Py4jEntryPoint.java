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
    Executor _executor;
    public static String last_talking_player = "";

    public Py4jEntryPoint(AltoClef mod) {
        _mod = mod;
        resetValues();
        _executor = Util.getMainWorkerExecutor();
    }

    public void onVoiceFeed(String playerName, byte[] audio) {
        // Voice chat stub - Phase 4
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onVoiceFeed(playerName, audio);
            }
        });
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

        if (task instanceof AbstractKillEntityTask || hasBaritoneGoal())
            return true;

        return !(task instanceof IdleTask || task instanceof GestureTask
                || task instanceof WaitForDragonAndPearlTask
                || (task != null && (task.toString() != null && !task.toString().isBlank() &&
                        task.toString().toLowerCase().contains("wait"))));
    }

    public byte[] getScreenshot() {
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
        if (AltoClef.inGame() && _mod.getPlayer() != null && _mod.getPlayer().getHandItems() != null) {
            for (ItemStack item : _mod.getPlayer().getHandItems()) {
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
        executeInNetworkThread(() -> {
            if (IsCallbackServerStarted()) {
                _cb.onChatMessage(msg);
            }
        });
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
            Optional<IPath> pathq = _mod.getClientBaritone().getPathingBehavior().getPath();
            BetterBlockPos goalpos = null;

            if (pathq.isPresent()) {
                List<BetterBlockPos> pathlist = pathq.get().positions();
                if (pathlist.size() > 0) {
                    goalpos = pathlist.get(pathlist.size() - 1);
                    result = new Vec3d(goalpos.getX(), goalpos.getY(), goalpos.getZ());
                }
            }
        }
        return result;
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
}
