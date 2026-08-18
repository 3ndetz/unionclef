package adris.altoclef.tasks.resources;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.multiversion.blockpos.BlockPosVer;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class MineAndCollectTask extends ResourceTask {

    private final Block[] _blocksToMine;

    private final MiningRequirement _requirement;

    private final TimerGame _cursorStackTimer = new TimerGame(3);

    private final MineOrCollectTask _subtask;

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _subtask = new MineOrCollectTask(_blocksToMine, this.itemTargets);
    }

    public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
        this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget[]{target}, blocksToMine, requirement);
    }

    public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget(item, count), blocksToMine, requirement);
    }

    public static Block[] itemTargetToBlockList(ItemTarget[] targets) {
        List<Block> result = new ArrayList<>(targets.length);
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                Block block = Block.getBlockFromItem(item);
                if (block != null && !WorldHelper.isAir(block)) {
                    result.add(block);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();

        // We're mining, so don't throw away pickaxes.
        mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

        _subtask.resetSearch();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    /** Times the tool gate was skipped because the target was already lying on the floor. */
    public static volatile int toolGateSkipped;

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // ⛔ A DROP ON THE FLOOR NEEDS NO PICKAXE, AND THIS GATE MADE THE BOT BLIND TO ONE.
        //
        // Two lines in this class disagree, and between them they hide a drop completely:
        //
        //   shouldAvoidPickingUp() returns TRUE, on the grounds that "picking up is controlled by
        //   a separate task here" -- which switches OFF ResourceTask's own pickup block; and
        //   this gate returns SatisfyMiningRequirementTask BEFORE that separate task (_subtask,
        //   the MineOrCollectTask that actually considers drops) ever gets to run.
        //
        // So while the requirement is unmet the bot has no pickup path at all. Measured on
        // pickup_pit, a course built for exactly this: RTGATE targets=[[diamond]] avoid=true
        // dropped=true -- the tracker HAD the diamond and the pickup block was skipped anyway --
        // with idrop=0/0/0/0 and scan=0 confirming the separate task never ran. The bot spent the
        // whole run trying to craft an iron pickaxe, hunting wood on a stone arena, while the
        // diamond it had been sent for lay eight blocks away (et=1247/1247, seen every tick).
        //
        // On the playthrough this is worse than a lost course: every ore the bot mines drops an
        // item, and any interruption that leaves the requirement unmet makes those drops invisible.
        //
        // The rule is simply that a tool is needed to BREAK a block, not to walk over a drop. If
        // the thing we were sent for is already on the ground, collect it and let the tool wait.
        if (!StorageHelper.miningRequirementMet(_requirement)) {
            if (kaptainwutax.tungsten.TungstenConfig.get().collectDropsBeforeTools
                    && mod.getEntityTracker().itemDropped(itemTargets)) {
                toolGateSkipped++;
                setDebugState("Collecting a drop instead of crafting a tool for it");
                return _subtask;
            }
            return new SatisfyMiningRequirementTask(_requirement);
        }

        if (_subtask.isMining()) {
            makeSureToolIsEquipped(mod);
        }

        // Wrong dimension check.
        if (_subtask.wasWandering() && isInWrongDimension(mod) && !mod.getBlockScanner().anyFound(_blocksToMine)) {
            return getToCorrectDimensionTask(mod);
        }

        return _subtask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof MineAndCollectTask task) {
            return Arrays.equals(task._blocksToMine, _blocksToMine);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Mine And Collect";
    }

    /**
     * Times a better tool was moved from the cursor into the hand mid-mining. Read as toolSwap.
     * It was structurally 0 on 1.21.11 before this was ported -- the body was switched off.
     */
    public static volatile int toolSwaps;

    private void makeSureToolIsEquipped(AltoClef mod) {
        if (_cursorStackTimer.elapsed() && !mod.getFoodChain().needsToEat()) {
            assert MinecraftClient.getInstance().player != null;
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (cursorStack != null && !cursorStack.isEmpty()) {
                // We have something in our cursor stack
                Item item = cursorStack.getItem();
                net.minecraft.block.BlockState mining = mod.getWorld().getBlockState(_subtask.miningPos());
                if (item.getDefaultStack().isSuitableFor(mining)) {
                    // ASK WHAT THE TOOLS DO TO THIS BLOCK, NOT WHAT CLASS THEY ARE.
                    //
                    // On 1.21.11 this whole body was switched off -- the mining-tool CLASS was
                    // deleted in that version and the port left a TODO where the comparison used
                    // to be -- so the bot never moved a better pickaxe from its cursor into its
                    // hand while mining. It would pick one up and carry on with whatever was
                    // already equipped, including a bare hand.
                    //
                    // Comparing mining SPEED on the block in front of us needs no version fork at
                    // all: getMiningSpeedMultiplier and isSuitableFor both exist unchanged in 1.21.1
                    // and 1.21.11. It is also a truer question than the old one, which asked about
                    // tool tiers and so could only ever rank pickaxes against pickaxes. The old
                    // `else` branch equipped ANY mining tool over a non-mining one even when that
                    // was a downgrade; a speed comparison cannot make that mistake. An empty hand
                    // scores 1.0, so a pickaxe wins on stone by arithmetic rather than by a special
                    // case, and axes, shovels and shears are handled by the same line for free.
                    ItemStack equipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
                    if (item.getDefaultStack().getMiningSpeedMultiplier(mining)
                            > equipped.getMiningSpeedMultiplier(mining)) {
                        toolSwaps++;
                        mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                    }
                }
            }
            _cursorStackTimer.reset();
        }
    }

    public static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {

        private final Block[] _blocks;
        private final ItemTarget[] _targets;
        private final Set<BlockPos> blacklist = new HashSet<>();
        private final MovementProgressChecker progressChecker = new MovementProgressChecker();
        private final Task _pickupTask;
        private BlockPos miningPos;

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
            _blocks = blocks;
            _targets = targets;
            _pickupTask = new PickupDroppedItemTask(_targets, true);
        }

        @Override
        protected Vec3d getPos(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return WorldHelper.toVec3d(b);
            }
            if (obj instanceof ItemEntity item) {
                return item.getPos();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected Optional<Object> getClosestTo(AltoClef mod, Vec3d pos) {
            Pair<Double, Optional<BlockPos>> closestBlock = getClosestBlock(mod,pos,  _blocks);
            Pair<Double, Optional<ItemEntity>> closestDrop = getClosestItemDrop(mod,pos,  _targets);

            double blockSq = closestBlock.getLeft();
            double dropSq = closestDrop.getLeft();

            // We can't mine right now.
            if (mod.getExtraBaritoneSettings().isInteractionPaused()) {
                return closestDrop.getRight().map(Object.class::cast);
            }

            if (dropSq <= blockSq) {
                return closestDrop.getRight().map(Object.class::cast);
            } else {
                return closestBlock.getRight().map(Object.class::cast);
            }
        }

        /** Times the tracker was asked about drops, and times it said there were some. Read as drop=asked/seen.
         *  rcon says three cobblestone entities are lying in the arena while the pack stays empty and the
         *  pickup task never ticks -- so whether the tracker AGREES that a drop exists is the question. */
        public static volatile int dropAsked, dropSeen;

        /** Squared distance inside which a drop is "already here" and needs no anti-ping-pong tax. */
        private static final double NEAR_DROP_SQ = 9.0;
        /** The historical handicap, kept for drops far enough away that walking to them costs time. */
        private static final double DROP_MINING_PENALTY = 10.0;

        /** Times the near-drop exemption actually changed the comparison. Read as drop's 3rd. */
        public static volatile int dropNearExempt;

        /** Candidates the block filter saw: accepted / rejected as unreachable / rejected as unbreakable.
         *  Read as scan=ok/unreach/nobreak. */
        public static volatile int scanAccepted, scanUnreachable, scanNoBreak;
        public static volatile int scanUnderfoot;
        /** Candidates rejected for being below standing height while the surface still had some. */
        public static volatile int scanBelowFeet;

        public static Pair<Double, Optional<ItemEntity>> getClosestItemDrop(AltoClef mod,Vec3d pos, ItemTarget... items) {
            Optional<ItemEntity> closestDrop = Optional.empty();
            dropAsked++;
            if (mod.getEntityTracker().itemDropped(items)) {
                dropSeen++;
                closestDrop = mod.getEntityTracker().getClosestItemDrop(pos, items);
            }

            // ⛔ THE +10 IS A HANDICAP IN SQUARED-DISTANCE SPACE, AND IT IS WHY A DROP AT THE BOT'S
            // FEET LOSES TO AN ORE FOUR BLOCKS AWAY.
            //
            // getClosestTo below compares `dropSq <= blockSq` with this penalty already added to
            // the drop and nothing added to the block. Ten in SQUARED space is not a small nudge:
            // a drop lying 2.6 blocks away (h=2.4, dy=-1.0, the geometry measured on every failing
            // mine_coal run) scores 16.8, so any ore inside 4.1 blocks beats it. On a course whose
            // ore sits in a cluster there is always such an ore, so collection is deferred to the
            // end of the run every time -- which is precisely when the drops are lying in the holes
            // the bot has just dug, and when the barren locks happen.
            //
            // The penalty's stated purpose -- "stop the bot ping-ponging away from its mining spot
            // for every item" -- is real, and it is about drops far enough away that walking there
            // costs something. It has no business applying to a drop the bot could TOUCH by taking
            // one step, where collecting costs a step and zero mining time, and where the drop is
            // the actual objective rather than a distraction from it.
            //
            // NEAR_DROP_SQ is 9 (three blocks). Eleven of the thirteen recorded failure geometries
            // sit at or under 2.4 blocks horizontal, so this covers the trunk of the distribution
            // and deliberately leaves the far tail alone.
            final boolean exempt = kaptainwutax.tungsten.TungstenConfig.get().dropNoPenaltyWhenNear;
            return new Pair<>(
                    closestDrop.map(itemEntity -> {
                        double trueSq = itemEntity.squaredDistanceTo(pos);
                        if (trueSq > NEAR_DROP_SQ) return trueSq + DROP_MINING_PENALTY;
                        // COUNT ALWAYS, ACT ONLY WHEN FLAGGED -- otherwise the control arm reads
                        // zero by construction and the counter cannot say the arms differed.
                        dropNearExempt++;
                        return trueSq + (exempt ? 0.0 : DROP_MINING_PENALTY);
                    }).orElse(Double.POSITIVE_INFINITY),
                    closestDrop
            );
        }

        /** Times the bot was found standing in a hole, and times it was not. Read as scan's 4th/5th. */
        public static volatile int scanEnclosed;

        /**
         * Is the bot standing in a hole -- solid ground on every side at its OWN feet level?
         *
         * <p>Four cardinals only. A diagonal is not what traps you: you can leave a pit through a
         * cardinal gap and cannot leave through a diagonal one, because the body is a box. The
         * 1x1 shaft this exists for is enclosed on all four.
         */
        private static boolean enclosedAtFeet(AltoClef mod, int feetY) {
            try {
                BlockPos feet = new BlockPos(mod.getPlayer().getBlockPos().getX(), feetY,
                        mod.getPlayer().getBlockPos().getZ());
                for (net.minecraft.util.math.Direction d
                        : new net.minecraft.util.math.Direction[]{
                        net.minecraft.util.math.Direction.NORTH,
                        net.minecraft.util.math.Direction.SOUTH,
                        net.minecraft.util.math.Direction.EAST,
                        net.minecraft.util.math.Direction.WEST}) {
                    if (!WorldHelper.isSolidBlock(feet.offset(d))) {
                        return false;
                    }
                }
                scanEnclosed++;
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public static Pair<Double,Optional<BlockPos> > getClosestBlock(AltoClef mod,Vec3d pos ,Block... blocks) {

            // !! DIGGING THE BLOCK UNDER YOUR OWN FEET IS HOW A BOT BURIES ITSELF.
            //
            // DestroyBlockTask.canClear already refuses exactly this, and says why in as many
            // words -- "clearing that is how a bot digs itself into a hole while trying to see a
            // tree" -- but that guard governs clearing an OBSTRUCTION, not choosing a TARGET.
            // Target selection never knew the rule, so on mine_stone the bot standing at y=-60
            // takes the nearest stone, which is directly beneath it, digs down to -63, lands in a
            // pit, climbs out onto the arena wall at y=-57 and strands itself where nothing is
            // reachable. Traced by polling its position once a second through a run.
            //
            // REFUSING IT OUTRIGHT WOULD BREAK DESCENDING, and mine_diamond is green today
            // precisely because the bot can dig down to reach ore. So this PREFERS anything else
            // and falls back to the underfoot block when it is genuinely the only candidate:
            // the exclusion runs first, and an empty result retries without it.
            // !! THE PIT IS THE DEFECT, NOT THE ONE BLOCK UNDER THE FEET.
            // mineAvoidUnderfoot forbade exactly one position and measured 0.40 sigma, because the
            // bot descends anyway: it takes a block a step aside, follows it down, and ends up in a
            // hole. Polling the position through a run shows where the time actually goes --
            // 75 of 120 seconds oscillating at y=-62/-63 inside its own excavation, four blocks
            // mined -- and the same pit is what it later climbs out of onto the arena wall, which
            // is the other 35-45% of runs. One cause, both failure modes.
            //
            // So the rule is about the SURFACE, not about a block: while enough candidates remain
            // at or above standing height, do not choose one below it. Descending still works when
            // the surface runs out, which is what mine_diamond needs and why it stays green.
            int feetY = mod.getPlayer() == null ? Integer.MIN_VALUE : mod.getPlayer().getBlockPos().getY();
            if (kaptainwutax.tungsten.TungstenConfig.get().mineStayOnSurface
                    && feetY != Integer.MIN_VALUE) {
                // !! THE RULE ABOVE RATCHETED, WHICH IS WHY IT MEASURED NOTHING.
                //
                // `check.getY() < feetY - 1` is relative to where the bot is STANDING, and the
                // floor is always feetY-1 -- so the block under its own feet always passes. Break
                // it, fall one, and the test re-anchors one level lower and passes the next one
                // too. The guard descends WITH the bot, one level per swing, exactly as if it were
                // not there. Traced three times: 0,-61,0 at t=0, y=-62 at 4.4 s, y=-63 at 6.6 s,
                // then `BFS stuck at 0,-63,0` with all eight neighbours feetBlocked=stone.
                //
                // What that costs is not the four blocks. It is that a bot at the bottom of a 1x1
                // shaft has NO lateral move -- the only direction the search can expand is up --
                // and it has just mined the blocks that make pillaring affordable. So it towers out
                // to y=-55, six blocks above the floor, spending the whole haul, and the run ends
                // with the bot standing on a cobblestone column with an empty pack. Every failure
                // mode this course has follows from the shaft.
                //
                // THE FIX IS TO ASK WHETHER WE ARE IN A HOLE, not how deep we have got. Standing on
                // open ground, digging down is ordinary and stays allowed -- mine_diamond needs it.
                // Standing in a pit, with solid ground on every side at our own feet level, digging
                // down deepens a shaft we already cannot walk out of, and the blocks at our feet
                // level are its walls: mine one of those instead and we can step out. Stateless, so
                // it releases the moment the bot is not enclosed, and it cannot ratchet because it
                // is a question about the WORLD rather than about how far we have fallen.
                int limit = enclosedAtFeet(mod, feetY) ? feetY : feetY - 1;
                Optional<BlockPos> onSurface = mod.getBlockScanner().getNearestBlock(pos, check -> {
                    if (check.getY() < limit) {
                        scanBelowFeet++;
                        return false;
                    }
                    if (mod.getBlockScanner().isUnreachable(check)) return false;
                    if (!WorldHelper.canBreak(check)) return false;
                    return true;
                }, blocks);
                if (onSurface.isPresent()) {
                    return new Pair<>(BlockPosVer.getSquaredDistance(onSurface.get(), pos), onSurface);
                }
            }
            BlockPos underfoot = mod.getPlayer() == null ? null : mod.getPlayer().getBlockPos().down();
            if (kaptainwutax.tungsten.TungstenConfig.get().mineAvoidUnderfoot && underfoot != null) {
                Optional<BlockPos> preferred = mod.getBlockScanner().getNearestBlock(pos, check -> {
                    if (check.equals(underfoot)) {
                        scanUnderfoot++;
                        return false;
                    }
                    if (mod.getBlockScanner().isUnreachable(check)) return false;
                    if (!WorldHelper.canBreak(check)) return false;
                    return true;
                }, blocks);
                if (preferred.isPresent()) {
                    return new Pair<>(BlockPosVer.getSquaredDistance(preferred.get(), pos), preferred);
                }
            }
            Optional<BlockPos> closestBlock = mod.getBlockScanner().getNearestBlock(pos, check -> {

                // WHY IS THERE NOTHING TO MINE? MEASURE IT, DO NOT GUESS AGAIN.
                // Four fixes to the wander machinery all measured an identical course score,
                // because the wander is healthy and the real question is upstream: the parent
                // re-asks for a wander only because this search comes back EMPTY. These three say
                // whether candidates existed and were DISCARDED here, and by which of the two
                // filters -- if the reachable trunk is being rejected as unreachable alongside the
                // bait, no reset anywhere else can help, because that set lives in the scanner.
                if (mod.getBlockScanner().isUnreachable(check)) {
                    scanUnreachable++;
                    return false;
                }
                if (!WorldHelper.canBreak(check)) {
                    scanNoBreak++;
                    return false;
                }
                scanAccepted++;
                return true;
            }, blocks);

            return new Pair<>(
                    closestBlock.map(blockPos -> BlockPosVer.getSquaredDistance(blockPos, pos)).orElse(Double.POSITIVE_INFINITY),
                    closestBlock
            );
        }

        @Override
        protected Vec3d getOriginPos(AltoClef mod) {
            return mod.getPlayer().getPos();
        }

        @Override
        protected Task onTick() {
            AltoClef mod = AltoClef.getInstance();

            // A SEARCH IS NOT PROGRESS -- the identical defect as in PickupDroppedItemTask, and the
            // identical two lines. While the pathfinder merely LOOKS, isPathing() is true, so this
            // reset fires every tick and the "failed to mine, suggest unreachable, blacklist, pick
            // another block" branch below can never be reached. See Nav.isExecutingRoute.
            if (kaptainwutax.tungsten.TungstenConfig.get().progressCheckIgnoresSearch
                    ? Nav.isExecutingRoute() : Nav.isPathing()) {
                progressChecker.reset();
            }
            if (miningPos != null && !progressChecker.check(mod)) {
                Nav.cancel();
                Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
                mod.getBlockScanner().requestBlockUnreachable(miningPos, 2);
                blacklist.add(miningPos);
                miningPos = null;
                progressChecker.reset();
            }
            return super.onTick();
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (miningPos == null || !miningPos.equals(newPos)) {
                    progressChecker.reset();
                }
                miningPos = newPos;
                return new DestroyBlockTask(miningPos);
            }
            if (obj instanceof ItemEntity) {
                miningPos = null;
                return _pickupTask;
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected boolean isValid(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return mod.getBlockScanner().isBlockAtPosition(b, _blocks) && WorldHelper.canBreak(b);
            }
            if (obj instanceof ItemEntity drop) {
                Item item = drop.getStack().getItem();
                if (_targets != null) {
                    for (ItemTarget target : _targets) {
                        if (target.matches(item)) return true;
                    }
                }
                return false;
            }
            return false;
        }

        @Override
        protected void onStart() {
            progressChecker.reset();
            miningPos = null;
        }

        @Override
        protected void onStop(Task interruptTask) {

        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof MineOrCollectTask task) {
                return Arrays.equals(task._blocks, _blocks) && Arrays.equals(task._targets, _targets);
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Mining or Collecting";
        }

        public boolean isMining() {
            return miningPos != null;
        }

        public BlockPos miningPos() {
            return miningPos;
        }
    }

}
