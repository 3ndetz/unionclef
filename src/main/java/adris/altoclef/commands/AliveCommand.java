package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.movement.AliveConfig;
import adris.altoclef.tasks.movement.AliveTask;

import java.util.ArrayList;
import java.util.List;

/**
 * @alive — agent-controlled "act like a real player" behavior with LIVE, changeable goals.
 *   @alive                      show the current config (mode + targets) as an alive-event
 *   @alive idle [sec]           fidget on the spot (look around, glance at people, hops)
 *   @alive watch <nicks> [sec]  keep LOOKING at these nicks (comma-separated)
 *   @alive near  <nick> [sec]   hang AROUND a nick (drift toward + fidget next to them)
 *   @alive roam  [sec]          wander random nearby points, looking around
 * Stop with @idle / @stop. While it runs you keep talking/playing; AliveTask emits events
 * (target_lost / stuck / arrived) into your events.sh so you react or preempt with a real task.
 * Re-issue @alive with new args ANY time to change targets/mode WITHOUT restarting (live config).
 */
public class AliveCommand extends Command {
    public AliveCommand() {
        super("alive",
                "Act like a player with live goals: @alive watch|near|roam|idle <nicks> [sec]. Emits events; @idle to stop.",
                new StringArg("mode", "status"),
                new StringArg("nicks", ""),
                new StringArg("seconds", "600"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String modeArg = parser.get(String.class);
        String nicksArg = parser.get(String.class);
        String secsArg = parser.get(String.class);
        String mode = (modeArg == null ? "status" : modeArg).toLowerCase();

        if (mode.equals("status") || mode.equals("?")) {
            System.out.println("ALIVE_EVENT {\"ev\":\"status\",\"arg\":\"" + AliveConfig.describe().replace("\"", "") + "\"}");
            finish();
            return;
        }

        List<String> targets = new ArrayList<>();
        if (nicksArg != null && !nicksArg.isBlank())
            for (String s : nicksArg.split("[,;]+"))
                if (!s.isBlank()) targets.add(s.trim());

        double secs = 600;
        try {
            if (secsArg != null && !secsArg.isBlank()) secs = Double.parseDouble(secsArg);
        } catch (NumberFormatException ignored) { }

        AliveConfig.Mode m;
        switch (mode) {
            case "watch": m = AliveConfig.Mode.WATCH; break;
            case "near":  m = AliveConfig.Mode.NEAR;  break;
            case "roam":  m = AliveConfig.Mode.ROAM;  break;
            default:      m = AliveConfig.Mode.IDLE;  break;
        }
        AliveConfig.set(m, targets, AliveConfig.radius());
        System.out.println("ALIVE_EVENT {\"ev\":\"config\",\"arg\":\"" + AliveConfig.describe().replace("\"", "") + "\"}");
        // isEqual(AliveTask)==true -> if already alive, this updates config WITHOUT restarting the task.
        mod.runUserTask(new AliveTask(secs), this::finish);
    }
}
