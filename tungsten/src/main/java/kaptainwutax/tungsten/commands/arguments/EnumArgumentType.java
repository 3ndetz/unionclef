package kaptainwutax.tungsten.commands.arguments;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.command.CommandSource;

public class EnumArgumentType<E extends Enum<E>> implements ArgumentType<E> {
	 private final Class<E> enumClass;

    public EnumArgumentType(Class<E> enumClass) {
    	this.enumClass = enumClass;
    }

    public static <E extends Enum<E>> EnumArgumentType<E> of(Class<E> enumClass) {
        return new EnumArgumentType<>(enumClass);
    }

    @Override
    public E parse(StringReader reader) throws CommandSyntaxException {
        String value = reader.readUnquotedString();
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.literalIncorrect().createWithContext(reader, value);
        }
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
    	
        return CommandSource.suggestMatching(Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .map(String::toLowerCase), builder);
    }
}
