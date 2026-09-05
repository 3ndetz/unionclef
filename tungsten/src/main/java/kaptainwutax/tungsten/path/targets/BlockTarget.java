package kaptainwutax.tungsten.path.targets;

import com.mojang.brigadier.exceptions.BuiltInExceptions;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import kaptainwutax.tungsten.commandsystem.Arg;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.world.Dimension;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public class BlockTarget implements ArgumentType<BlockTarget> {
	private final int _x;
    private final int _y;
    private final int _z;
    private final Dimension _dimension;
    private final BlockTargetCoordType _type;
    static final DynamicCommandExceptionType INVALID_NUM_OF_COORD_ARGS = new DynamicCommandExceptionType(
	    numbers -> Text.literal("Unexpected number of integers passed to coordinate: " + numbers));

    public BlockTarget(int x, int y, int z, Dimension dimension, BlockTargetCoordType type) {
	_x = x;
	_y = y;
	_z = z;
	_dimension = dimension;
	_type = type;
    }
    
    public Vec3d getVec3d() {
    	return new Vec3d(_x, _y, _z);
    }

    public static BlockTarget parseRemainder(String line) throws CommandException {
	line = line.trim();
	if (line.startsWith("(") && line.endsWith(")")) {
	    line = line.substring(1, line.length() - 1);
	}
	String[] parts = line.split(" ");
	List<Integer> numbers = new ArrayList<>();
	Dimension dimension = null;
	for (String part : parts) {
	    try {
		int num = Integer.parseInt(part);
		numbers.add(num);
	    } catch (NumberFormatException e) {
		dimension = (Dimension) Arg.parseEnum(part, Dimension.class);
		break;
	    }
	}
	int x = 0, y = 0, z = 0;
	BlockTarget.BlockTargetCoordType coordType;
	switch (numbers.size()) {
	case 0 -> coordType = BlockTargetCoordType.NONE;
	case 1 -> {
	    y = numbers.get(0);
	    coordType = BlockTargetCoordType.Y;
	}
	case 2 -> {
	    x = numbers.get(0);
	    z = numbers.get(1);
	    coordType = BlockTargetCoordType.XZ;
	}
	case 3 -> {
	    x = numbers.get(0);
	    y = numbers.get(1);
	    z = numbers.get(2);
	    coordType = BlockTargetCoordType.XYZ;
	}
	default -> throw new CommandException("Unexpected number of integers passed to coordinate: " + numbers.size());
	}
	return new BlockTarget(x, y, z, dimension, coordType);
    }

    public int getX() {
	return _x;
    }

    public int getY() {
	return _y;
    }

    public int getZ() {
	return _z;
    }

    public Dimension getDimension() {
	return _dimension;
    }

    public boolean hasDimension() {
	return _dimension != null;
    }

    public BlockTargetCoordType getType() {
	return _type;
    }

    // Combination of types we can have
    public enum BlockTargetCoordType {
	XYZ, // [x, y, z]
	XZ, // [x, z]
	Y, // [y]
	NONE // []
    }

    @Override
    public BlockTarget parse(StringReader reader) throws CommandSyntaxException {
	reader.skipWhitespace();
	if (!reader.canRead()) {
		throw new BuiltInExceptions().readerExpectedStartOfQuote().createWithContext(reader);
	}
	String[] parts = reader.readString().split(" ");
	List<Integer> numbers = new ArrayList<>();
	Dimension dimension = null;
	for (String part : parts) {
	    try {
		int num = Integer.parseInt(part);
		numbers.add(num);
	    } catch (NumberFormatException e) {
		try {
		    dimension = (Dimension) Arg.parseEnum(part, Dimension.class);
		} catch (CommandException e1) {
		    // ⛔ FIXED 2026-09-05: this swallowed a bad dimension token (e.g. ";goto 5 ENDX")
		    // with only a printStackTrace() -- the command then silently proceeded with
		    // dimension=null instead of telling the user their argument didn't parse. This
		    // method IS live (registered as the ArgumentType brigadier uses for the
		    // "gotoTarget" argument, per GotoTargetArgumentType.java), so a real typo here
		    // was invisible to the player. Its sibling parseRemainder() above gets this right
		    // by simply declaring `throws CommandException` and letting it propagate; this
		    // override can't do that (fixed ArgumentType#parse signature), so it must convert
		    // to the checked exception brigadier actually expects instead of eating it.
		    throw new CommandSyntaxException(
		        new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Text.literal(e1.getMessage())),
		        Text.literal(e1.getMessage()));
		}
		break;
	    }
	}
	int x = 0, y = 0, z = 0;
	BlockTarget.BlockTargetCoordType coordType;
	switch (numbers.size()) {
	case 0 -> coordType = BlockTargetCoordType.NONE;
	case 1 -> {
	    y = numbers.get(0);
	    coordType = BlockTargetCoordType.Y;
	}
	case 2 -> {
	    x = numbers.get(0);
	    z = numbers.get(1);
	    coordType = BlockTargetCoordType.XZ;
	}
	case 3 -> {
	    x = numbers.get(0);
	    y = numbers.get(1);
	    z = numbers.get(2);
	    coordType = BlockTargetCoordType.XYZ;
	}
	default -> throw INVALID_NUM_OF_COORD_ARGS.create(numbers.size());
	}
	;
	return new BlockTarget(x, y, z, dimension, coordType);
    }
}
