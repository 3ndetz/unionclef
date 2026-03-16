package adris.altoclef.butler;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.GameMenuTaskChain;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.multiplayer.LobbyTask;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The butler system lets authorized players send commands to the bot to execute.
 * Authorization defined in "altoclef_butler_whitelist.txt"/"altoclef_butler_blacklist.txt"
 * and "useButlerWhitelist"/"useButlerBlacklist" in "altoclef_settings.json".
 */
public class Butler {

    private static boolean STUCK_FIX_BUTLER_ALLOW = true;

    private static final String BUTLER_MESSAGE_START = "` ";

    private final AltoClef _mod;

    private final WhisperChecker _whisperChecker = new WhisperChecker();

    private final UserAuth _userAuth;

    private String _currentUser = null;

    private boolean _commandInstantRan = false;
    private boolean _commandFinished = false;

    private final TimerGame _lobbyMovingTimer = new TimerGame(10);
    private final TimerReal _captchaTimer = new TimerReal(4);
    public String CaptchaSolvingMode = "SOLVE_MAXIMUM"; // GET_DATASET | SOLVE_DATASET_ONLY

    private final HashSet<String> _teammates = new HashSet<>();

    public Butler(AltoClef mod) {
        _mod = mod;
        _userAuth = new UserAuth(mod);

        // Revoke current user when a task finishes
        EventBus.subscribe(TaskFinishedEvent.class, evt -> {
            if (_currentUser != null) _currentUser = null;
        });
    }

    public static boolean IsStuckFixAllow() {
        return STUCK_FIX_BUTLER_ALLOW && ButlerConfig.getInstance().autoStuckFix;
    }

    /** Called from AltoClef's ALLOW_CHAT / ALLOW_GAME event handlers for every incoming server message. */
    public void onReceiveChat(String msg) {
        if (_mod.getPlayer() == null) return;
        if (AltoClef.inGame()) {
            _mod.getInfoSender().onChatMessage(msg);
        }
        receiveMessage(msg, _mod.getPlayer().getName().getString());
    }

    public boolean recieveWhisperCommand(String ourName, String msg) {
        WhisperChecker.MessageResult whisper = _whisperChecker.receiveMessage(_mod, ourName, msg);
        if (whisper != null && whisper.from != null && whisper.message != null)
            return receiveWhisper(whisper.from, whisper.message);
        return false;
    }

