package adris.altoclef.butler;

import java.util.Objects;

/**
 * Chat parser tester — uses real WhisperChecker.chatParse logic.
 * Run via: gradlew testChatParser
 */
public class ChatParserTest {

    static int passed = 0, failed = 0;

    static void test(String label, String[] format, String rawMessage,
                     String expectedFrom, String expectedMessage) {
        WhisperChecker.MessageResult result = WhisperChecker.chatParse("TestBot", format, rawMessage);
        String from = result != null ? result.from : null;
        String msg = result != null ? result.message : null;

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

    static void testNoMatch(String label, String[] format, String rawMessage) {
        WhisperChecker.MessageResult result = WhisperChecker.chatParse("TestBot", format, rawMessage);
        if (result == null) {
            passed++;
            System.out.println("  OK  " + label + " (no match, as expected)");
        } else {
            failed++;
            System.out.println("  FAIL " + label + " (should not match)");
            System.out.println("       got: from=" + result.from + " msg=" + result.message);
        }
    }

    static String[] fmt(String server, String pattern, String mode) {
        return new String[]{server, pattern, mode};
    }

    public static void main(String[] args) {
        System.out.println("=== Chat Parser Tests ===\n");

        // --- Vanilla ---
        System.out.println("[vanilla]");
        test("basic vanilla",
                fmt("universal", "<{from}> {message}", "survival"),
                "<Steve> hello world",
                "Steve", "hello world");

        test("vanilla with leading space",
                fmt("universal", "<{from}> {message}", "survival"),
                " <Steve> hello world",
                "Steve", "hello world");

        // --- mlegacy skypvp ---
        System.out.println("\n[mlegacy skypvp]");
        test("rank+from double space",
                fmt("mlegacy.net", "[{rank}]  {from} ➠ {message}", "skypvp"),
                " [Воин]  Hirushka ➠ как можно с пингом 5 промахиваться",
                "Hirushka", "как можно с пингом 5 промахиваться");

        test("rank+from single space",
                fmt("mlegacy.net", "[{rank}]  {from} ➠ {message}", "skypvp"),
                " [Солдат] wearthkii ➠ ладно не будем булить за тачки за селом",
                "wearthkii", "ладно не будем булить за тачки за селом");

        test("clan+rank+from",
                fmt("mlegacy.net", "({clan}) [{rank}] {from} ➠ {message}", "skypvp"),
                " (invulnerable) [Воин]  artem228proaye ➠ поменял | kamz0ner",
                "artem228proaye", "поменял | kamz0ner");

        // --- mlegacy skywars ---
        System.out.println("\n[mlegacy skywars]");
        test("rank » msg",
                fmt("mlegacy.net", "[{rank}] {from}  » {message}", "skywars"),
                "[VIP] player123  » gg",
                "player123", "gg");

        test("(rank) > msg",
                fmt("mlegacy.net", "({rank}) {from} > {message}", "skywars"),
                "(Mod) admin1 > всем привет",
                "admin1", "всем привет");

        // --- mlegacy survival ---
        System.out.println("\n[mlegacy survival]");
        test("global rank ➯",
                fmt("mlegacy.net", "{global} [{rank}] {from} ➯ {message}", "survival"),
                "Ⓖ [Барон] testplayer ➯ кто на трейд?",
                "testplayer", "кто на трейд?");

        // --- musteryworld ---
        System.out.println("\n[musteryworld]");
        test("murder party",
                fmt("mc.musteryworld.net", "[Чат пати] {from} ➠ {message}", "murdermystery"),
                "[Чат пати] SomePlayer ➠ идём на точку",
                "SomePlayer", "идём на точку");

        test("simple colon",
                fmt("mc.musteryworld.net", "{from}: {message}", "murdermystery"),
                "PlayerName: тест сообщение",
                "PlayerName", "тест сообщение");

        test("bedwars flag",
                fmt("mc.musteryworld.net", "[⚑] {from}: {message}", "bedwars"),
                "[⚑] Fighter: за мной",
                "Fighter", "за мной");

        test("survival full",
                fmt("mc.musteryworld.net", "{starterPrefix} [{clan}] | [{rank}] {from} > {message}", "survival"),
                "Ⓛ [KINGS] | [Duke] warrior99 > продаю алмазы",
                "warrior99", "продаю алмазы");

        test("survival no clan",
                fmt("mc.musteryworld.net", "{starterPrefix} | [{rank}] {from} > {message}", "survival"),
                "Ⓛ | [Knight] builder1 > нужна помощь",
                "builder1", "нужна помощь");

        // --- vimemc ---
        System.out.println("\n[vimemc]");
        test("survival full",
                fmt("mc.vimemc.ru", "{starterPrefix} [{global}] [{clan}] | ᖧ{rank}ᖨ {from}  > {message}", "survival"),
                "Ⓛ [Мир] [CLAN] | ᖧVIPᖨ player1  > тест",
                "player1", "тест");

        test("murdermystery donate",
                fmt("mc.vimemc.ru", "ᖧ{donate}ᖨ {from} ⇨ {message}", "murdermystery"),
                "ᖧPremiumᖨ detective1 ⇨ я нашёл убийцу",
                "detective1", "я нашёл убийцу");

        test("murdermystery plain",
                fmt("mc.vimemc.ru", "{from} ⇨ {message}", "murdermystery"),
                "nobody123 ⇨ кто убийца?",
                "nobody123", "кто убийца?");

        // --- funnymc ---
        System.out.println("\n[funnymc]");
        test("survival full",
                fmt("funnymc.ru", "{starterPrefix} {global} ({clan}) [{rank}] {from} ➯ {message}", "survival"),
                "Ⓖ Мир (TEAM) [Lord] king1 ➯ база тут",
                "king1", "база тут");

        test("skywars »",
                fmt("funnymc.ru", "[{rank}] {from}  » {message}", "skywars"),
                "[Pro] fighter  » идём",
                "fighter", "идём");

        // --- Edge cases ---
        System.out.println("\n[edge cases]");
        test("lots of leading whitespace",
                fmt("mlegacy.net", "[{rank}]  {from} ➠ {message}", "skypvp"),
                "   [Воин]  TestUser ➠ test msg",
                "TestUser", "test msg");

        test("tabs in spacing",
                fmt("mlegacy.net", "[{rank}]  {from} ➠ {message}", "skypvp"),
                "[Воин]\tTestUser ➠ tab test",
                "TestUser", "tab test");

        testNoMatch("garbage doesn't match",
                fmt("mlegacy.net", "[{rank}]  {from} ➠ {message}", "skypvp"),
                "random text without any format");

        testNoMatch("empty message",
                fmt("universal", "<{from}> {message}", "survival"),
                "");

        // --- Summary ---
        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }
}
