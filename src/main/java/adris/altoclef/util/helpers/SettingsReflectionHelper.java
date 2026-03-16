package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class SettingsReflectionHelper {

    public static List<SettingInfo> getSettableFields(Object configObject) {
        List<SettingInfo> settings = new ArrayList<>();
        Class<?> clazz = configObject.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            if (isSettableField(field)) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(configObject);
                    settings.add(new SettingInfo(field.getName(), field.getType(), value, field));
                } catch (IllegalAccessException e) {
                    Debug.logWarning("Could not access field: " + field.getName());
                }
            }
        }
        return settings;
    }

    public static Optional<Field> findSettingField(Class<?> clazz, String settingName) {
        try {
            Field field = clazz.getDeclaredField(settingName);
            if (isSettableField(field)) return Optional.of(field);
        } catch (NoSuchFieldException ignored) {}

        for (String pattern : generateFieldNamePatterns(settingName)) {
            try {
                Field field = clazz.getDeclaredField(pattern);
                if (isSettableField(field)) return Optional.of(field);
            } catch (NoSuchFieldException ignored) {}
        }

        for (Field field : clazz.getDeclaredFields()) {
            if (isSettableField(field)) {
                String fieldName = field.getName().toLowerCase();
                String searchName = settingName.toLowerCase();
                if (fieldName.contains(searchName) || searchName.contains(fieldName)) {
                    return Optional.of(field);
                }
            }
        }
        return Optional.empty();
    }

    public static boolean setSetting(Object configObject, String settingName, String newValue) {
        Optional<Field> fieldOpt = findSettingField(configObject.getClass(), settingName);
        if (fieldOpt.isPresent()) {
            Field field = fieldOpt.get();
            try {
                field.setAccessible(true);
                Object oldValue = field.get(configObject);
                Object convertedValue = convertStringToType(newValue, field.getType());
                field.set(configObject, convertedValue);
                Debug.logMessage("Setting '" + field.getName() + "' changed from '" + oldValue + "' to '" + convertedValue + "'");
                return true;
            } catch (Exception e) {
                Debug.logError("Failed to set field '" + field.getName() + "': " + e.getMessage());
            }
        }
        return false;
    }

    public static Optional<Object> getSetting(Object configObject, String settingName) {
        Optional<Field> fieldOpt = findSettingField(configObject.getClass(), settingName);
        if (fieldOpt.isPresent()) {
            Field field = fieldOpt.get();
            try {
                field.setAccessible(true);
                return Optional.of(field.get(configObject));
            } catch (IllegalAccessException e) {
                Debug.logError("Failed to get field '" + field.getName() + "': " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static boolean isSettableField(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) ||
                Modifier.isTransient(modifiers) || field.isAnnotationPresent(JsonIgnore.class)) {
            return false;
        }
        if (field.getName().startsWith("_")) return false;
        Class<?> type = field.getType();
        return type == boolean.class || type == Boolean.class ||
               type == int.class || type == Integer.class ||
               type == float.class || type == Float.class ||
               type == double.class || type == Double.class ||
               type == String.class;
    }

    private static String[] generateFieldNamePatterns(String settingName) {
        String camelCase = toCamelCase(settingName);
        return new String[]{
            settingName, settingName.toLowerCase(), camelCase,
            "show" + capitalize(camelCase), "is" + capitalize(camelCase),
            "should" + capitalize(camelCase), "auto" + capitalize(camelCase),
            settingName.replace("_", ""), settingName.replace("-", ""),
            settingName.replaceAll("[^a-zA-Z0-9]", "")
        };
    }

    private static String toCamelCase(String str) {
        if (str == null || str.isEmpty()) return str;
        String[] parts = str.split("[_\\-\\s]+");
        if (parts.length == 0) return str;
        StringBuilder result = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) result.append(capitalize(parts[i]));
        return result.toString();
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private static Object convertStringToType(String value, Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == float.class || type == Float.class) return Float.parseFloat(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        if (type == String.class) return value;
        throw new IllegalArgumentException("Unsupported type: " + type.getName());
    }

    public static class SettingInfo {
        public final String name;
        public final Class<?> type;
        public final Object currentValue;
        public final Field field;

        public SettingInfo(String name, Class<?> type, Object currentValue, Field field) {
            this.name = name;
            this.type = type;
            this.currentValue = currentValue;
            this.field = field;
        }

        @Override
        public String toString() {
            return name + " (" + type.getSimpleName() + ") = " + currentValue;
        }
    }
}
