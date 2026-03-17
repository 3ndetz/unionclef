package adris.altoclef.butler;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone chat parser tester. Run with: java ChatParserTest
 * No Minecraft dependencies — copies parsing logic locally.
 */
public class ChatParserTest {

    // ── Inline copy of parsing logic (no MC deps) ──────────────────────

    static String escapeRegexChars(String text) {
        for (char c : new char[]{'[', ']', '.', '^', '?', '*', '$', '(', ')', '/', '|', '+'}) {
            text = text.replace(String.valueOf(c), "\\" + c);
        }
        return text;
    }

    static Map<String, String> chatParse(String format, String message) {
        List<String> allParts = new ArrayList<>(Arrays.asList(
                "{team}", "{global}", "{starterPrefix}", "{donate}", "{suffix}",
                "{clan}", "{rank}", "{from}", "{to}", "{message}"));

        String chatFormatNew = format;
        message = message.strip().replace("\\", "");

        // Normalize arrows
        List<String> arrows = Arrays.asList("➥", "->", "➡", "➥", "➯", "➨", "›", "►", "⋙", "»", "⪼", "⇨");
        for (String arrow : arrows) {
            if (!chatFormatNew.contains(arrow)) {
                message = message.replace(arrow, ">");
            }
        }

        chatFormatNew = escapeRegexChars(chatFormatNew);
        String chatFormat = chatFormatNew;

        List<String> parts = new ArrayList<>(allParts);
        parts.sort(Comparator.comparingInt(chatFormat::indexOf));
        parts.removeIf(part -> !chatFormat.contains(part));

        String regexFormat = Pattern.quote(chatFormat);
        for (String part : parts) {
            regexFormat = regexFormat.replace(part, "(.+)");
        }
        if (regexFormat.startsWith("\\Q")) regexFormat = regexFormat.substring(2);
        if (regexFormat.endsWith("\\E")) regexFormat = regexFormat.substring(0, regexFormat.length() - 2);

        // Normalize whitespace in regex
        regexFormat = regexFormat.replaceAll(" +", "\\\\s+");

        Pattern p = Pattern.compile(regexFormat);
        Matcher m = p.matcher(message);
        Map<String, String> values = new HashMap<>();
        if (m.matches()) {
            for (int i = 0; i < m.groupCount() && i < parts.size(); i++) {
                values.put(parts.get(i), m.group(i + 1));
            }
        }

        // Clean up {from} like the real parser
        if (values.containsKey("{from}")) {
            String name = values.get("{from}").strip().split(" ")[0];
            for (char c : new char[]{'~', '[', ']', '.', '^', '?', '*', '$', '(', ')', '/', '|', '+'}) {
                name = name.replace(String.valueOf(c), "");
            }
            values.put("{from}", name);
        }

        return values;
    }

    // ── Test infra ─────────────────────────────────────────────────────

    static int passed = 0, failed = 0;

    static void test(String label, String format, String rawMessage,
                     String expectedFrom, String expectedMessage) {
        Map<String, String> result = chatParse(format, rawMessage);
        String from = result.get("{from}");
        String msg = result.get("{message}");

        boolean ok = Objects.equals(from, expectedFrom) && Objects.equals(msg, expectedMessage);
        if (ok) {
            passed++;
            System.out.println("  OK  " + label);
        } else {
            failed++;
            System.out.println("  FAIL " + label);
            System.out.println("       expected: from=" + expectedFrom + " msg=" + expectedMessage);
            System.out.println("       got:      from=" + from + " msg=" + msg);
        }
    }

