package adris.altoclef.butler;

import adris.altoclef.Debug;
import adris.altoclef.util.helpers.ConfigHelper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ButlerConfig {

    private static ButlerConfig _instance = new ButlerConfig();

    static {
        ConfigHelper.loadConfig("configs/butler.json", ButlerConfig::new, ButlerConfig.class, newConfig -> _instance = newConfig);
    }

    /**
     * If true, bot will send command output as chat messages.
     */
    public boolean sendCommandOutput = false;
    /**
     * If true, will use blacklist for rejecting users from using your player as a butler
     */
    public boolean useButlerBlacklist = false;
    /**
     * If true, will use whitelist to only accept users from said whitelist.
     */
    public boolean useButlerWhitelist = true;
    /**
     * If true, automatically tries to fix stuck movement.
     */
    public boolean autoStuckFix = false;
    /**
     * If true, prints debug info about chat parsing results.
     */
    public boolean debugChatParseResult = false;
    /**
     * If true, bot will automatically navigate minigame menus (lobby selection, game start buttons).
     */
    public boolean autoJoin = true;
    /**
     * If true, automatically logs in with multiplayer_password on server join.
     * Ignored if multiplayer_password is empty.
     */
    public boolean autoLogin = true;
    /**
     * Password for multiplayer command authentication.
     */
    public String multiplayer_password = "";
    /**
     * Servers have different messaging plugins that change the way messages are displayed.
     * Rather than attempt to implement all of them and introduce a big security risk,
     * you may define custom whisper formats that the butler will watch out for.
     * <p>
     * Within curly brackets are three special parts:
     * <p>
     * {from}: Who the message was sent from
     * {to}: Who the message was sent to, butler will ignore if this is not your username.
     * {message}: The message.
     * <p>
     * WARNING: The butler will only accept non-chat messages as commands, but don't make this too lenient,
     * else you may risk unauthorized control to the bot. Basically, make sure that only whispers can
     * create the following messages.
     */
    public String[] whisperFormats = new String[]{
            "{from} whispers to you: {message}",
            "{from} шепчет вам: {message}",
            "{from} шепчет: {message}",
            "{from} whispers: {message}",
            "[{from} -> я] {message}",
            "[{from} -> Я] {message}",
            "[{from} -> me] {message}",
            "[{from} -> Me] {message}",
            "[{from} -> You] {message}",
            "[{from} -> you] {message}",
            "[{from} -> {to}] {message}"
    };
    /**
     * If set to true, will print information about whispers that are parsed and those
     * that have failed parsing.
     * <p>
     * Enable this if you need help setting up the whisper format.
     */
    public boolean whisperFormatDebug = false;
    /**
     * Determines if failure messages should be sent to a non-authorized entity attempting to use butler
     * <p>
     * DEFAULT false: do NOT auto-reply rude "не пиши сюда" to players who PM the bot — the
     * private message still reaches the agent's LLM (onWeakChatMessage), so it answers IN
     * CHARACTER instead of the butler bluntly rejecting. (operator: фикс навсегда)
     */
    public boolean sendAuthorizationResponse = false;
    /**
     * The response sent in a failed execution due to non-authorization
     * {from}: the username of the player who triggered the failed authorization response
     */
    public String failedAuthorizationResponse = "{from}, не пиши сюда, пожалуйста";
    /**
     * Use this to choose if the prefix should be required in messages
     * <p>
     * Disable this if you want to be able to send normal messages and not butler commands.
     */
    public boolean requirePrefixMsg = false;

    /**
     * Server-specific chat format patterns.
     * Each entry: { "server", "format_pattern", "game_mode" }
     * <p>
     * TODOS.md C7.6: these used to be a literal array baked into this class -- every fresh
     * install shipped six named community servers' domains and internal chat-plugin formats
     * straight in the compiled jar. Now they live in the bundled resource template
     * {@code butler_default_chat_formats.json} (a config template, same spirit as
     * {@code configs/butler.json} itself), loaded once below. Behavior for existing and new
     * installs is unchanged -- same defaults, same {@code configs/butler.json} override path --
     * only the storage location moved out of source.
     */
    public String[][] chatFormats = loadDefaultChatFormats();

    private static String[][] loadDefaultChatFormats() {
        try (InputStream in = ButlerConfig.class.getClassLoader().getResourceAsStream("butler_default_chat_formats.json")) {
            if (in == null) {
                Debug.logError("butler_default_chat_formats.json missing from resources, falling back to 'universal' only.");
                return new String[][]{{"universal", "<{from}> {message}", "survival"}};
            }
            return new ObjectMapper().readValue(in, String[][].class);
        } catch (IOException e) {
            Debug.logError("Failed to load butler_default_chat_formats.json, falling back to 'universal' only: " + e.getMessage());
            return new String[][]{{"universal", "<{from}> {message}", "survival"}};
        }
    }

    public static ButlerConfig getInstance() {
        return _instance;
    }
}
