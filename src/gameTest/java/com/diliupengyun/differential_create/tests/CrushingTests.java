package com.diliupengyun.differential_create.tests;

import java.util.List;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

final class CrushingTests {
    private static final BlockPos POS = new BlockPos(8,3,8);
    static void staticFeedPresentation(GameTestHelper h) {
        fixture(h, pair -> {
            insert(pair, 64);
            for (double common : new double[]{-4, 0, 4}) for (double error : new double[]{-1e-10, 0, 1e-10}) {
                speed(pair, common + error, common - error);
                CrushingKinetics.update(pair.controller());
                h.assertTrue(pair.controller().crushingspeed == 0, "static-contact roundoff cannot emit crushing particles");
                double before = work(pair).differentialcreate$remainingTravel();
                pair.controller().tick();
                near(h, before, work(pair).differentialcreate$remainingTravel(), 1e-10, "common oscillation cannot grant processing progress");
            }
            speed(pair, .001, -.001);
            CrushingKinetics.update(pair.controller());
            h.assertTrue(pair.controller().crushingspeed > 0, "real slow relative movement must remain visible");
            h.succeed();
        });
    }
    static void motion(GameTestHelper h, double a, double b) {
        fixture(h,pair -> {
            insert(pair,64);
            pair.controller().inventory.remainingTime=1000; // Isolate motion from the completion cases below.
            double before=work(pair).differentialcreate$remainingTravel();
            speed(pair,a,b); pair.controller().tick();
            near(h,before,work(pair).differentialcreate$remainingTravel(),1e-9,"native countdown cannot grant sampled work");
            double relative=a-b, j=InertiaRegistry.getInertia(pair.a()), torque=pair.torque();
            double stop=Math.abs(relative)*j/(2*torque), time=Math.min(stop,RotationMath.TICK_MINUTES);
            double travel=(Math.abs(relative)*time-torque/j*time*time)/2;
            MachiningClock.advance(h.getLevel());
            near(h,before-work(pair).differentialcreate$remainingTravel(),travel*1200,1e-6,"actual common feed travel");
            near(h,a-Math.signum(relative)*torque/j*time,NetworkDynamics.preciseSpeed(pair.a()),1e-6,"left paired reaction");
            near(h,b+Math.signum(relative)*torque/j*time,NetworkDynamics.preciseSpeed(pair.b()),1e-6,"right paired reaction");
            h.assertTrue(pair.a().getOrCreateNetwork()!=pair.b().getOrCreateNetwork(),"material contact preserves independent networks");
            if(a==b) h.assertTrue(pair.controller().crushingspeed==0,"common rotation has no feed");
            else if(a>0 && b>0 && a>b) {
                var reading=NetworkLoad.of(pair.b());
                h.assertTrue(reading.status()==LoadFactor.Status.AVAILABLE && reading.fraction()==0,"slower shaft receives mechanical power");
            }
            h.succeed();
        });
    }
    static void completion(GameTestHelper h, int direction) {
        fixture(h,pair -> {
            insert(pair,64); double required=work(pair).differentialcreate$remainingTravel();
            work(pair).differentialcreate$advanceTravel(required-32);
            speed(pair,direction*128,-direction*64);
            double initialEnergy=energy(pair), initialMomentum=.1*direction*64;
            MachiningClock.advance(h.getLevel());
            h.assertTrue(pair.controller().inventory.isEmpty(),"recipe and whole-batch output occur at the paid boundary");
            near(h,16*32d/1200,initialEnergy-energy(pair),1e-6,"completion unloads both wheels for rest of tick");
            near(h,initialMomentum,.1*(NetworkDynamics.preciseSpeed(pair.a())+NetworkDynamics.preciseSpeed(pair.b())),1e-7,"completion momentum");
            int outputs=outputCount(h);
            h.assertTrue(outputs==64,"roll one gravel output for each item in the full stack: "+outputs);
            double after=energy(pair); MachiningClock.advance(h.getLevel()); pair.controller().tick();
            near(h,after,energy(pair),1e-7,"finished controller has no work torque");
            h.assertTrue(outputCount(h)==outputs,"later ticks cannot replay a recipe");
            h.succeed();
        });
    }
    static void batch(GameTestHelper h, int count) {
        fixture(h,pair -> {
            insert(pair,count);
            double required=work(pair).differentialcreate$remainingTravel();
            double expected=Math.max(0,pair.controller().inventory.remainingTime-20)*12.5*CrushingWork.batchFactor(count);
            near(h,expected,required,1e-8,"finite logarithmic whole-batch budget");
            double used=0;
            for(int tick=0;tick<1000 && Double.isFinite(work(pair).differentialcreate$remainingTravel());tick++) {
                speed(pair,256,-256); double before=energy(pair); MachiningClock.advance(h.getLevel()); used+=before-energy(pair);
            }
            h.assertTrue(pair.controller().inventory.isEmpty(),"batch completes at integrated work boundary");
            near(h,16*required/1200,used,2e-6,"batch work has no per-item torque multiplier");
            h.assertTrue(outputCount(h)==count,"native recipe rolls and whole-stack output retained");
            h.succeed();
        });
    }
    static void persistence(GameTestHelper h) {
        fixture(h,pair -> {
            insert(pair,64); double full=work(pair).differentialcreate$remainingTravel();
            work(pair).differentialcreate$advanceTravel(123.456789);
            double remaining=work(pair).differentialcreate$remainingTravel();
            var tag=pair.controller().saveWithFullMetadata(h.getLevel().registryAccess());
            var pos=pair.controller().getBlockPos(); h.getLevel().removeBlockEntity(pos);
            var cold=(CrushingWheelControllerBlockEntity)BlockEntity.loadStatic(pos,h.getBlockState(POS),tag,h.getLevel().registryAccess());
            h.getLevel().setBlockEntity(cold);
            var restored=CrushingKinetics.pair(cold);
            near(h,full-123.456789,work(restored).differentialcreate$remainingTravel(),1e-8,"double precision credit survives cold reload");
            speed(restored,64,64); MachiningClock.advance(h.getLevel()); cold.tick();
            near(h,remaining,work(restored).differentialcreate$remainingTravel(),1e-8,"no feed preserves saved progress");
            speed(restored,0,0); MachiningClock.advance(h.getLevel());
            near(h,remaining,work(restored).differentialcreate$remainingTravel(),1e-8,"fully stopped preserves saved progress");
            h.succeed();
        });
    }
    static void entityFeed(GameTestHelper h) {
        fixture(h,pair -> {
            speed(pair,8,0);
            Direction facing=pair.controller().getBlockState().getValue(CrushingWheelControllerBlock.FACING);
            var center=net.minecraft.world.phys.Vec3.atCenterOf(pair.controller().getBlockPos());
            var pig=net.minecraft.world.entity.EntityType.PIG.create(h.getLevel());
            pig.setPos(center);h.getLevel().addFreshEntity(pig);
            AllBlocks.CRUSHING_WHEEL_CONTROLLER.get().checkEntityForProcessing(h.getLevel(),pair.controller().getBlockPos(),pig);
            pair.controller().tick();
            double motion=pig.getDeltaMovement().dot(net.minecraft.world.phys.Vec3.atLowerCornerOf(facing.getNormal()));
            near(h,.08,motion,1e-7,"entity feed uses half the single wheel speed");
            float health=pig.getHealth();pig.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            speed(pair,8,8);pair.controller().tick();
            near(h,health,pig.getHealth(),0,"common rotation grants no native crushing damage");
            near(h,0,pig.getDeltaMovement().length(),0,"common rotation grants no native feed");
            pig.discard();pair.controller().clear();speed(pair,8,0);
            var item=new ItemEntity(h.getLevel(),center.x,center.y,center.z,new ItemStack(Items.COBBLESTONE,4));
            h.getLevel().addFreshEntity(item);
            AllBlocks.CRUSHING_WHEEL_CONTROLLER.get().checkEntityForProcessing(h.getLevel(),pair.controller().getBlockPos(),item);
            pair.controller().tick();
            h.assertTrue(!item.isAlive() && pair.controller().inventory.getStackInSlot(0).getCount()==4,
                "single moving wheel retains native item intake and whole stack");
            h.succeed();
        });
    }
    static void drives(GameTestHelper h, boolean clutch) {
        fixture(h,pair -> {
            insert(pair,64);
            var placed=new java.util.ArrayList<com.simibubi.create.content.kinetics.base.KineticBlockEntity>();
            for(var wheel:List.of(pair.a(),pair.b())) {
                BlockPos pos=(wheel==pair.a()?POS.west():POS.east()).north();
                if(clutch && wheel==pair.a()) {
                    h.setBlock(pos,AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.Z));
                    com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity contact=h.getBlockEntity(pos);
                    contact.source=h.absolutePos(pos.north());placed.add(contact);pos=pos.north();
                }
                h.setBlock(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.SOUTH));
                com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity motor=h.getBlockEntity(pos);
                // ScrollValueBehaviour.setValue queues its value until its own next tick.
                motor.generatedSpeed.value=wheel==pair.a()?128:-64;motor.updateGeneratedRotation();placed.add(motor);
            }
            placed.forEach(NetworkTopology::mark);NetworkTopology.mark(pair.a());NetworkTopology.mark(pair.b());NetworkTopology.flush();
            for(var member:placed) if(member.isSource()) {
                h.assertTrue(SourcePhysics.curve(member).stallTorque()!=0,"source has actual drive torque");
                h.assertTrue(member.getOrCreateNetwork()==(member.getBlockPos().getX()==pair.a().getBlockPos().getX()?pair.a():pair.b()).getOrCreateNetwork(),
                    "motor has a real shaft connection to its wheel: "+member.getBlockState());
            }
            speed(pair,0,0);
            double before=work(pair).differentialcreate$remainingTravel();
            boolean slipped=false;
            if(clutch) {
                // Inspect a real short engagement interval; its slip can finish before a full tick ends.
                ElasticKinetics.state(pair.a()).physicalSpeed=-16;
                var engagement=ElasticKinetics.sample(MachiningClock.networks(h.getLevel()),PhysicsTuning.TORSION_MAX_STEP);
                engagement.commit();
                work(pair).differentialcreate$advanceMotion(engagement.crushingTravel(pair),engagement.crushingSignedTravel(pair));
                for(var edge:engagement.connections) if(edge.clutch!=null && edge.a.getOrCreateNetwork()==pair.a().getOrCreateNetwork())
                    slipped|=edge.slipping;
            }
            for(int tick=0;tick<24;tick++) {
                MachiningClock.advance(h.getLevel());
                h.assertTrue(!pair.a().isRemoved()&&!pair.b().isRemoved(),"normal independent drive survives startup");
                h.assertTrue(pair.a().getOrCreateNetwork()!=pair.b().getOrCreateNetwork(),"drives retain independent networks");
                h.assertTrue(work(pair).differentialcreate$remainingTravel()<=before,"driven work remains monotone");
                before=work(pair).differentialcreate$remainingTravel();
                if(clutch) for(var edge:((INetworkDynamics)pair.a().getOrCreateNetwork()).differentialcreate$data().connections)
                    if(edge.clutch!=null) {
                        slipped|=edge.slipping;
                        h.assertTrue(Math.abs(edge.torque)<=ClutchPhysics.torqueLimit(edge.clutch)+1e-6,"clutch respects its separate force cap");
                    }
            }
            h.assertTrue(NetworkDynamics.preciseSpeed(pair.a())>0 && NetworkDynamics.preciseSpeed(pair.b())<0,
                "both independent motors deliver their own direction: "+NetworkDynamics.preciseSpeed(pair.a())+", "+NetworkDynamics.preciseSpeed(pair.b())
                +"; sources="+placed.stream().map(be->be.getGeneratedSpeed()+"/"+SourcePhysics.curve(be)+"/"+NetworkDynamics.preciseSpeed(be)).toList());
            if(clutch) h.assertTrue(slipped,"startup exercised actual clutch slip");
            h.succeed();
        });
    }
    static void geometry(GameTestHelper h, Direction.Axis axis, Direction separation) {
        BlockPos a=POS.relative(separation.getOpposite()),b=POS.relative(separation);
        h.setBlock(a,AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS,axis));
        h.setBlock(b,AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS,axis));
        h.runAfterDelay(5,()-> {
            CrushingWheelBlockEntity left=h.getBlockEntity(a),right=h.getBlockEntity(b);
            NetworkTopology.mark(left); NetworkTopology.mark(right); NetworkTopology.flush();
            // Call the real update path, with one stopped wheel; it must create a valid controller.
            ElasticKinetics.state(left).physicalSpeed=64; ElasticKinetics.state(right).physicalSpeed=0;
            AllBlocks.CRUSHING_WHEEL.get().updateControllers(left.getBlockState(),h.getLevel(),left.getBlockPos(),separation);
            CrushingWheelControllerBlockEntity controller=h.getBlockEntity(POS);
            var pair=CrushingKinetics.pair(controller);
            h.assertTrue(controller.getBlockState().getValue(CrushingWheelControllerBlock.VALID),"single moving wheel provides feed");
            near(h,.64,controller.crushingspeed,1e-7,"controller speed uses both wheels");
            Direction forward=controller.getBlockState().getValue(CrushingWheelControllerBlock.FACING);
            ElasticKinetics.state(left).physicalSpeed=-64;
            CrushingKinetics.update(controller);
            h.assertTrue(controller.getBlockState().getValue(CrushingWheelControllerBlock.FACING)==forward.getOpposite(),"feed reversal flips intake/output direction");
            h.assertTrue(pair!=null,"matching wheel geometry detected for each orientation");
            h.setBlock(b,Blocks.AIR);
            h.assertTrue(h.getBlockState(POS).isAir(),"removing either wheel removes its controller");
            h.succeed();
        });
    }
    private static void fixture(GameTestHelper h, Consumer<CrushingKinetics.Pair> body) {
        for(BlockPos p:List.of(POS.west(),POS.east())) h.setBlock(p,AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.Z));
        h.runAfterDelay(5,()-> {
            CrushingWheelBlockEntity a=h.getBlockEntity(POS.west()),b=h.getBlockEntity(POS.east());
            NetworkTopology.mark(a);NetworkTopology.mark(b);NetworkTopology.flush();
            AllBlocks.CRUSHING_WHEEL.get().updateControllers(a.getBlockState(),h.getLevel(),a.getBlockPos(),Direction.EAST);
            CrushingWheelControllerBlockEntity controller=h.getBlockEntity(POS);
            h.assertTrue(controller!=null,"controller exists even at standstill");
            body.accept(CrushingKinetics.pair(controller));
        });
    }
    private static void insert(CrushingKinetics.Pair pair,int count) { pair.controller().inventory.insertItem(0,new ItemStack(Items.COBBLESTONE,count),false); }
    private static ShaftWork work(CrushingKinetics.Pair pair) { return (ShaftWork)pair.controller(); }
    private static void speed(CrushingKinetics.Pair pair,double a,double b) {
        ElasticKinetics.state(pair.a()).physicalSpeed=a;ElasticKinetics.state(pair.b()).physicalSpeed=b;
        CrushingKinetics.update(pair.controller());
    }
    private static double energy(CrushingKinetics.Pair pair) { return .5*InertiaRegistry.getInertia(pair.a())*Math.pow(NetworkDynamics.preciseSpeed(pair.a()),2)
        +.5*InertiaRegistry.getInertia(pair.b())*Math.pow(NetworkDynamics.preciseSpeed(pair.b()),2); }
    private static int outputCount(GameTestHelper h) { return h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(h.absolutePos(POS)).inflate(2))
        .stream().filter(e->e.getItem().is(Items.GRAVEL)).mapToInt(e->e.getItem().getCount()).sum(); }
    private static void near(GameTestHelper h,double expected,double actual,double tolerance,String label) {
        h.assertTrue(Double.isFinite(actual)&&Math.abs(expected-actual)<=tolerance,label+": expected "+expected+", got "+actual);
    }
}
