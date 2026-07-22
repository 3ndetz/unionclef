# Tungsten Multiversion: 1.21.1 + 1.21.11

## Goal

Make tungsten compile and work under both MC 1.21.1 (current) and MC 1.21.11 (upstream target).
Use the ReplayMod preprocessor, same approach as altoclef.

## Version codes

- `1.21.1` = `12101`
- `1.21.11` = `12111`

Formula: `major * 10000 + minor * 100 + patch`

## What breaks between 1.21.1 and 1.21.11

### Confirmed breaking changes

#### 1. Diagonal movement normalization (MC-271065)

Added in MC 1.21.4+. Method `applyDirectionalMovementSpeedFactors()` normalizes
diagonal input so moving diagonally isn't ~41% faster than straight.

- 1.21.1: diagonal input NOT normalized -- magnitude > 1.0 when W+A/D
- 1.21.4+: `applyDirectionalMovementSpeedFactors()` clamps magnitude

We already have this code in `Agent.java:1364-1397` (commented out with a versioning
warning). Needs `//#if MC >= 12104` gate.

Impact: ~29% diagonal speed difference. The pathfinder simulates with wrong speed
if this doesn't match the server version, causing drift on every diagonal segment.

#### 2. PlayerInput record (MC 1.21.4+)

`net.minecraft.util.PlayerInput` is a record class added in 1.21.4+.
In 1.21.1, `Input` class has no `playerInput` field.

We already solved this: `TungstenPlayerInput` is our own class that holds the same
data. The upstream uses `PlayerInput` directly -- we must NOT import that.

For 1.21.11, `PlayerInput` exists. Preprocessor gate:

```java
//#if MC >= 12104
import net.minecraft.util.PlayerInput;
//#else
//$$ // use TungstenPlayerInput instead
//#endif
```

Or just keep `TungstenPlayerInput` for all versions (simpler, no API dependency).

#### 3. Input class structure

- 1.21.1: `ClientPlayerEntity.input` is a `KeyboardInput` with individual boolean fields
  (`pressingForward`, `pressingBack`, etc.)
- 1.21.4+: `ClientPlayerEntity.input.playerInput` is a `PlayerInput` record

Our `MixinClientPlayerEntity.end()` already handles this by constructing
`TungstenPlayerInput` from individual fields (line 89-98). For 1.21.11, we might
need to read from the `playerInput` record instead.

#### 4. ServerPlayerEntity.setPlayerInput()

Added in MC 1.21.4+. Used by upstream for server-side path execution.
Does not exist in 1.21.1. Our PathExecutor already has this disabled (line 93).

For multiversion:

```java
//#if MC >= 12104
public void tick(ServerPlayerEntity player) {
    player.setPlayerInput(node.input.getPlayerInput());
}
//#endif
```

#### 5. Entity.stopGliding()

Removed or renamed between versions. Our PathExecutor already has a comment
about this (line 159: "player.stopGliding() removed in MC 1.21"). Upstream
still calls `player.stopGliding()` in their executor -- must be version-gated.

#### 6. Loom version jump (1.7.4 to 1.15-SNAPSHOT)

Fabric Loom 1.15 may change how mixins are processed, remapping behavior,
or access widener handling. The build.gradle needs version-conditional loom
or a shared compatible version.

### Likely breaking changes (needs verification at compile time)

#### 7. WorldView interface

`VoxelWorld` implements `WorldView`. Between MC versions, this interface grows
new abstract methods. Common additions:

- `getEnabledFeatures()` (already returns null in our impl)
- `getRegistryManager()` (already returns null)
- New methods in 1.21.11 may require stubs

#### 8. Chunk constructor signature

`MixinWorldChunk extends Chunk` -- the Chunk constructor takes `PalettesFactory`
which may have changed signature. The `loadFromPacket` mixin target method
signature may also differ.

#### 9. Rendering API changes

`DrawMode.DEBUG_LINES`, `VertexFormats.POSITION_COLOR`, `RenderLayer.getDebugLineStrip()`
-- rendering APIs shift frequently between MC versions. Our `MixinDebugRenderer`
will likely need version gates.

#### 10. Network packet changes

`EntityTrackerUpdateS2CPacket` and `PlayerPositionLookS2CPacket` -- field names
or method signatures may change. Our `MixinClientPlayNetworkHandler` targets
specific method descriptors that could shift.

### Probably safe (stable APIs)

- `BlockState`, `FluidState`, `VoxelShape` -- core world APIs, stable
- `Entity.getPos()`, `setYaw()`, `setPitch()` -- basic entity state
- `GameOptions.forwardKey` etc. -- input key bindings
- `MinecraftClient.getInstance()` -- singleton access
- `changeLookDirection()` -- mouse input API, stable across versions
- Mixin `@Inject` into `tick()` methods -- stable targets

## Implementation plan

### Phase 1: Build infrastructure

1. Add preprocessor plugin to `tungsten/build.gradle`
2. Create `tungsten/versions/1.21.1/` and `tungsten/versions/1.21.11/` dirs
3. Add tungsten version nodes to `root.gradle.kts`
4. Add per-version mappings/fabric-api to tungsten's build.gradle
5. Wire altoclef version projects to include matching tungsten version

### Phase 2: Source gates

Add `//#if` directives for confirmed breaking changes:

- `Agent.java` -- diagonal normalization gate
- `MixinClientPlayerEntity.java` -- Input/PlayerInput reading
- `PathExecutor.java` -- server-side tick, stopGliding
- `MixinWorldChunk.java` -- constructor/method signatures (if changed)
- `MixinDebugRenderer.java` -- rendering API (if changed)

### Phase 3: Compile and fix

Build for 1.21.11 and fix whatever else breaks. Most issues will be in mixins
(method descriptors shifting) and WorldView interface additions.

## Reference: altoclef preprocessor example

```java
//#if MC >= 12101
return pos.getSquaredDistance(obj);
//#else
//$$ return pos.getSquaredDistance(obj.getX(), obj.getY(), obj.getZ(), true);
//#endif
```

Active code compiles normally. Inactive code stays as `//$$` comments.
The preprocessor strips branches based on the `MC` constant derived from
the project name (e.g. project `:1.21.11` gets `MC = 12111`).
