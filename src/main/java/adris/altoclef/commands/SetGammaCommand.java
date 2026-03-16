package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.args.DoubleArg;
import adris.altoclef.multiversion.OptionsVer;

public class SetGammaCommand extends Command {

    public SetGammaCommand() throws CommandException {
        super("gamma", "Sets the brightness to a value",
                new DoubleArg("gamma", 1.0)
        );
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        double gammaValue = parser.get(Double.class);
        changeGamma(gammaValue);
    }

    public static void changeGamma(double value) {
        Debug.logMessage("Gamma set to " + value);

        OptionsVer.setGamma(value);
    }

}