    private void receiveMessage(String msg, String receiver) {
        String ourName = receiver;
        if (_mod.getPlayer() != null) {
            ourName = _mod.getPlayer().getName().getString();
        }

        // --- Server address / mode detection ---
        String serverAddress = "universal";
        if (MinecraftClient.getInstance().getCurrentServerEntry() != null
                && MinecraftClient.getInstance().getCurrentServerEntry().address != null) {
            serverAddress = MinecraftClient.getInstance().getCurrentServerEntry().address.toString();
            _mod.getInfoSender().UpdateServerInfo("server", serverAddress);
        }

        String serverMode = _mod.getInfoSender().getInfo("serverMode", "survival");
        List<String> availableServerModes = new ArrayList<>();
        for (String[] format : ButlerConfig.getInstance().chatFormats) {
            if (serverAddress.equals(format[0])) availableServerModes.add(format[2]);
        }
        for (String mode : availableServerModes) {
            if (msg.toLowerCase().contains(mode.toLowerCase())) {
                serverMode = mode;
                _mod.getInfoSender().UpdateServerInfo("serverMode", serverMode);
            }
        }

        // --- Chat type detection (Ⓛ local / Ⓖ global / лобби) ---
        if (msg.contains("Ⓛ")) {
            _mod.getInfoSender().UpdateServerInfo("chatType", "local");
        } else if (msg.contains("Ⓖ")) {
            _mod.getInfoSender().UpdateServerInfo("chatType", "global");
        } else if (msg.toLowerCase().contains("лобби")) {
            _mod.getInfoSender().UpdateServerInfo("chatType", "lobby");
        }

        // --- Auto-join logic ---
        if (ButlerConfig.getInstance().autoJoin) {
            if ((msg.contains("] Вы находитесь в Лобби. Выберите сервер и пройдите в портал!")
                    || msg.contains("Вы успешно вошли!")) && serverAddress.contains("musteryworld")) {
                _mod.getCommandExecutor().execute("@stop");
                Debug.logMessage("Мы в лобби!");
                _lobbyMovingTimer.reset();
                _mod.runUserTask(new LobbyTask());
            } else if (msg.contains("Введите капчу с картинки в чат")) {
                captchaActionsPerform();
            } else if (msg.contains("/login")
                    || (msg.contains("/l") && (msg.contains("пароль") || msg.contains("pass")))) {
                _mod.getMessageSender().enqueueChat(
                        "/login " + ButlerConfig.getInstance().multiplayer_password, MessagePriority.TIMELY);
            } else if (msg.contains("/reg")) {
                _mod.getMessageSender().enqueueChat(
                        "/register " + ButlerConfig.getInstance().multiplayer_password, MessagePriority.TIMELY);
            } else if (msg.contains("[SkyWars] Добро пожаловать!") || msg.contains("[SkyWars] Вы покинули игру")) {
                Debug.logMessage("Мы в хабе!");
                _mod.getCommandExecutor().execute("@stop");
                _lobbyMovingTimer.reset();
                _mod.getCommandExecutor().execute("@goto -65 -69");
            } else if (msg.contains("[SkyWars] " + ourName + " присоединился к")) {
                Debug.logMessage("Мы в колбе!");
                _mod.getCommandExecutor().execute("@stop");
            } else if (msg.contains(ourName + " выиграл игру!")) {
                Debug.logMessage("ПОБЕДА!!! УРАА!!");
                _mod.getCommandExecutor().execute("@stop");
                _lobbyMovingTimer.reset();
                _mod.getCommandExecutor().execute("@goto -65 -69");
            } else if (msg.contains("1ый Убийца -")) {
                Debug.logMessage("Игра остановлена");
                _mod.getCommandExecutor().execute("@stop");
                _lobbyMovingTimer.reset();
                _mod.getCommandExecutor().execute("@goto -65 -69");
            } else if (msg.contains("[SkyWars] " + ourName + " погиб")
                    || msg.contains("[SkyWars] " + ourName + " был убит")
                    || msg.contains("[SkyWars] " + ourName + " победил в битве")) {
                _mod.getCommandExecutor().execute("@stop");
                _mod.getInfoSender().onDeath(_mod.getDamageTracker()._lastAttackingPlayerName);
                _lobbyMovingTimer.reset();
                _mod.runUserTask(new LobbyTask()); // LobbyMoveTask not ported → fallback to LobbyTask
            } else if (msg.contains("[SkyWars] Игра начинается через 1")
                    || ((serverAddress.equals("funnymc.ru") || serverAddress.equals("mlegacy.net"))
                    && msg.contains("Игра начинается через 1 секунду"))) {
                Debug.logMessage("Начался батл SW!");
                _mod.getCommandExecutor().execute("@stop");
                ClearTeammates();
                AddNearestPlayerToFriends(_mod, 5);
                _mod.getCommandExecutor().execute("@test sw");
            }
        }

        // --- Chat parsing → py4j ---
        boolean strongChatMessage = false;
        WhisperChecker.MessageResult chatParsedResult =
                _whisperChecker.receiveChat(_mod, ourName, msg, serverAddress, serverMode);
        if (chatParsedResult != null) {
            if (ButlerConfig.getInstance().debugChatParseResult) {
                Debug.logInternal("Chatparsedresult>>" + chatParsedResult + "<<\nexact:"
                        + chatParsedResult.serverExactPrediction);
            }
            if (chatParsedResult.serverExactPrediction != null) {
                String pred = chatParsedResult.serverExactPrediction;
                if (ButlerConfig.getInstance().debugChatParseResult) {
                    Debug.logMessage("serverExactPrediction=" + pred + ",server=" + chatParsedResult.server);
                }
                String nick = chatParsedResult.from;
                if (nick != null && !nick.isBlank()
                        && (pred.equals("exact") || pred.equals("server") || pred.equals("universal"))) {
                    if (!nick.contains("MurderMystery") && !nick.equals("Ошибка")
                            && !nick.matches(".*[^a-zA-Z0-9_].*")) {
                        _mod.getInfoSender().onStrongChatMessage(chatParsedResult);
                        strongChatMessage = true;
                    }
                }
            }
        }
        if (!strongChatMessage) {
            _mod.getInfoSender().onWeakChatMessage(msg);
        }

        // --- Whisper command dispatch ---
        WhisperChecker.MessageResult whisper = _whisperChecker.receiveMessage(_mod, ourName, msg);
        if (whisper != null && whisper.from != null && whisper.message != null) {
            receiveWhisper(whisper.from, whisper.message);
        }
    }

    private boolean receiveWhisper(String username, String message) {
        boolean debug = ButlerConfig.getInstance().whisperFormatDebug;
        if (message.startsWith(BUTLER_MESSAGE_START)) {
            if (debug) Debug.logMessage("Rejecting: MSG is detected to be sent from another bot.");
            return false;
        }
        if (_userAuth.isUserAuthorized(username)) {
            if (message.startsWith(_mod.getModSettings().getCommandPrefix())) {
                executeWhisper(username, message);
                return true;
            } else {
                if (debug) Debug.logMessage("User \"" + username + "\" sent simple private message.");
            }
        } else {
            if (debug) Debug.logMessage("Rejecting: User \"" + username + "\" is not authorized.");
            if (ButlerConfig.getInstance().sendAuthorizationResponse) {
                sendWhisper(username,
                        ButlerConfig.getInstance().failedAuthorizationResposne.replace("{from}", username),
                        MessagePriority.UNAUTHORIZED);
            }
        }
        return false;
    }

