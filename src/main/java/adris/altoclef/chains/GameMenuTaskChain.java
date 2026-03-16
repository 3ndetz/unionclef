package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.butler.ButlerConfig;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.multiplayer.RejoinEvent;
import adris.altoclef.mixins.DeathScreenAccessor;
import adris.altoclef.multiversion.ConnectScreenVer;
import adris.altoclef.tasks.fix.StuckFixingTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.multiplayer.LobbyTask;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.util.agent.Pipeline;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.apache.commons.lang3.ArrayUtils;

public class GameMenuTaskChain extends SingleTaskChain {

    private final TimerReal _reconnectTimer = new TimerReal(10);
    private ServerInfo _prevServerEntry = null;
    public ServerInfo _connectOverrideServerEntry = null;
    private boolean _reconnecting = false;
    private Class _prevScreen = null;
    public static boolean _needUnStuckFix = false;
    public static boolean _needDisconnect = false;
    public static boolean _needToStopTasksOnReconnect = false;
    public static boolean _reJoinAfterDisconnect = false;
    public static boolean NeedtoStopTasksOnDeath = false;
    public static String ServerIp = "";
    private final TimerReal _commandDelayTimer = new TimerReal(2);
    public static int reconnectAttemps = -1;

    public GameMenuTaskChain(TaskRunner runner) {
        super(runner);
    }

    private boolean shouldAutoReconnect() {
        AltoClef mod = AltoClef.getInstance();
        return mod != null && mod.getModSettings().isAutoReconnect();
    }

    @Override
    protected void onStop() {
    }

    @Override
    public void onInterrupt(TaskChain other) {
    }

    @Override
    protected void onTick() {
    }

    public String getServerIp() {
        return ServerIp;
    }

    public static boolean isMinigamePipeline(Pipeline pipeline) {
        return pipeline.equals(Pipeline.SkyWars)
                || pipeline.equals(Pipeline.MurderMystery)
                || pipeline.equals(Pipeline.BedWars);
    }

    private boolean _clicked = false;
    private boolean _joined = false;
    public TimerReal clickTimer = new TimerReal(0.7);
    public TimerReal _reloadInfoSenderTimer = new TimerReal(10);
    private boolean _infoSenderLoaded = true;

    // Timers for different click operations
    private final TimerReal _slotClickTimer = new TimerReal(0.5);
    private final TimerReal _worldJoinTimer = new TimerReal(3);
    private final TimerReal _minigameButtonTimer = new TimerReal(1.0);
    private final TimerReal _lobbyButtonTimer = new TimerReal(1.0);
    private final TimerReal _mouseClickTimer = new TimerReal(0.3);

    public boolean rejoinEventPublished = false;