    static void testNoMatch(String label, String format, String rawMessage) {
        Map<String, String> result = chatParse(format, rawMessage);
        if (!result.containsKey("{from}") || !result.containsKey("{message}")) {
            passed++;
            System.out.println("  OK  " + label + " (no match, as expected)");
        } else {
            failed++;
            System.out.println("  FAIL " + label + " (should not match)");
            System.out.println("       got: from=" + result.get("{from}") + " msg=" + result.get("{message}"));
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("=== Chat Parser Tests ===\n");

        // --- Vanilla ---
        System.out.println("[vanilla]");
        test("basic vanilla",
                "<{from}> {message}",
                "<Steve> hello world",
                "Steve", "hello world");

        test("vanilla with leading space",
                "<{from}> {message}",
                " <Steve> hello world",
                "Steve", "hello world");

        // --- mlegacy: [rank] from ➠ message ---
        System.out.println("\n[mlegacy skypvp]");
        test("mlegacy rank+from double space",
                "[{rank}]  {from} ➠ {message}",
                " [Воин]  Hirushka ➠ как можно с пингом 5 промахиваться",
                "Hirushka", "как можно с пингом 5 промахиваться");

        test("mlegacy rank+from single space",
                "[{rank}]  {from} ➠ {message}",
                " [Солдат] wearthkii ➠ ладно не будем булить за тачки за селом",
                "wearthkii", "ладно не будем булить за тачки за селом");

        test("mlegacy clan+rank+from",
                "({clan}) [{rank}] {from} ➠ {message}",
                " (invulnerable) [Воин]  artem228proaye ➠ поменял | kamz0ner",
                "artem228proaye", "поменял | kamz0ner");

        // --- mlegacy skywars ---
        System.out.println("\n[mlegacy skywars]");
        test("mlegacy skywars rank » msg",
                "[{rank}] {from}  » {message}",
                "[VIP] player123  » gg",
                "player123", "gg");

        test("mlegacy skywars (rank) > msg",
                "({rank}) {from} > {message}",
                "(Mod) admin1 > всем привет",
                "admin1", "всем привет");

        // --- mlegacy survival ---
        System.out.println("\n[mlegacy survival]");
        test("mlegacy survival global rank ➯",
                "{global} [{rank}] {from} ➯ {message}",
                "Ⓖ [Барон] testplayer ➯ кто на трейд?",
                "testplayer", "кто на трейд?");

        // --- musteryworld ---
        System.out.println("\n[musteryworld]");
        test("musteryworld murder party",
                "[Чат пати] {from} ➠ {message}",
                "[Чат пати] SomePlayer ➠ идём на точку",
                "SomePlayer", "идём на точку");

        test("musteryworld simple colon",
                "{from}: {message}",
                "PlayerName: тест сообщение",
                "PlayerName", "тест сообщение");

        test("musteryworld bedwars flag",
                "[⚑] {from}: {message}",
                "[⚑] Fighter: за мной",
                "Fighter", "за мной");

        test("musteryworld survival full",
                "{starterPrefix} [{clan}] | [{rank}] {from} > {message}",
                "Ⓛ [KINGS] | [Duke] warrior99 > продаю алмазы",
                "warrior99", "продаю алмазы");

        test("musteryworld survival no clan",
                "{starterPrefix} | [{rank}] {from} > {message}",
                "Ⓛ | [Knight] builder1 > нужна помощь",
                "builder1", "нужна помощь");

        // --- vimemc ---
        System.out.println("\n[vimemc]");
        test("vimemc survival full",
                "{starterPrefix} [{global}] [{clan}] | ᖧ{rank}ᖨ {from}  > {message}",
                "Ⓛ [Мир] [CLAN] | ᖧVIPᖨ player1  > тест",
                "player1", "тест");

        test("vimemc murdermystery",
                "ᖧ{donate}ᖨ {from} ⇨ {message}",
                "ᖧPremiumᖨ detective1 ⇨ я нашёл убийцу",
                "detective1", "я нашёл убийцу");

        test("vimemc murdermystery plain",
                "{from} ⇨ {message}",
                "nobody123 ⇨ кто убийца?",
                "nobody123", "кто убийца?");

        // --- funnymc ---
        System.out.println("\n[funnymc]");
        test("funnymc survival full",
                "{starterPrefix} {global} ({clan}) [{rank}] {from} ➯ {message}",
                "Ⓖ Мир (TEAM) [Lord] king1 ➯ база тут",
                "king1", "база тут");

        test("funnymc skywars",
                "[{rank}] {from}  » {message}",
                "[Pro] fighter  » идём",
                "fighter", "идём");

        // --- Edge cases ---
        System.out.println("\n[edge cases]");
        test("lots of leading whitespace",
                "[{rank}]  {from} ➠ {message}",
                "   [Воин]  TestUser ➠ test msg",
                "TestUser", "test msg");

        test("tabs in spacing",
                "[{rank}]  {from} ➠ {message}",
                "[Воин]\tTestUser ➠ tab test",
                "TestUser", "tab test");

        testNoMatch("garbage doesn't match",
                "[{rank}]  {from} ➠ {message}",
                "random text without any format");

        testNoMatch("empty message",
                "<{from}> {message}",
                "");

        // --- Summary ---
        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }
}