    // --- Captcha (MapItemHelper not yet ported — random fallback only) ---
    private void captchaActionsPerform() {
        if (_captchaTimer.elapsed()) {
            _captchaTimer.reset();
            if (CaptchaSolvingMode.contains("SOLVE")) {
                Debug.logMessage("КАПЧА РЕШЕНИЕ (рандом, MapItemHelper не портирован)");
                String captchaSolving = Integer.toString(ThreadLocalRandom.current().nextInt(1000, 100000));
                _mod.getMessageSender().enqueueChat(captchaSolving, MessagePriority.TIMELY);
            }
        } else {
            Debug.logMessage("КАПЧА УЖЕ РЕШАЕТСЯ!");
        }
    }

    public void reJoin(long delay_before, AltoClef mod) {
        mod.cancelUserTask();
        Debug.logMessage("[SCHEDULER] WAITING START");
        mod.runUserTask(new GetToXZTask(-1000, 1000));
        GameMenuTaskChain._needDisconnect = true;
        GameMenuTaskChain._reJoinAfterDisconnect = true;
        GameMenuTaskChain._needToStopTasksOnReconnect = true;
    }

    // --- Teammates ---
    public void AddNearestPlayerToFriends(AltoClef mod, double radius) {
        List<PlayerEntity> players = mod.getEntityTracker().getTrackedEntities(PlayerEntity.class);
        try {
            for (Entity entity : players) {
                if (entity instanceof PlayerEntity
                        && mod.getPlayer().getPos().isInRange(entity.getPos(), radius)
                        && !entity.equals(mod.getPlayer())) {
                    String name = entity.getName().getString();
                    if (!isUserAuthorized(name)) {
                        boolean added = AddUserToWhitelist(name);
                        if (added) {
                            _teammates.add(name);
                            Debug.logMessage("[КЕНТЫ] +игрок " + name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Debug.logWarning("Ошибка системы поиска тиммейтов: проигнорирована.");
        }
    }

    public void ClearTeammates() {
        for (String name : _teammates) {
            boolean removed = RemoveUserFromWhitelist(name);
            if (removed) Debug.logMessage("[КЕНТЫ] -игрок " + name);
        }
        _teammates.clear();
    }

    // --- Auth ---
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isUserAuthorized(String username) {
        return _userAuth.isUserAuthorized(username);
    }

    public boolean AddUserToWhitelist(String username) {
        return _userAuth.addUserToWhitelist(username);
    }

    public boolean RemoveUserFromWhitelist(String username) {
        return _userAuth.removeUserFromWhitelist(username);
    }

    // --- Log forwarding ---
    public void onLog(String message, MessagePriority priority) {
        if (_currentUser != null) sendWhisper(message, priority);
    }

    public void onLogWarning(String message, MessagePriority priority) {
        if (_currentUser != null) sendWhisper("[WARNING:] " + message, priority);
    }

    public void tick() {}

    public String getCurrentUser() { return _currentUser; }

    public boolean hasCurrentUser() { return _currentUser != null; }

    private void executeWhisper(String username, String message) {
        String prevUser = _currentUser;
        _commandInstantRan = true;
        _commandFinished = false;
        _currentUser = username;
        if (ButlerConfig.getInstance().sendCommandOutput)
            sendWhisper("Command Executing: " + message, MessagePriority.TIMELY);
        String prefix = ButlerConfig.getInstance().requirePrefixMsg ? _mod.getModSettings().getCommandPrefix() : "";
        AltoClef.getCommandExecutor().execute(prefix + message, () -> {
            if (ButlerConfig.getInstance().sendCommandOutput)
                sendWhisper("Command Finished: " + message, MessagePriority.TIMELY);
            if (!_commandInstantRan) _currentUser = null;
            _commandFinished = true;
        }, e -> {
            for (String msg : e.getMessage().split("\n"))
                sendWhisper("TASK FAILED: " + msg, MessagePriority.ASAP);
            e.printStackTrace();
            _currentUser = null;
            _commandInstantRan = false;
        });
        _commandInstantRan = false;
        if (_commandFinished) _currentUser = prevUser;
    }

    private void sendWhisper(String message, MessagePriority priority) {
        if (_currentUser != null) {
            sendWhisper(_currentUser, message, priority);
        } else {
            Debug.logWarning("Failed to send butler message, no current user: " + message);
        }
    }

    private void sendWhisper(String username, String message, MessagePriority priority) {
        _mod.getMessageSender().enqueueWhisper(username, BUTLER_MESSAGE_START + message, priority);
    }
}
