package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.mounted.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Cold real assemblers on powered rails, without repeated harness pushes or
 * free hand energy. Sixteen tools match the player's tree farm load. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class MinecartHandPowerTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (var facing : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            for (int count : new int[]{1, 16}) for (String kind : new String[]{"start", "plant"}) {
                String name = "minecart_hand_" + facing.getName() + "_" + count + "_" + kind;
                if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                    tests.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 600, 0, true,
                        h -> run(h, facing, count, kind)));
            }
            String name = "minecart_hand_" + facing.getName() + "_coast";
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                tests.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 600, 0, true,
                    h -> run(h, facing, 16, "coast")));
            for (String kind : new String[]{"fast_plant", "unpowered_plant"}) {
                String useName = "minecart_hand_" + facing.getName() + "_" + kind;
                if (useName.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                    tests.add(new TestFunction(useName, useName, "differentialcreate_motion:kineticphysicstests.physics_test", 600, 0, true,
                        h -> run(h, facing, 16, kind)));
            }
        }
        return tests;
    }
    private static void run(GameTestHelper h, Direction facing, int count, String kind) {
        BlockPos base = switch (facing) {
            case EAST -> new BlockPos(4, 5, 4);
            case WEST -> new BlockPos(20, 5, 22);
            case NORTH -> new BlockPos(4, 5, 20);
            default -> new BlockPos(20, 5, 4);
        };
        var side = facing.getClockWise(); boolean plant = kind.endsWith("plant"), unpowered = kind.equals("unpowered_plant"),
            coast = kind.equals("coast") || unpowered;
        var shape = facing.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        for (int i = -1; i <= 8; i++) {
            var p = base.relative(facing, i); h.setBlock(p.below(), coast ? Blocks.STONE : Blocks.REDSTONE_BLOCK);
            h.setBlock(p, coast ? Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape)
                : Blocks.POWERED_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape)
                    .setValue(PoweredRailBlock.POWERED, true));
        }
        h.setBlock(base.relative(facing.getOpposite()), Blocks.OBSIDIAN);
        h.setBlock(base.below(), Blocks.REDSTONE_BLOCK);
        h.setBlock(base, AllBlocks.CART_ASSEMBLER.getDefaultState().setValue(CartAssemblerBlock.RAIL_SHAPE, shape)
            .setValue(CartAssemblerBlock.RAIL_TYPE, CartAssembleRailType.REGULAR));
        h.setBlock(base.above(), Blocks.SLIME_BLOCK);
        for (int i = 1; i <= count; i++) {
            var p = base.above().relative(side, i); h.setBlock(p, Blocks.SLIME_BLOCK); h.setBlock(p.above(), Blocks.SLIME_BLOCK);
            h.setBlock(p.above(2), AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
        }
        h.runAfterDelay(5, () -> {
            var cart = EntityType.MINECART.create(h.getLevel()); cart.setPos(Vec3.atCenterOf(h.absolutePos(base)).add(0, -.4375, 0));
            h.getLevel().addFreshEntity(cart);
            CartAssemblerBlockEntity assembler = h.getBlockEntity(base); assembler.tryAssemble(cart);
            var root = (OrientedContraptionEntity) MinecartPhysics.mounted(cart);
            h.assertTrue(root != null, "ordinary assembler captures the hands");
            ((MountedContraption) root.getContraption()).rotationMode = CartAssemblerBlockEntity.CartMovementMode.ROTATION_LOCKED;
            cart.positionRider(root);
            var hands = root.getContraption().getActors().stream().map(a -> a.getRight()).filter(MovingDeployerWork::isHand).toList();
            h.assertTrue(hands.size() == count, "all real mounted hands are present");
            var targets = new ArrayList<BlockPos>(); int index = 0;
            for (var ctx : hands) {
                ctx.blockEntityData.putString("Mode", plant ? "USE" : "PUNCH");
                var hand = ContraptionActorWork.of(ctx).hand(); hand.player().setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(plant ? Items.BIRCH_SAPLING : Items.DIAMOND_PICKAXE, plant ? 8 : 1));
                h.assertTrue(hand.cartEnergy() == 0, "cold hand starts without free energy");
                if (coast && !unpowered && index++ % 2 == 0) ctx.disabled = true;
                if (plant) {
                    var actor = MovementBehaviour.REGISTRY.get(ctx.state);
                    var target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(actor.getActiveAreaOffset(ctx)), 1));
                    // A real cold minecart obtains its energy on the approach,
                    // as in the player's saw-and-planter tree farm.
                    for (int row = unpowered ? 0 : 2; row < (unpowered ? 1 : 5); row++) {
                        var p = target.relative(facing, row); h.getLevel().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                        h.getLevel().setBlockAndUpdate(p.below(), Blocks.DIRT.defaultBlockState()); targets.add(p);
                    }
                }
            }
            if (coast) {
                h.setBlock(base.below(), Blocks.STONE);
                h.setBlock(base, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape));
                cart.setDeltaMovement(Vec3.atLowerCornerOf(facing.getNormal()).scale(unpowered ? .001 : .2));
            }
            else h.setBlock(base, Blocks.POWERED_RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape)
                .setValue(PoweredRailBlock.POWERED, true));
            if (kind.equals("fast_plant")) cart.setDeltaMovement(Vec3.atLowerCornerOf(facing.getNormal()).scale(.4));
            double initialEnergy = energy(cart, hands); var start = cart.position();
            class Observe {
                int ticks; boolean parked;
                void tick() {
                    ticks++;
                    h.assertFalse(root.isStalled() || hands.stream().anyMatch(c -> c.stall), "hands never use native whole-car pause");
                    double speed = cart.getDeltaMovement().horizontalDistance();
                    if (plant && speed < 1e-9 && hands.stream().anyMatch(c -> ContraptionActorWork.of(c).hand().pending())) parked = true;
                    if (coast) {
                        double work = hands.stream().mapToDouble(c -> c.data.getCompound(MovingDeployerWork.TAG).getDouble("Work")).sum();
                        h.assertTrue(energy(cart, hands) + work <= initialEnergy + 1e-8, "shared take-off and hand actions use only paid cart energy");
                        h.assertTrue(hands.stream().filter(c -> c.disabled).allMatch(c -> ContraptionActorWork.of(c).hand().cartEnergy() == 0),
                            "disabled idle tools take no charging work");
                    }
                    boolean planted = targets.stream().allMatch(p -> h.getLevel().getBlockState(p).is(Blocks.BIRCH_SAPLING)
                        || h.getLevel().getBlockState(p).is(Blocks.BIRCH_LOG));
                    if (coast && ticks == 12 || !coast && !plant && ticks >= 20 && cart.position().distanceTo(start) > 2
                        || plant && planted && hands.stream().noneMatch(c -> ContraptionActorWork.of(c).hand().pending())) {
                        if (plant && !unpowered) {
                            h.assertFalse(parked, "ordinary planting and retraction keep the carrier moving");
                            h.assertTrue(hands.stream().allMatch(c -> ContraptionActorWork.of(c).hand().player().getMainHandItem().getCount() == 5),
                                "each planter consumes exactly three real saplings without replaying the same contact");
                        }
                        if (unpowered) {
                            h.assertTrue(targets.stream().allMatch(p -> h.getLevel().getBlockState(p).isAir()), "insufficient cart energy cannot plant for free");
                            h.assertTrue(hands.stream().allMatch(c -> ContraptionActorWork.of(c).hand().player().getMainHandItem().getCount() == 8),
                                "unfunded contact consumes no saplings");
                        } else if (coast) h.assertTrue(hands.stream().anyMatch(c -> ContraptionActorWork.of(c).hand().cartEnergy() > 0), "moving enabled hands actually charge");
                        root.discard(); cart.ejectPassengers(); cart.discard(); h.succeed(); return;
                    }
                    if (ticks > (plant ? 500 : 80)) h.fail("cold " + count + "-hand " + kind + " failed: distance="
                        + cart.position().distanceTo(start) + ", speed=" + speed + ", planted=" + targets.stream().filter(p -> h.getLevel().getBlockState(p).is(Blocks.BIRCH_SAPLING)).count()
                        + ", position=" + cart.position() + ", actor=" + hands.getFirst().position
                        + ", targets=" + targets.stream().limit(3).map(p -> p + ":" + h.getLevel().getBlockState(p) + "/" + h.getLevel().getBlockState(p.below())).toList()
                        + ", item=" + ContraptionActorWork.of(hands.getFirst()).hand().player().getMainHandItem() + ", hand=" + hands.getFirst().data);
                    h.runAfterDelay(1, this::tick);
                }
            }
            h.runAfterDelay(1, new Observe()::tick);
        });
    }
    private static double energy(AbstractMinecart cart, List<MovementContext> hands) {
        return MinecartMotion.energy(MinecartPhysics.refresh(cart), cart.getDeltaMovement().horizontalDistance())
            + hands.stream().mapToDouble(c -> ContraptionActorWork.of(c).hand().cartEnergy()).sum();
    }
}