    @Override
    public float getPriority() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null) return Float.NEGATIVE_INFINITY;

        if (!AltoClef.inGame()) {
            rejoinEventPublished = false;
            _worldJoinTimer.reset();
        } else {
            if (_worldJoinTimer.elapsed() && !rejoinEventPublished) {
                EventBus.publish(new RejoinEvent());
                rejoinEventPublished = true;
            }
        }

        // Python sender auto-reload
        if (mod.getModSettings().shouldReloadInfoSender() && _reloadInfoSenderTimer.elapsed()) {
            if (!mod.getInfoSender().IsCallbackServerStarted()) {
                if (_infoSenderLoaded) {
                    Debug.logMessage("[InfoSender] Python callback connection lost. Retry...");
                    _infoSenderLoaded = false;
                }
                mod.reloadPythonSender();
            } else {
                if (!_infoSenderLoaded) {
                    Debug.logMessage("[InfoSender] Python callback connection established!");
                    _infoSenderLoaded = true;
                }
            }
            _reloadInfoSenderTimer.reset();
        }

        if (ButlerConfig.getInstance().autoJoin) {
            if (ContainerType.screenHandlerMatches(ContainerType.CHEST)) {
                Text title = MinecraftClient.getInstance().currentScreen != null
                        ? MinecraftClient.getInstance().currentScreen.getTitle() : null;

                if (title != null && title.getString() != null) {
                    String t = title.getString().toLowerCase();

                    if (t.contains("выбор сервера") || t.contains("мини-игры")) {
                        String[] MinigamesTitles = new String[]{"мини-игры", "МИНИ-ИГРЫ", "МИНИИГРЫ"};

                        String[] ClickTitles;
                        switch (AltoClef.getPipeline()) {
                            case SkyWars:
                                ClickTitles = new String[]{"SkyWars", "skywars", "скайварс", "скай-варс"};
                                break;
                            case BedWars:
                                ClickTitles = new String[]{"BedWars", "bedwars", "бедварс"};
                                break;
                            case MurderMystery:
                                ClickTitles = new String[]{"MurderMystery", "murdermystery", "МардерМистери", "Murder"};
                                break;
                            default:
                                ClickTitles = null;
                                break;
                        }
                        if (ClickTitles != null && _worldJoinTimer.elapsed()) {
                            Slot slot = ItemHelper.getCustomItemSlot(mod, ArrayUtils.addAll(MinigamesTitles));

                            if (slot != null && _slotClickTimer.elapsed()) {
                                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                                _slotClickTimer.reset();
                                return 90;
                            } else {
                                slot = ItemHelper.getCustomItemSlot(mod, ArrayUtils.addAll(MinigamesTitles, ClickTitles));
                                if (slot != null && _slotClickTimer.elapsed()) {
                                    mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                                    _slotClickTimer.reset();
                                    _clicked = true;
                                    return 90;
                                }
                            }
                        }
                    }
                }
            }

            boolean isMinigame = isMinigamePipeline(AltoClef.getPipeline());
            if (isMinigame) {
                if (clickTimer.elapsed() && _worldJoinTimer.elapsed()) {
                    if (_lobbyButtonTimer.elapsed() && ItemHelper.clickCustomItem(mod, "Выбор сервера", "Выбор лобби")) {
                        clickTimer.reset();
                        _lobbyButtonTimer.reset();
                    }
                    if (_minigameButtonTimer.elapsed() && ItemHelper.clickCustomItem(mod, "новая игра", "начать игру", "быстро играть (пкм)")) {
                        clickTimer.reset();
                        _minigameButtonTimer.reset();
                    }
                }
            }
        }

        if (_clicked) {
            _joined = true;
            _clicked = false;
        }
        setTask(null);
        return Float.NEGATIVE_INFINITY;
    }

    public void onTickPost(AltoClef mod) {
        Screen screen = MinecraftClient.getInstance().currentScreen;

        if (AltoClef.inGame()) {
            reconnectAttemps = 0;
            _prevServerEntry = MinecraftClient.getInstance().getCurrentServerEntry();
            if (_prevServerEntry != null) {
                ServerIp = _prevServerEntry.address.toString();
            }
            if (mod.getPlayer().hasStatusEffect(StatusEffects.INVISIBILITY)) {
                if (mod.getPlayer().getStatusEffect(StatusEffects.INVISIBILITY).getAmplifier() >= 3) {
                    if (NeedtoStopTasksOnDeath) {
                        mod.getMessageSender().enqueueChat("/hub", MessagePriority.TIMELY);
                        NeedtoStopTasksOnDeath = false;
                        _commandDelayTimer.reset();
                        if (_commandDelayTimer.elapsed()) {
                            _commandDelayTimer.reset();
                            Debug.logMessage("ВАЛИМ! 111");
                        }
                    }
                }
            }
        }

        if (screen instanceof DisconnectedScreen disconnectedScreen) {
            Text reason = disconnectedScreen.getTitle();
            String kickMessage = reason != null ? reason.getString() : "UNKNOWN REASON";
            if (kickMessage == null) kickMessage = "UNKNOWN REASON";

            Debug.logMessage("Kicked from server with reason: " + kickMessage);

            if (shouldAutoReconnect()) {
                Debug.logMessage("RECONNECTING: Going to Multiplayer Screen");
                _reconnecting = true;
                _reconnectTimer.reset();
                MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
            } else {
                mod.cancelUserTask();
            }
        } else if (_needUnStuckFix) {
            if (AltoClef.inGame()) {
                ServerInfo srv = MinecraftClient.getInstance().getCurrentServerEntry();
                if (srv != null) {
                    Debug.logMessage("Starting UNSTUCK FIX >> server=" + srv.address.toString());
                    _prevServerEntry = srv;
                }

                Debug.logMessage("OPEN GAME MENU");
                MinecraftClient client = MinecraftClient.getInstance();
                client.setScreen(new GameMenuScreen(true));
                disconnect(client);
                SelectWorldScreen worldScreen = new SelectWorldScreen(new TitleScreen());
                MinecraftClient.getInstance().setScreen(worldScreen);
                Debug.logMessage("worldScreen.isMouseOver() " + worldScreen.isMouseOver(0, 0));

                double x = worldScreen.width / 2.0 - 154;
                double y = worldScreen.height - 52;

                if (_mouseClickTimer.elapsed()) {
                    worldScreen.mouseClicked(x, y, 0);
                    worldScreen.mouseReleased(x, y, 0);

                    if (worldScreen.hoveredElement(x, y).isPresent()) {
                        Element hoveredElement = worldScreen.hoveredElement(x, y).get();
                        hoveredElement.mouseClicked(0, 0, 0);
                        hoveredElement.mouseReleased(0, 0, 0);
                        mod.cancelUserTask();
                        ServerInfo finalSrv = srv;
                        Runnable doOnStuckFixFinish = new Thread(() -> {
                            MinecraftClient clientt = MinecraftClient.getInstance();
                            clientt.setScreen(new GameMenuScreen(true));
                            Debug.logMessage("[STUCKFIX] DISCONNECT STAGE 2");
                            disconnect(clientt);
                            mod.cancelUserTask();
                            mod.runUserTask(new GetToXZTask(0, 0));
                            Debug.logMessage("[STUCKFIX] SET MP SCREEN");
                            MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
                            Debug.logMessage("[STUCKFIX] RECONNECT TO SERVER");
                            if (_prevServerEntry == null) {
                                _prevServerEntry = finalSrv;
                            }
                            _reconnecting = true;
                            _reconnectTimer.reset();
                        });
                        mod.runUserTask(new StuckFixingTask(), doOnStuckFixFinish);
                    }
                    _mouseClickTimer.reset();
                }
                _needUnStuckFix = false;
            }
        } else if (_needDisconnect) {
            if (AltoClef.inGame()) {
                ServerInfo srv;
                if (_connectOverrideServerEntry != null) {
                    srv = _connectOverrideServerEntry;
                    _connectOverrideServerEntry = null;
                } else {
                    srv = MinecraftClient.getInstance().getCurrentServerEntry();
                }

                if (srv != null) {
                    Debug.logMessage("Starting DISCONNECT CHAIN >> server=" + srv.address.toString());
                    _prevServerEntry = srv;
                }

                if (_prevServerEntry != null && _reJoinAfterDisconnect) {
                    _reconnecting = true;
                    _reconnectTimer.reset();
                    _reJoinAfterDisconnect = false;
                }

                Debug.logMessage("[DISCONNECT CHAIN] OPEN GAME MENU");
                MinecraftClient client = MinecraftClient.getInstance();
                client.setScreen(new GameMenuScreen(true));
                screen = client.currentScreen;
                client.getAbuseReportContext().tryShowDraftScreen(client, screen, _innerDisconnect(client), true);
                MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
                _needDisconnect = false;
            }
        } else if (screen instanceof MultiplayerScreen) {
            if (_reconnecting && _reconnectTimer.elapsed()) {
                reconnectAttemps += 1;
                Debug.logMessage("RECONNECTING: Going to reconnect");
                _reconnecting = false;

                if (_prevServerEntry == null) {
                    Debug.logWarning("Failed to re-connect to server, no server entry cached.");
                } else {
                    Debug.logMessage("RECONNECTING: Connect to " + _prevServerEntry.address.toString());
                    MinecraftClient client = MinecraftClient.getInstance();
                    ConnectScreenVer.connect(screen, client, ServerAddress.parse(_prevServerEntry.address), _prevServerEntry, false);
                    if (_needToStopTasksOnReconnect) {
                        mod.cancelUserTask();
                        _needToStopTasksOnReconnect = false;
                    }
                }
            }
        }

        if (screen != null) {
            _prevScreen = screen.getClass();
        }
    }

    public Runnable _innerDisconnect(MinecraftClient client) {
        return () -> {
            if (client.world != null) {
                boolean bl = client.isInSingleplayer();
                client.world.disconnect();
                if (bl) {
                    client.disconnect(new DisconnectedScreen(client.currentScreen, Text.of("menu.savingLevel"), Text.of("DEATH")));
                } else {
                    client.disconnect();
                }
            }
        };
    }

    public void disconnect(MinecraftClient client) {
        if (AltoClef.inGame() && client.world != null) {
            Debug.logMessage("DISCONNECT");
            boolean bl = client.isInSingleplayer();
            client.world.disconnect();
            if (bl) {
                client.disconnect(new DisconnectedScreen(client.currentScreen, Text.of("menu.savingLevel"), Text.of("DEATH")));
            } else {
                client.disconnect();
            }
        } else {
            Debug.logMessage("Tried to disconnect >>> world is null or not in game");
        }
    }

    public void connectToServer(String ip) {
        _connectOverrideServerEntry = new ServerInfo("CustomServer", ip, ServerInfo.ServerType.OTHER);
        if (AltoClef.inGame()) {
            _reJoinAfterDisconnect = true;
            _needDisconnect = true;
        } else {
            _prevServerEntry = _connectOverrideServerEntry;
            Screen screen = MinecraftClient.getInstance().currentScreen;
            Debug.logMessage("RECONNECT CHAIN: Not in game; Connecting to server from menu: " + ip);
            if (!(screen instanceof MultiplayerScreen)) {
                Debug.logMessage("RECONNECT CHAIN: Setting to MultiplayerScreen " + ip);
                MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
            }
            _needDisconnect = false;
            _reconnecting = true;
            _reconnectTimer.reset();
        }
    }

    public static void StuckFixActivate() {
        _needUnStuckFix = true;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
    }

    @Override
    public String getName() {
        return "Game Menu Chain";
    }
}
