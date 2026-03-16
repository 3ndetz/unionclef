/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package shredder;

import shredder.api.IBaritone;
import shredder.api.IBaritoneProvider;
import shredder.api.cache.IWorldScanner;
import shredder.api.command.ICommandSystem;
import shredder.api.schematic.ISchematicSystem;
import shredder.cache.FasterWorldScanner;
import shredder.command.CommandSystem;
import shredder.command.ExampleBaritoneControl;
import shredder.utils.schematic.SchematicSystem;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.MinecraftClient;

/**
 * @author Brady
 * @since 9/29/2018
 */
public final class BaritoneProvider implements IBaritoneProvider {

    private final List<IBaritone> all;
    private final List<IBaritone> allView;

    public BaritoneProvider() {
        this.all = new CopyOnWriteArrayList<>();
        this.allView = Collections.unmodifiableList(this.all);

        // Setup chat control, just for the primary instance
        final Baritone primary = (Baritone) this.createBaritone(MinecraftClient.getInstance());
        primary.registerBehavior(ExampleBaritoneControl::new);
    }

    @Override
    public IBaritone getPrimaryBaritone() {
        return this.all.get(0);
    }

    @Override
    public List<IBaritone> getAllBaritones() {
        return this.allView;
    }

    @Override
    public synchronized IBaritone createBaritone(MinecraftClient minecraft) {
        IBaritone baritone = this.getBaritoneForMinecraft(minecraft);
        if (baritone == null) {
            this.all.add(baritone = new Baritone(minecraft));
        }
        return baritone;
    }

    @Override
    public synchronized boolean destroyBaritone(IBaritone baritone) {
        return baritone != this.getPrimaryBaritone() && this.all.remove(baritone);
    }

    @Override
    public IWorldScanner getWorldScanner() {
        return FasterWorldScanner.INSTANCE;
    }

    @Override
    public ICommandSystem getCommandSystem() {
        return CommandSystem.INSTANCE;
    }

    @Override
    public ISchematicSystem getSchematicSystem() {
        return SchematicSystem.INSTANCE;
    }
}
