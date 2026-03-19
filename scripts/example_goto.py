"""Example: connect to the bot, run a command, wait for it to finish, write result."""

import sys
import time
from pathlib import Path

from py4j.java_gateway import JavaGateway

# Connect to the running Minecraft instance (default port 25333)
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25333
gw = JavaGateway(gateway_parameters={"port": PORT})
bot = gw.entry_point

print(f"Connected to bot on port {PORT}")
print(f"In game: {bot.inGame()}")

if not bot.inGame():
    print("Bot is not in game. Set autoConnectServer in altoclef_settings.json or join manually.")
    sys.exit(1)

# Run a goto command
bot.ExecuteCommand("@goto 0 64 0")
print("Running @goto 0 64 0 ...")

# Poll until the task finishes
while bot.hasActiveTask():
    time.sleep(1)

print("Task finished.")

# Write result
result_file = Path(__file__).parent / "result.txt"
result_file.write_text("OK\n")
print(f"Result written to {result_file}")
