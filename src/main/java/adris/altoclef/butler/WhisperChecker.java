package adris.altoclef.butler;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.time.TimerGame;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhisperChecker {

    private static final TimerGame _repeatTimer = new TimerGame(0.1);
    private static final TimerGame _whisperRepeatTimer = new TimerGame(0.1);

    private static String _lastMessage = null;
    private static String _lastWhisperMessage = null;

    public boolean isVanillaWhisperMessage(String message) {
        return message.matches("^\\[\\w+\\] whispers to you:.*") ||
                message.matches("^\\[\\w+\\] шепчет вам:.*");
    }

    public static String escapeRegexChars(String text) {
        List<Character> regexKillingChars = new ArrayList<>(
                Arrays.asList('[', ']', '.', '^', '?', '*', '$', '(', ')', '/', '|', '+'));
        for (Character killer : regexKillingChars) {
            String charr = killer.toString();
            text = text.replace(charr, "\\" + charr);
        }
        return text;
    }

    public static MessageResult tryParse(String ourUsername, String whisperFormatInp, String message) {
        List<String> parts = new ArrayList<>(Arrays.asList("{from}", "{to}", "{message}"));
        String whisperFormat = escapeRegexChars(whisperFormatInp);
        parts.sort(Comparator.comparingInt(whisperFormat::indexOf));
        parts.removeIf(part -> !whisperFormat.contains(part));

        String regexFormat = Pattern.quote(whisperFormat);
        for (String part : parts) {
            regexFormat = regexFormat.replace(part, "(.+)");
        }
        if (regexFormat.startsWith("\\Q")) {
            regexFormat = regexFormat.substring("\\Q".length());
        }
        if (regexFormat.endsWith("\\E")) {
            regexFormat = regexFormat.substring(0, regexFormat.length() - "\\E".length());
        }
        Pattern p = Pattern.compile(regexFormat);
        Matcher m = p.matcher(message);
        Map<String, String> values = new HashMap<>();
        if (m.matches()) {
            for (int i = 0; i < m.groupCount(); ++i) {
                if (i >= parts.size()) {
                    Debug.logError("Invalid whisper format parsing: " + whisperFormat + " for message: " + message);
                    break;
                }
                values.put(parts.get(i), m.group(i + 1));
            }
        }

        if (values.containsKey("{to}")) {
            String toUser = values.get("{to}");
            if (!toUser.equals(ourUsername)) {
                Debug.logInternal("Rejected message since it is sent to " + toUser + " and not " + ourUsername);
                return null;
            }
        }
        if (values.containsKey("{from}") && values.containsKey("{message}")) {
            MessageResult result = new MessageResult();
            result.from = values.get("{from}");
            result.message = values.get("{message}");
            return result;
        }
        return null;
    }

    public static MessageResult chatParse(String ourUsername, String[] chatFormatMas, String message) {
        return chatParse(ourUsername, chatFormatMas, message, "exact");
    }

    public static MessageResult chatParse(String ourUsername, String[] chatFormatMas, String message,
                                          String ExactState) {
        List<String> parts = new ArrayList<>(
                Arrays.asList("{team}", "{global}", "{starterPrefix}", "{donate}", "{suffix}", "{clan}", "{rank}",
                        "{from}", "{to}", "{message}"));
        String serverName = chatFormatMas[0];
        String serverMode = chatFormatMas[2];
        String chatFormatNew = new String(chatFormatMas[1]);
        message = message.replace("\\", "");
        // Normalize arrows
        List<String> arrows = new ArrayList<>(Arrays.asList("➥", "->", "➡", "➥", "➯", "➨", "›", "►", "⋙", "»", "⪼", "⇨"));
        for (String arrow : arrows) {
            if (!chatFormatNew.contains(arrow)) {
                message = message.replace(arrow, ">");
            }
        }
        chatFormatNew = escapeRegexChars(chatFormatNew);
        String chatFormat = chatFormatNew;

        parts.sort(Comparator.comparingInt(chatFormat::indexOf));
        parts.removeIf(part -> !chatFormat.contains(part));

        String regexFormat = Pattern.quote(chatFormat);
        for (String part : parts) {
            regexFormat = regexFormat.replace(part, "(.+)");
        }
        if (regexFormat.startsWith("\\Q")) {
            regexFormat = regexFormat.substring("\\Q".length());
        }
        if (regexFormat.endsWith("\\E")) {
            regexFormat = regexFormat.substring(0, regexFormat.length() - "\\E".length());
        }
        Pattern p = Pattern.compile(regexFormat);
        Matcher m = p.matcher(message);
        Map<String, String> values = new HashMap<>();
        if (m.matches()) {
            for (int i = 0; i < m.groupCount(); ++i) {
                if (i >= parts.size()) {
                    Debug.logError("Invalid whisper format parsing: " + chatFormat + " for message: " + message);
                    break;
                }
                values.put(parts.get(i), m.group(i + 1));
            }
        }

        if (values.containsKey("{to}")) {
            String toUser = values.get("{to}");
            if (!toUser.equals(ourUsername)) {
                Debug.logInternal("Rejected message since it is sent to " + toUser + " and not " + ourUsername);
                return null;
            }
        }

        List<Character> nickKillingChars = new ArrayList<>(
                Arrays.asList('~', '[', ']', '.', '^', '?', '*', '$', '(', ')', '/', '|', '+'));
        if (values.containsKey("{from}") && values.containsKey("{message}")) {
            String name = values.get("{from}");
            if (name != null && !name.isBlank()) {
                String[] splittedName = name.strip().split(" ");
                if (splittedName.length > 0) {
                    name = splittedName[0];
                    for (Character killer : nickKillingChars) {
                        name = name.replace(killer.toString(), "");
                    }
                    MessageResult result = new MessageResult();
                    if (values.containsKey("{starterPrefix}")) result.starter_prefix = values.get("{starterPrefix}");
                    if (values.containsKey("{rank}"))         result.rank = values.get("{rank}");
                    if (values.containsKey("{clan}"))         result.clan = values.get("{clan}");
                    if (values.containsKey("{team}"))         result.team = values.get("{team}");
                    if (values.containsKey("{global}"))       result.chat_type = values.get("{global}");
                    result.server = serverName;
                    result.serverMode = serverMode;
                    result.serverExactPrediction = ExactState;
                    result.from = name;
                    result.message = values.get("{message}");
                    return result;
                }
            }
        }
        return null;
    }

    public MessageResult receiveChat(AltoClef mod, String ourUsername, String msg, String server, String servermode) {
        boolean duplicate = msg.equals(_lastMessage);
        if (duplicate && !_repeatTimer.elapsed()) {
            _repeatTimer.reset();
            return null;
        }
        _lastMessage = msg;

        // 1. exact server + mode match
        for (String[] format : ButlerConfig.getInstance().chatFormats) {
            if (server.equals(format[0]) && servermode.equals(format[2])) {
                MessageResult check = chatParse(ourUsername, format, msg);
                if (check != null && check.from != null && check.message != null) return check;
            }
        }
        // 2. server only
        for (String[] format : ButlerConfig.getInstance().chatFormats) {
            if (server.equals(format[0])) {
                MessageResult check = chatParse(ourUsername, format, msg, "server");
                if (check != null && check.from != null && check.message != null) return check;
            }
        }
        // 3. universal
        for (String[] format : ButlerConfig.getInstance().chatFormats) {
            if ("universal".equals(format[0])) {
                MessageResult check = chatParse(ourUsername, format, msg, "universal");
                if (check != null && check.from != null && check.message != null) return check;
            }
        }
        // 4. random (any format)
        for (String[] format : ButlerConfig.getInstance().chatFormats) {
            MessageResult check = chatParse(ourUsername, format, msg, "random");
            if (check != null && check.from != null && check.message != null) return check;
        }
        return null;
    }

    public MessageResult receiveMessage(AltoClef mod, String ourUsername, String msg) {
        // Use a separate dedup tracker so that receiveChat() and receiveMessage()
        // don't interfere with each other when called on the same message.
        boolean duplicate = msg.equals(_lastWhisperMessage);
        if (duplicate && !_whisperRepeatTimer.elapsed()) {
            _whisperRepeatTimer.reset();
            return null;
        }
        _lastWhisperMessage = msg;

        for (String format : ButlerConfig.getInstance().whisperFormats) {
            MessageResult check = tryParse(ourUsername, format, msg);
            if (check != null) {
                String user = check.from;
                String message = check.message;
                if (user == null || message == null) break;
                return check;
            }
        }
        return null;
    }

    public static class MessageResult {
        public String from;
        public String message;
        public String rank;
        public String serverMode;
        public String server;
        public String clan;
        public String team;
        public String chat_type;
        public String serverExactPrediction;
        public String starter_prefix;

        @Override
        public String toString() {
            return "MessageResult{from='" + from + "', message='" + message + "'}";
        }
    }
}
