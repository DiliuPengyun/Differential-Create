package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/** Ordinary powered controllers, native tool interactions, and automatic feed.
 * No harness writes a body pose, a breaking callback, or an accepted hand stroke. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MovingDeployerTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (boolean piston : new boolean[]{false, true}) for (boolean hard : new boolean[]{false, true})
            for (int speed : new int[]{32, 64}) {
                String name = "hand_" + (piston ? "piston_" : "bearing_") + (hard ? "obsidian_" : "stone_") + speed;
                if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                    cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 6000, 0, true,
                        h -> mining(h, piston, hard, speed)));
            }
        for (String special : new String[]{"lifecycle", "plant", "power_loss", "gantry", "body_reload", "fist"}) {
            String name = "hand_" + special;
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 6000, 0, true,
                    h -> special(h, special)));
        }
        for (String kind : new String[]{"plant", "place", "hoe", "heavy", "unpowered", "overspeed", "mixed", "reload", "reload_legacy", "mode_change", "attack"}) {
            String name = "hand_use_" + kind;
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                cases.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 1200, 0, true,
                    h -> use(h, kind)));
        }
        return cases;
    }

    private static void use(GameTestHelper h, String kind) {
        boolean mixed = kind.equals("mixed"), reload = kind.startsWith("reload"), unpowered = kind.equals("unpowered"),
            overspeed = kind.equals("overspeed"), modeChange = kind.equals("mode_change"), attack = kind.equals("attack"),
            legacy = kind.equals("reload_legacy"), heavy = kind.equals("heavy");
        setup(h, !reload, unpowered || overspeed || reload || modeChange ? 1 : 32);
        var anchor = new BlockPos(8, 8, 12).east();
        if (!reload) {
            if (!mixed) h.setBlock(anchor.above(2), Blocks.AIR);
            var handPos = mixed ? anchor.above().north() : anchor.above();
            h.setBlock(handPos, AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
            var tool = h.getBlockEntity(handPos); var tag = tool.saveWithFullMetadata(h.getLevel().registryAccess());
            // The fixture's protective barrier exists until targets are laid
            // out. Keep use disabled during assembly, as a player configures
            // the tool and material before starting its first working pass.
            tag.putString("Mode", "PUNCH"); tool.loadWithComponents(tag, h.getLevel().registryAccess());
            if (heavy) {
                h.setBlock(anchor.east(), Blocks.SLIME_BLOCK); h.setBlock(anchor.east(2), Blocks.SLIME_BLOCK);
                h.setBlock(anchor.east(2).above(), Blocks.SLIME_BLOCK);
                for (int x = 2; x <= 12; x++) for (int y = 2; y <= 12; y++) for (int z = -5; z <= 5; z++)
                    h.setBlock(anchor.offset(x, y, z), Blocks.SLIME_BLOCK);
            }
        }
        h.runAfterDelay(5, () -> {
            if (reload) ((MechanicalBearingBlockEntity) h.getBlockEntity(new BlockPos(8, 8, 12))).assemble();
            // Let the heavy payload approach naturally before configuring the
            // first full contact, rather than presenting a target just as the
            // tool is already leaving its current voxel.
            h.runAfterDelay(heavy ? 8 : 4, () -> {
                var f = fixture(h, !reload);
                var pair = f.root.getContraption().getActors().stream().filter(a -> MovingDeployerWork.isHand(a.getRight())
                    && (reload || a.getRight().state.getValue(BlockStateProperties.FACING) == Direction.DOWN)).findFirst().orElseThrow();
                var ctx = pair.getRight(); ctx.blockEntityData.putString("Mode", attack ? "PUNCH" : "USE");
                ItemStack held = reload ? ItemStack.EMPTY : new ItemStack(kind.equals("place") ? Items.COBBLESTONE
                    : kind.equals("hoe") ? Items.DIAMOND_HOE : modeChange ? Items.DIAMOND_PICKAXE : attack ? Items.DIAMOND_SWORD : Items.WHEAT_SEEDS,
                    kind.equals("hoe") || modeChange || attack ? 1 : 8);
                ContraptionActorWork.of(ctx).hand().player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
                var behaviour = com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(ctx.state);
                var target = BlockPos.containing(f.root.toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(behaviour.getActiveAreaOffset(ctx)), 1));
                var targets = new ArrayList<BlockPos>();
                for (int i = 0; i < (mixed || reload || unpowered || overspeed || modeChange || attack ? 1 : 5); i++) {
                    var p = target.east(i); targets.add(p);
                    h.getLevel().setBlockAndUpdate(p, (reload ? Blocks.NOTE_BLOCK : modeChange ? Blocks.STONE
                        : kind.equals("hoe") ? Blocks.DIRT : Blocks.AIR).defaultBlockState());
                    var soil = (kind.equals("place") || kind.equals("hoe") || reload ? Blocks.DIRT : Blocks.FARMLAND).defaultBlockState();
                    if (soil.hasProperty(BlockStateProperties.MOISTURE)) soil = soil.setValue(BlockStateProperties.MOISTURE, 7);
                    h.getLevel().setBlockAndUpdate(p.below(), soil);
                }
                var victim = attack ? net.minecraft.world.entity.EntityType.COW.create(h.getLevel()) : null;
                if (victim != null) {
                    victim.setPos(Vec3.atCenterOf(target)); victim.setNoAi(true); victim.setNoGravity(true); h.getLevel().addFreshEntity(victim);
                }
                if (legacy) {
                    // Use the old firmware's long use stroke from the start,
                    // paid by the real motor before serializing an old save.
                    var hand = ContraptionActorWork.of(ctx).hand(); hand.prepare();
                    ctx.data.getCompound(MovingDeployerWork.TAG).putDouble("Stroke", MovingDeployerWork.STROKE);
                }
                MovingDeployerWork miner = null; BlockPos miningTarget = null;
                if (mixed) {
                    var punch = f.root.getContraption().getActors().stream().map(a -> a.getRight()).filter(c -> MovingDeployerWork.isHand(c)
                        && c.state.getValue(BlockStateProperties.FACING) == Direction.EAST).findFirst().orElseThrow();
                    punch.blockEntityData.putString("Mode", "PUNCH"); miner = ContraptionActorWork.of(punch).hand();
                    miner.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                    miningTarget = BlockPos.containing(f.root.toGlobalVector(Vec3.atCenterOf(punch.localPos).add(2, 0, 0), 1));
                    h.getLevel().setBlockAndUpdate(miningTarget, Blocks.OBSIDIAN.defaultBlockState());
                }
                var feed = ActorFeed.of(f.driver); ActorFeed.enabled(f.driver);
                var start = f.root.position();
                double initialHeat = feed.brakeHeat;
                if (heavy) {
                    // A single coherent initial running state represents an
                    // already-moving payload. Subsequent work is ordinary
                    // native ticking, with no repeated velocity assignment.
                    var network = (INetworkDynamics) f.driver.getOrCreateNetwork();
                    network.differentialcreate$setSpeed(-64 / network.differentialcreate$getRatios().getOrDefault(f.driver, 1f));
                    network.differentialcreate$data().connections.forEach(edge -> edge.twist = 0);
                    feed.speed = NetworkDynamics.preciseSpeed(f.driver);
                    h.assertTrue(Math.abs(feed.speed) > 30 && feed.inertia > .1, "the heavy planter starts with real output momentum");
                }
                if (unpowered) {
                    f.motor.generatedSpeed.setValue(0);
                    var network = (INetworkDynamics) f.driver.getOrCreateNetwork(); network.differentialcreate$setSpeed(0);
                    network.differentialcreate$data().connections.forEach(edge -> edge.twist = 0);
                }
                // A single initial output momentum is a real stored-energy
                // condition, not a repeated harness motor or a free hand hit.
                if (unpowered || overspeed) feed.speed = overspeed ? -512 : -32;
                var miningHand = miner; var rock = miningTarget;
                class Loop {
                    MovementContext current = ctx;
                    int ticks, hitsAt, returns; boolean restored, usedInMotion, plantedWhileMining, switched, miningChecked, usedAgain;
                    void tick() {
                        ticks++; var hand = ContraptionActorWork.of(current).hand(); var data = current.data.getCompound(MovingDeployerWork.TAG);
                        h.assertFalse(current.stall || f.root.isStalled(), "ordinary use never invokes native whole-body pause");
                        if (heavy) h.assertTrue(feed.brakeHeat == initialHeat,
                            "a heavy planter that can finish before leaving contact does not brake just because it is heavy: tick=" + ticks
                                + ", heat=" + feed.brakeHeat + ", input=" + NetworkDynamics.preciseSpeed(f.driver) + ", output=" + feed.speed
                                + ", position=" + f.root.position() + ", start=" + start + ", hand=" + current.data);
                        if (attack) {
                            h.assertFalse(hand.holding() || feed.brake > 0, "a quick entity attack does not request mining dwell");
                            if (victim.getHealth() < victim.getMaxHealth() && !hand.pending()) {
                                h.assertTrue(Math.abs(feed.speed) > ActorFeed.SETTLED && data.getInt("Hits") == 1,
                                    "a moving hand executes exactly one native entity attack at its real contact");
                                h.assertTrue(data.getDouble("Work") >= 4 * 2 * MovingDeployerWork.USE_STROKE / 1200d - 1e-7,
                                    "the quick attack and retraction still consume shaft work");
                                victim.discard(); f.root.discard(); h.succeed(); return;
                            }
                        }
                        if (modeChange && !switched && hand.phase() == 1 && data.getDouble("Travel") > 0) {
                            h.assertTrue(data.getInt("Hits") == 0, "mode switches before the short use action executes");
                            current.blockEntityData.putString("Mode", "PUNCH"); f.motor.generatedSpeed.setValue(-32); switched = true;
                        }
                        if (modeChange && switched && !miningChecked && data.getInt("Hits") > 0) {
                            h.assertTrue(data.getDouble("Stroke") == MovingDeployerWork.STROKE
                                && data.getDouble("Work") >= 4 * MovingDeployerWork.STROKE / 1200d - 1e-7,
                                "switching modes retires the short use stroke and pays a full mining stroke");
                            h.assertTrue(data.getFloat("Breaking") > 0 || h.getLevel().getBlockState(target).isAir(), "the newly paid mining stroke performs a native hit");
                            miningChecked = true;
                        }
                        if (modeChange && miningChecked && !usedAgain && !hand.pending() && h.getLevel().getBlockState(target).isAir()) {
                            h.assertTrue(data.getBoolean("Visited"), "the completed mining contact has a completion record");
                            current.blockEntityData.putString("Mode", "USE");
                            hand.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE));
                            usedAgain = true;
                        }
                        if (modeChange && usedAgain && !hand.pending() && h.getLevel().getBlockState(target).is(Blocks.COBBLESTONE)) {
                            h.assertTrue(hand.player().getMainHandItem().isEmpty(), "an idle mode switch rechecks the same contact and consumes the new material once");
                            f.root.discard(); h.succeed(); return;
                        }
                        if (unpowered && ticks <= 25) {
                            h.assertTrue(h.getLevel().getBlockState(target).isAir() && data.getInt("Hits") == 0,
                                "coasting without input power cannot create a planting action");
                            if (ticks == 25) {
                                h.assertTrue(feed.brakeHeat > 0 && Math.abs(feed.speed) < ActorFeed.SETTLED,
                                    "a powerless contact uses a finite physical brake and dissipates the coasting energy");
                                f.motor.generatedSpeed.setValue(-32);
                            }
                        }
                        if (overspeed && !hand.failure().isEmpty()) {
                            h.assertTrue(h.getLevel().getBlockState(target).isAir() && hand.player().getMainHandItem().getCount() == 8,
                                "a contact lost before the paid action cannot plant at a stale position");
                            h.assertTrue(f.root.position().distanceTo(start) > .25, "finite brakes do not teleport an overspeed carrier to rest");
                            f.root.discard(); h.succeed(); return;
                        }
                        if (data.getInt("Hits") > 0 && hitsAt == 0) {
                            hitsAt = ticks; usedInMotion = Math.abs(feed.speed) > ActorFeed.SETTLED;
                            if (mixed) plantedWhileMining = miningHand.holding() && !h.getLevel().getBlockState(rock).isAir();
                        }
                        if (hand.phase() == 2 && !mixed && !unpowered && !overspeed && !modeChange) {
                            returns++;
                            h.assertFalse(hand.holding() || feed.brake > 0, "ordinary retraction leaves the carrier transmission engaged");
                        }
                        if (reload && hand.phase() == 2 && !restored) {
                            double pose = hand.extension(); behaviour.writeExtraData(current);
                            var saved = current.writeToNBT(new net.minecraft.nbt.CompoundTag()); hand.player().discard();
                            if (legacy) {
                                var old = saved.getCompound("Data").getCompound(MovingDeployerWork.TAG);
                                old.remove("Stroke"); old.remove("Completed"); old.remove("Mode"); old.putString("Failure", "no_power");
                            }
                            current = MovementContext.readNBT(h.getLevel(), pair.getLeft(), saved, f.root.getContraption()); pair.setRight(current);
                            h.assertTrue(Math.abs(ContraptionActorWork.of(current).hand().extension() - pose) < 1e-10,
                                "reload preserves a moving-use retraction and the completed action");
                            restored = true;
                        }
                        boolean done = targets.stream().allMatch(p -> h.getLevel().getBlockState(p).is(reload ? Blocks.NOTE_BLOCK
                            : kind.equals("hoe") ? Blocks.FARMLAND : kind.equals("place") ? Blocks.COBBLESTONE : Blocks.WHEAT));
                        if (done && !hand.pending() && (!reload || restored && ticks > hitsAt + 80)
                            && (!mixed || h.getLevel().getBlockState(rock).isAir() && !miningHand.pending() && Math.abs(feed.speed) > ActorFeed.SETTLED)) {
                            if (!mixed && !unpowered) h.assertTrue(usedInMotion && returns > 0, "normal use executes and retracts during real body motion");
                            if (mixed) h.assertTrue(plantedWhileMining, "the planter can finish while the miner keeps its own sustained contact");
                            h.assertTrue(data.getInt("Hits") == targets.size(), "each real contact is activated exactly once: " + current.data);
                            if (!reload && !kind.equals("hoe")) h.assertTrue(hand.player().getMainHandItem().getCount() == 8 - targets.size(), "native use consumes the exact materials");
                            if (kind.equals("hoe")) h.assertTrue(hand.player().getMainHandItem().getDamageValue() == 5, "native use wears the real hoe once per block");
                            if (reload) h.assertTrue(h.getLevel().getBlockState(target).getValue(BlockStateProperties.NOTE) == 1,
                                "a stationary target is not clicked again after reload or retraction");
                            h.assertTrue(data.getDouble("Work") >= targets.size() * 4 * 2 * MovingDeployerWork.USE_STROKE / 1200d - 1e-7,
                                "moving use and retraction pay configured shaft work");
                            f.root.discard(); h.succeed(); return;
                        }
                        if (ticks > 1100) h.fail("moving use " + kind + " failed: data=" + current.data + ", feed=" + feed.speed
                            + ", root=" + f.root.position() + ", target=" + targets.stream().map(p -> h.getLevel().getBlockState(p)).toList());
                        h.runAfterDelay(1, this::tick);
                    }
                }
                h.runAfterDelay(1, new Loop()::tick);
            });
        });
    }
    private record Fixture(KineticBlockEntity driver, CreativeMotorBlockEntity motor, AbstractContraptionEntity root, MovementContext ctx, BlockPos target) {}
    private static void setup(GameTestHelper h, boolean piston, int speed) {
        var base = new BlockPos(8, 8, 12);
        h.setBlock(base, (piston ? AllBlocks.MECHANICAL_PISTON : AllBlocks.MECHANICAL_BEARING).getDefaultState()
            .setValue(BlockStateProperties.FACING, piston ? Direction.EAST : Direction.UP));
        h.setBlock(base.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        CreativeMotorBlockEntity motor = h.getBlockEntity(base.below()); motor.generatedSpeed.setValue(piston ? -speed : speed);
        if (piston) for (int i = 1; i <= 8; i++) h.setBlock(base.west(i), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        var anchor = piston ? base.east() : base.above();
        h.setBlock(anchor, Blocks.SLIME_BLOCK);
        h.setBlock(anchor.above(), Blocks.SLIME_BLOCK);
        h.setBlock(anchor.above(2), AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, piston ? Direction.EAST : Direction.UP));
        var tool = h.getBlockEntity(anchor.above(2)); var tag = tool.saveWithFullMetadata(h.getLevel().registryAccess());
        tag.putString("Mode", "PUNCH"); tool.loadWithComponents(tag, h.getLevel().registryAccess());
        h.setBlock(anchor.south(), Blocks.CHEST);
    }
    private static Fixture fixture(GameTestHelper h, boolean piston) {
        var base = new BlockPos(8, 8, 12); KineticBlockEntity driver = h.getBlockEntity(base);
        var root = piston ? ((LinearActuatorBlockEntity) driver).movedContraption : ((MechanicalBearingBlockEntity) driver).getMovedContraption();
                h.assertTrue(root != null, "ordinary driver assembles the mounted hand: state=" + driver.getBlockState() + ", speed=" + driver.getSpeed() + ", tag=" + driver.saveWithFullMetadata(h.getLevel().registryAccess()));
        var ctx = root.getContraption().getActors().stream().filter(a -> MovingDeployerWork.isHand(a.getRight())).findFirst().orElseThrow().getRight();
        ctx.blockEntityData.putString("Mode", "PUNCH");
        ContraptionActorWork.of(ctx).hand().player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        var offset = Vec3.atLowerCornerOf(ctx.state.getValue(BlockStateProperties.FACING).getNormal()).scale(2);
        var target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(offset), 1));
        return new Fixture(driver, h.getBlockEntity(base.below()), root, ctx, target);
    }
    private static void mining(GameTestHelper h, boolean piston, boolean hard, int speed) {
        setup(h, piston, speed);
        h.runAfterDelay(5, () -> {
            if (!piston) ((MechanicalBearingBlockEntity) h.getBlockEntity(new BlockPos(8, 8, 12))).assemble();
            h.runAfterDelay(4, () -> {
                var f = fixture(h, piston); h.getLevel().setBlockAndUpdate(f.target, (hard ? Blocks.OBSIDIAN : Blocks.STONE).defaultBlockState());
                class Loop {
                    int ticks, parked, hits; double heldAngle; boolean resumed;
                    void tick() {
                        ticks++; var hand = ContraptionActorWork.of(f.ctx).hand(); var feed = ActorFeed.of(f.driver);
                        h.assertFalse(f.ctx.stall || f.root.isStalled() || f.root.getContraption().stalled, "paid hand never invokes a native whole-body stall");
                        h.assertTrue(Double.isFinite(feed.speed) && Math.abs(feed.speed) < 10000, "feed has a finite physical velocity");
                        hits = f.ctx.data.getCompound(MovingDeployerWork.TAG).getInt("Hits");
                        if (hand.holding() && Math.abs(feed.speed) < ActorFeed.SETTLED) {
                            parked++; h.assertTrue(Math.abs(NetworkDynamics.preciseSpeed(f.driver)) > 1, "input turns while the real feed is parked");
                            heldAngle = feed.angle;
                        }
                        if (h.getLevel().getBlockState(f.target).isAir() && !hand.pending()) {
                            if (Math.abs(feed.angle - heldAngle) > .001) resumed = true;
                            if (resumed) {
                                h.assertTrue(hits > 0 && parked > 0, "native mining receives powered hits after physical parking");
                                h.assertTrue(f.ctx.data.getCompound(MovingDeployerWork.TAG).getDouble("Work") >= hits * 4 * 1000 / 1200d - 1e-5,
                                    "every finished hit and retraction paid the configured hand work");
                                System.out.println("HAND RESULT piston=" + piston + " hard=" + hard + " rpm=" + speed + " ticks=" + ticks + " hits=" + hits);
                                f.root.discard(); h.succeed(); return;
                            }
                        }
                        if (ticks > 5800) h.fail("hand did not complete/resume: data=" + f.ctx.data + ", feed=" + feed.speed + ", input=" + f.driver.getSpeed());
                        h.runAfterDelay(1, this::tick);
                    }
                }
                h.runAfterDelay(1, new Loop()::tick);
            });
        });
    }

    static void paidLifecycle(GameTestHelper h, MechanicalBearingBlockEntity shaft, org.apache.commons.lang3.tuple.MutablePair<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo, MovementContext> pair) {
        var root = shaft.getMovedContraption(); var ctx = pair.getRight();
        ctx.blockEntityData.putString("Mode", "PUNCH");
        ContraptionActorWork.of(ctx).hand().player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        ActorFeed.enabled(shaft); var feed = ActorFeed.of(shaft); feed.speed = 0;
        for (double[] schedule : new double[][]{{16}, {512}, {0, 32, -64, 128}}) {
            feed.speed = 0;
            var resetNetwork = (INetworkDynamics) shaft.getOrCreateNetwork();
            resetNetwork.differentialcreate$setSpeed(0);
            resetNetwork.differentialcreate$data().connections.forEach(edge -> edge.twist = 0);
            var target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(ctx.state).getActiveAreaOffset(ctx)), 1));
            h.getLevel().setBlockAndUpdate(target, Blocks.GLASS.defaultBlockState());
            var hand = ContraptionActorWork.of(ctx).hand(); hand.prepare();
            double before = hand.remaining();
            for (int i = 0; i < 25; i++) {
                var network = (INetworkDynamics) shaft.getOrCreateNetwork(); network.differentialcreate$setSpeed(0);
                root.tickActors(); MachiningClock.advance(h.getLevel()); shaft.tick();
            }
            h.assertTrue(hand.pending() && hand.remaining() == before && h.getLevel().getBlockState(target).is(Blocks.GLASS),
                "zero input and feed cannot execute or pay a native hand action: before=" + before + ", remaining=" + hand.remaining() + ", data=" + ctx.data);
            ((INetworkDynamics) shaft.getOrCreateNetwork()).differentialcreate$setSpeed(16);
            MachiningClock.advance(h.getLevel()); shaft.tick();
            double fractional = hand.remaining();
            com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(ctx.state).writeExtraData(ctx);
            var saved = ctx.writeToNBT(new net.minecraft.nbt.CompoundTag());
            hand.player().discard();
            ctx = MovementContext.readNBT(h.getLevel(), pair.getLeft(), saved, root.getContraption()); pair.setRight(ctx);
            hand = ContraptionActorWork.of(ctx).hand();
            h.assertTrue(Math.abs(fractional - hand.remaining()) < 1e-8, "reload preserves the exact fractional powered hand stroke");
            int initialHits = ctx.data.getCompound(MovingDeployerWork.TAG).getInt("Hits"), step = 0;
            double initialWork = ctx.data.getCompound(MovingDeployerWork.TAG).getDouble("Work");
            while (hand.pending() && step < 2000) {
                ((INetworkDynamics) shaft.getOrCreateNetwork()).differentialcreate$setSpeed(schedule[step++ % schedule.length]);
                root.tickActors(); MachiningClock.advance(h.getLevel()); shaft.tick();
                h.assertFalse(ctx.stall || root.isStalled(), "paid repeating hits do not stall the whole structure");
            }
            h.assertTrue(step < 2000 && h.getLevel().getBlockState(target).isAir(), "powered native glass mining completes under stop/reverse RPM schedules");
            int hits = ctx.data.getCompound(MovingDeployerWork.TAG).getInt("Hits") - initialHits;
            double paid = ctx.data.getCompound(MovingDeployerWork.TAG).getDouble("Work");
            h.assertTrue(hits > 0 && paid >= initialWork + hits * 1000 * 4 / 1200d - 1, "every repeated hand hit consumes positive configured work");
        }
        var hand = ContraptionActorWork.of(ctx).hand(); var target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(ctx.state).getActiveAreaOffset(ctx)), 1));
        h.getLevel().setBlockAndUpdate(target, Blocks.OBSIDIAN.defaultBlockState()); hand.prepare();
        ((INetworkDynamics) shaft.getOrCreateNetwork()).differentialcreate$setSpeed(64); MachiningClock.advance(h.getLevel()); shaft.tick();
        for (int i = 0; i < 8 && hand.extension() == 0; i++) {
            ((INetworkDynamics) shaft.getOrCreateNetwork()).differentialcreate$setSpeed(64);
            root.tickActors(); MachiningClock.advance(h.getLevel()); shaft.tick();
        }
        h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState()); h.getLevel().setBlockAndUpdate(target, Blocks.OBSIDIAN.defaultBlockState());
        hand.prepare();
        h.assertTrue(hand.phase() != 1 && hand.failure().equals("changed_target"), "remove-and-replace of an identical block retires the old extension");
        h.assertTrue(ctx.data.getCompound(MovingDeployerWork.TAG).getFloat("Breaking") == 0, "a replaced target cannot inherit breaking progress");
        ctx.disabled = true; hand.cancel("disabled"); ActorFeed.prepare(shaft);
        h.assertTrue(feed.brake == 0 && feed.coupling > 0, "disabling an unfinished hand immediately releases its finite feed brake");
    }

    private static void special(GameTestHelper h, String kind) {
        if (kind.equals("gantry")) { gantry(h); return; }
        if (kind.equals("body_reload")) { bodyReload(h); return; }
        boolean piston = kind.equals("plant");
        setup(h, piston, kind.equals("lifecycle") ? 0 : 32);
        if (piston) {
            var anchor = new BlockPos(8, 8, 12).east();
            h.setBlock(anchor.above(2), Blocks.AIR);
            h.setBlock(anchor.above(), AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
            var tool = h.getBlockEntity(anchor.above()); var initial = tool.saveWithFullMetadata(h.getLevel().registryAccess());
            initial.putString("Mode", "PUNCH"); tool.loadWithComponents(initial, h.getLevel().registryAccess());
        }
        h.runAfterDelay(5, () -> {
            if (!piston) ((MechanicalBearingBlockEntity) h.getBlockEntity(new BlockPos(8, 8, 12))).assemble();
            h.runAfterDelay(4, () -> {
                var f = fixture(h, piston);
                if (kind.equals("lifecycle")) {
                    var pair = f.root.getContraption().getActors().stream().filter(a -> a.getRight() == f.ctx).findFirst().orElseThrow();
                    paidLifecycle(h, (MechanicalBearingBlockEntity) f.driver, pair);
                    f.root.discard(); h.succeed(); return;
                }
                if (kind.equals("fist")) {
                    ContraptionActorWork.of(f.ctx).hand().player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    h.getLevel().setBlockAndUpdate(f.target, Blocks.GLASS.defaultBlockState());
                } else if (piston) {
                    f.ctx.blockEntityData.putString("Mode", "USE");
                    ContraptionActorWork.of(f.ctx).hand().player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS, 8));
                    h.getLevel().setBlockAndUpdate(f.target, Blocks.AIR.defaultBlockState());
                    h.getLevel().setBlockAndUpdate(f.target.below(), Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
                } else h.getLevel().setBlockAndUpdate(f.target, Blocks.OBSIDIAN.defaultBlockState());
                class Loop {
                    int ticks, stoppedAt; double stoppedTravel; boolean observed, restarted;
                    void tick() {
                        ticks++; var hand = ContraptionActorWork.of(f.ctx).hand();
                        h.assertFalse(f.ctx.stall || f.root.isStalled(), "simple hand work does not freeze the machine");
                        if (kind.equals("power_loss") && ticks == 20) f.motor.generatedSpeed.setValue(0);
                        if (kind.equals("power_loss") && ticks > 20 && !observed && Math.abs(NetworkDynamics.preciseSpeed(f.driver)) < 1e-8) {
                            h.assertTrue(hand.pending(), "unpowered obsidian work exhausts real shaft energy before completion");
                            stoppedTravel = f.ctx.data.getCompound(MovingDeployerWork.TAG).getDouble("Travel"); observed = true; stoppedAt = ticks;
                        }
                        if (kind.equals("power_loss") && observed && ticks == stoppedAt + 40) {
                            h.assertTrue(observed && stoppedTravel == f.ctx.data.getCompound(MovingDeployerWork.TAG).getDouble("Travel"), "a powerless hand retains its paid pose without free tick work");
                            f.motor.generatedSpeed.setValue(32); restarted = true;
                        }
                        boolean done = kind.equals("fist") ? h.getLevel().getBlockState(f.target).is(Blocks.GLASS)
                            && f.ctx.data.getCompound(MovingDeployerWork.TAG).getInt("Hits") == 1
                            : piston ? h.getLevel().getBlockState(f.target).is(Blocks.WHEAT) : h.getLevel().getBlockState(f.target).isAir() && restarted;
                        if (done && !hand.pending()) {
                            h.assertTrue(f.ctx.data.getCompound(MovingDeployerWork.TAG).getDouble("Work") > 0, "native planting/mining consumed shaft work");
                            f.root.discard(); h.succeed(); return;
                        }
                        if (ticks > 5800) h.fail("simple " + kind + " did not finish: " + f.ctx.data);
                        h.runAfterDelay(1, this::tick);
                    }
                }
                h.runAfterDelay(1, new Loop()::tick);
            });
        });
    }
    private static void gantry(GameTestHelper h) {
        var base = new BlockPos(8, 7, 12);
        for (int i = -2; i <= 8; i++) {
            h.setBlock(base.east(i), AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(com.simibubi.create.content.kinetics.gantry.GantryShaftBlock.FACING, Direction.EAST));
            for (int y = 1; y <= 4; y++) h.setBlock(base.east(i).above(y), Blocks.AIR);
        }
        h.setBlock(base.west(3), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        CreativeMotorBlockEntity motor = h.getBlockEntity(base.west(3)); motor.generatedSpeed.setValue(-32);
        h.setBlock(base.above(2), Blocks.SLIME_BLOCK);
        h.setBlock(base.above(3), AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.runAfterDelay(5, () -> {
            h.setBlock(base.above(), AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
            com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity carriage = h.getBlockEntity(base.above()); carriage.queueAssembly(); carriage.tick();
            h.runAfterDelay(4, () -> {
                var root = java.util.stream.StreamSupport.stream(h.getLevel().getAllEntities().spliterator(), false)
                    .filter(e -> e instanceof com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity)
                    .map(e -> (com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity) e)
                    .filter(e -> e.getAnchorVec().distanceTo(Vec3.atLowerCornerOf(h.absolutePos(base.above()))) < 2).findFirst().orElseThrow();
                var ctx = root.getContraption().getActors().stream().filter(a -> MovingDeployerWork.isHand(a.getRight())).findFirst().orElseThrow().getRight();
                ctx.blockEntityData.putString("Mode", "PUNCH");
                var hand = ContraptionActorWork.of(ctx).hand(); hand.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                var target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(2, 0, 0), 1));
                h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                h.getLevel().setBlockAndUpdate(target.east(), Blocks.STONE.defaultBlockState());
                double initialX = root.getX();
                class Loop {
                    int ticks; boolean parked;
                    void tick() {
                        ticks++;
                        h.assertFalse(root.isRemoved() || root.isStalled() || ctx.stall, "a physically stopped gantry hand stays assembled without native stall");
                        var shaft = ContraptionPhysics.gantryShaft(root);
                        h.assertTrue(shaft != null, "gantry retains a live physical support");
                        parked |= hand.holding() && Math.abs(ActorFeed.of(shaft).speed) < ActorFeed.SETTLED;
                        if (h.getLevel().getBlockState(target).isAir() && h.getLevel().getBlockState(target.east()).isAir()
                            && !hand.pending() && root.getX() > initialX + .5) {
                            h.assertTrue(parked && ctx.data.getCompound(MovingDeployerWork.TAG).getInt("Hits") >= 4,
                                "ordinary gantry automatically parks, works, changes support and continues to a new target");
                            root.discard(); h.succeed(); return;
                        }
                        if (ticks > 500) h.fail("gantry did not finish after physical handover: " + ctx.data + ", pos=" + root.position());
                        h.runAfterDelay(1, this::tick);
                    }
                }
                h.runAfterDelay(1, new Loop()::tick);
            });
        });
    }

    private static void bodyReload(GameTestHelper h) {
        var pos = new BlockPos(8, 10, 8);
        h.setBlock(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(pos.above(), Blocks.SLIME_BLOCK);
        h.setBlock(pos.above(2), AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        for (var side : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH}) {
            h.setBlock(pos.above().relative(side), Blocks.SLIME_BLOCK);
            h.setBlock(pos.above().relative(side, 2), AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
            h.setBlock(pos.above(2).relative(side, 2), Blocks.OAK_PLANKS);
        }
        h.runAfterDelay(5, () -> {
            MechanicalBearingBlockEntity original = h.getBlockEntity(pos); original.assemble(); NetworkTopology.flush();
            var root = original.getMovedContraption(); root.tick();
            var children = ContraptionPhysics.drives(original).stream().filter(ContraptionPhysics.Drive::stabilized).toList();
            h.assertTrue(children.size() == 3, "the hand shares a real assembly with three stabilized payloads");
            ActorFeed.enabled(original); var feed = ActorFeed.of(original); feed.speed = 7;
            ((INetworkDynamics) original.getOrCreateNetwork()).differentialcreate$setSpeed(7);
            double expected = feed.inertia, energy = ElasticKinetics.kineticEnergy(original.getOrCreateNetwork());
            var rootTag = root.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            rootTag.putString("id", net.minecraft.world.entity.EntityType.getKey(root.getType()).toString()); rootTag.remove("Passengers");
            var childTags = children.stream().map(d -> {
                var tag = d.entity().saveWithoutId(new net.minecraft.nbt.CompoundTag());
                tag.putString("id", net.minecraft.world.entity.EntityType.getKey(d.entity().getType()).toString()); return tag;
            }).toList();
            for (var child : children) child.entity().remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            root.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            var blockTag = original.saveWithFullMetadata(h.getLevel().registryAccess());
            h.assertTrue(blockTag.getInt("DCStructureCount") == 4, "late controller save retains all unloaded hand payloads");
            var restored = (MechanicalBearingBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), blockTag, h.getLevel().registryAccess());
            h.getLevel().setBlockEntity(restored); NetworkTopology.mark(restored); NetworkTopology.flush(); ElasticKinetics.refresh(restored.getOrCreateNetwork());
            h.assertTrue(Math.abs(ActorFeed.of(restored).inertia - expected) < 1e-7 && Math.abs(ActorFeed.of(restored).speed - 7) < 1e-7,
                "controller-only reload retains its independent output mass and velocity: expected=" + expected + ", inertia=" + ActorFeed.of(restored).inertia + ", speed=" + ActorFeed.of(restored).speed + ", tag=" + blockTag);
            var loadedRoot = (com.simibubi.create.content.contraptions.ControlledContraptionEntity) net.minecraft.world.entity.EntityType.loadEntityRecursive(rootTag, h.getLevel(), e -> e);
            h.getLevel().addFreshEntity(loadedRoot); restored.attach(loadedRoot);
            for (int loaded = 0; loaded <= 3; loaded++) {
                ContraptionPhysics.checkAttachments(restored); ElasticKinetics.refresh(restored.getOrCreateNetwork());
                h.assertTrue(Math.abs(ActorFeed.of(restored).inertia - expected) < 1e-7, "partial hand payload loading retains the full output mass: " + loaded);
                h.assertTrue(Math.abs(ActorFeed.of(restored).speed - 7) < 1e-6, "partial loading cannot slow the same saved output twice");
                h.assertTrue(Math.abs(ElasticKinetics.kineticEnergy(restored.getOrCreateNetwork()) - energy) < 1e-6, "partial loading cannot mint or remove the body's saved energy");
                if (loaded < 3) {
                    var child = net.minecraft.world.entity.EntityType.loadEntityRecursive(childTags.get(loaded), h.getLevel(), e -> e);
                    h.getLevel().addFreshEntity(child); child.startRiding(loadedRoot);
                }
            }
            restored.disassemble(); h.succeed();
        });
    }
}
