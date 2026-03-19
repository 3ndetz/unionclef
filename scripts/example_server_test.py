"""Example: connect to a server, run a command, check result, output to file.

Usage:
    uv run example_server_test.py [port]

Requires autoConnectServer to be set in altoclef_settings.json,
or the bot to be already connected to a server.
"""

import sys
import time
from pathlib import Path

from py4j.java_gateway import JavaGateway

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25333
gw = JavaGateway(gateway_parameters={"port": PORT})
bot = gw.entry_point

RESULT_FILE = Path(__file__).parent / "result.txt"


def wait_for_game(timeout: int = 120) -> bool:
    """Wait until the bot is in-game."""
    start = time.time()
    while not bot.inGame():
        if time.time() - start > timeout:
            return False
        time.sleep(2)
    return True


def run_command(cmd: str, timeout: int = 300) -> bool:
    """Execute a bot command and wait for it to finish. Returns True if completed within timeout."""
    bot.ExecuteCommand(cmd)
    start = time.time()
    time.sleep(1)  # let the task register
    while bot.hasActiveTask():
        if time.time() - start > timeout:
            return False
        time.sleep(1)
    return True


def main():
    print(f"Connected on port {PORT}")

    # Wait for the bot to be in-game (auto-connect or manual)
    if not bot.inGame():
        print("Waiting for bot to join server...")
        if not wait_for_game():
            RESULT_FILE.write_text("FAIL: timeout waiting for game\n")
            sys.exit(1)

    print(f"In game. Health={bot.getHealth()}")

    # --- your test scenario here ---
    ok = run_command("@goto 100 64 100", timeout=120)
    health = bot.getHealth()

    result = "OK" if ok and health > 0 else "FAIL"
    details = f"result={result} health={health} command_finished={ok}"
    print(details)
    RESULT_FILE.write_text(details + "\n")


if __name__ == "__main__":
    main()
