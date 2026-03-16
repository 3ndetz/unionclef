package adris.altoclef;

import adris.altoclef.commands.*;
import adris.altoclef.commands.multiplayer.*;
import adris.altoclef.commands.random.ScanCommand;
import adris.altoclef.commands.random.DummyTaskCommand;
import adris.altoclef.commandsystem.exception.CommandException;

/**
 * Initializes altoclef's built in commands.
 */
public class AltoClefCommands {

    public static void init() throws CommandException {
        // List commands here
        AltoClef.getCommandExecutor().registerNewCommand(
                new HelpCommand(),
                new GetCommand(),
                new ListCommand(),
                new EquipCommand(),
                new DepositCommand(),
                new StashCommand(),
                new GotoCommand(),
                new IdleCommand(),
                new HeroCommand(),
                new CoordsCommand(),
                new StatusCommand(),
                new InventoryCommand(),
                new LocateStructureCommand(),
                new StopCommand(),
                new PauseCommand(),
                new UnPauseCommand(),
                new SetGammaCommand(),
                new TestCommand(),
                new FoodCommand(),
                new MeatCommand(),
                new ReloadSettingsCommand(),
                new GamerCommand(),
                new MarvionCommand(),
                new DummyTaskCommand(),
                new FollowCommand(),
                new ScanCommand(),
                new GiveCommand(),
                new PunkCommand(),
                new SelfCareCommand(),
                // Agent / custom tasks / settings
                new AgentCommand(),
                new CustomCommand(),
                new SetSettingsCommand(),
                new TimeoutCommand(),
                new TimeoutSpecificCommand(),
                new CoverWithBlocksCommand(),
                new CoverWithSandCommand(),
                // Multiplayer commands
                new AvoidCommand(),
                new CombatCommand(),
                new PursueCommand(),
                new GestureCommand(),
                new ShiftCommand(),
                new ShootCommand(),
                new ConnectCommand(),
                new SignCommand(),
                new GraveCommand(),
                new GameCommand(),
                new NickCommand(),
                new CheckPlayerCommand(),
                new PearlCommand(),
                new ForgetCommand(),
                new BuildCommand(),
                new CheckBlockCommand()
        );
    }
}
