package kaptainwutax.tungsten.commandsystem;

import kaptainwutax.tungsten.path.targets.BlockTarget;

public class Arg<T> extends ArgBase {
    private final Class<T> _tType;
    public T Default;
    private boolean _isArray = false;
    private String _name = "";
    private boolean _showDefault;

    // Regular Constructor
    public Arg(Class<T> type, String name) throws CommandException {
        _name = name;
        // I really hate java
        _tType = type;

        _showDefault = true;
        _hasDefault = false;
        // If enum, we're good
        if (!_tType.isEnum()) {
            // Make sure as an extra precaution that we only use (non enum) types we can handle
            if (!isInstancesOf(_tType, String.class, Float.class, Integer.class, Double.class, Long.class, BlockTarget.class)) {
                throw new CommandException("Arguments are not programmed to parse the following type: " + _tType + ". This is either not implemented intentionally or by accident somehow.");
            }
        }
    }

    // Constructor with default value
    public Arg(Class<T> type, String name, T defaultValue, int minArgCountToUseDefault, boolean showDefault) throws CommandException {
        this(type, name);
        _hasDefault = true;
        Default = defaultValue;
        _minArgCountToUseDefault = minArgCountToUseDefault;
        _showDefault = showDefault;
    }

    public Arg(Class<T> type, String name, T defaultValue, int minArgCountToUseDefault) throws CommandException {
        this(type, name, defaultValue, minArgCountToUseDefault, true);
    }

    public static Object parseEnum(String unit, Class type) throws CommandException {
        unit = unit.toLowerCase().trim();
        StringBuilder res = new StringBuilder();
        for (Object v : type.getEnumConstants()) {
            if (v.toString().toLowerCase().equals(unit)) {
                return v;
            }
            res.append(type);
            res.append("|");
        }
        res.delete(res.length() - 1, res.length()); // Remove the last "|"
        throw new CommandException("Invalid argument found: " + unit + ". Accepted values are: " + res);
    }

    // This is important cause if it is, it will stop parsing further variables and end here as it is a params.
    @Override
    public boolean isArray() {
        return _isArray;
    }

    private boolean isEnum() {
        return _tType.isEnum();
    }

    // Horrendous chain syntax that I'm only using here.
    public Arg<T> asArray() {
        _isArray = true;
        return this;
    }

    /// <summary>
///     Return the "help" command representation of this argument.
///     For instance, in a "dialogue" command it looks like this:
///         dialogue <name = ""> [text]
///     name is optional and defaults to "", while text is non optional.
/// </summary>
    @Override
    public String getHelpRepresentation() {
        if (hasDefault()) {
            if (_showDefault) {
                return "<" + _name + "=" + Default + ">";
            }
            return "<" + _name + ">";
        }
        return "[" + _name + "]";
    }

    @SuppressWarnings("unchecked")
    private <V> boolean isInstanceOf(Class<V> vType, Class<?> t) {
        return vType == t || vType.isAssignableFrom(t);
    }

    private <V> boolean isInstancesOf(Class<V> vType, Class<?>... types) {
        // I really hate java

        for (Class<?> t : types) {
            if (isInstanceOf(vType, t)) {
                return true;
            }
        }
        return false;
    }

    private void parseErrorCheck(boolean good, Object value, String type) throws CommandException {
        if (!good)
            throw new CommandException("Failed to parse the following argument into type " + type + ": " + value + ".");
    }

    private <V> V parseUnitUtil(Class<V> vType, String unit, String[] unitPlusRemainder) throws CommandException {
        // If enum, check from our cached enum dictionary.
        if (isEnum()) {
            return getConverted(vType, parseEnum(unit, vType));
        }

        // Do number parsing.
        if (isInstanceOf(vType, Float.class)) {
            try {
                return getConverted(vType, Float.parseFloat(unit));
            } catch (NumberFormatException e) {
                parseErrorCheck(false, unit, "float");
            }
        }
        if (isInstanceOf(vType, Double.class)) {
            try {
                return getConverted(vType, Double.parseDouble(unit));
            } catch (NumberFormatException e) {
                parseErrorCheck(false, unit, "double");
            }
        }
        if (isInstanceOf(vType, Integer.class)) {
            try {
                return getConverted(vType, Integer.parseInt(unit));
            } catch (NumberFormatException e) {
                parseErrorCheck(false, unit, "int");
            }
        }
        if (isInstanceOf(vType, Long.class)) {
            try {
                return getConverted(vType, Long.parseLong(unit));
            } catch (NumberFormatException e) {
                parseErrorCheck(false, unit, "long");
            }
        }

        if (isInstanceOf(vType, BlockTarget.class)) {
            return getConverted(vType, BlockTarget.parseRemainder(String.join(" ", unitPlusRemainder)));
        }

        // Now do String parsing.
        if (isInstanceOf(vType, String.class)) {
            // Remove quotes
            if (unit.length() >= 2) {
                if (unit.charAt(0) == '\"' && unit.charAt(unit.length() - 1) == '\"') {
                    unit = unit.substring(1, unit.length() - 1);
                }
            }
            return getConverted(vType, unit);
        }

        throw new CommandException("Arguments are not programmed to parse the following type: " + vType + ". This is either not implemented intentionally or by accident somehow.");
    }

    @Override
    public Object parseUnit(String unit, String[] unitPlusRemainder) throws CommandException {
        return parseUnitUtil(_tType, unit, unitPlusRemainder);
    }

    public boolean checkValidUnit(String arg, StringBuilder errorMsg) {
        errorMsg.delete(0, errorMsg.length());
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V getDefault(Class<V> vType) {
        return getConverted(vType, Default);
    }

    @Override
    public boolean isArbitrarilyLong() {
        return isInstanceOf(_tType, BlockTarget.class);
    }
}
