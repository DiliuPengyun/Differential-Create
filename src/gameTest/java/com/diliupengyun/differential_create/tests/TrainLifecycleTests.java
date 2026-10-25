package com.diliupengyun.differential_create.tests;

import java.util.*;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.*;

/** Native structure capture, rotated rebuilds and terminal collision constraints. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainLifecycleTests {
    private static void add(List<TestFunction> tests,String name,Consumer<GameTestHelper> run){
        if(name.matches(System.getProperty("differentialcreate.trains.select",".*")))
            tests.add(new TestFunction(name,name,"differentialcreate_trains:kineticphysicstests.physics_test",160,0,true,run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases(){
        var tests=new ArrayList<TestFunction>();
        for(boolean large:new boolean[]{false,true})for(int engines:new int[]{0,1,2})
            add(tests,"train_two_bogeys_"+(large?"large_":"small_")+engines,h->twoBogeys(h,large,engines));
        for(Direction facing:Direction.values())
            add(tests,"train_bearing_payload_"+facing.getName(),h->bearing(h,facing));
        for(Rotation rotation:Rotation.values())for(String change:new String[]{"engine","hose","heater","lever","mass","empty_water"})
            add(tests,"train_rebuild_"+rotation.name().toLowerCase(Locale.ROOT)+"_"+change,h->rebuild(h,rotation,change));
        for(boolean reverse:new boolean[]{false,true})for(String op:new String[]{"end","crash","stall","readback","unloaded"})
            add(tests,"train_constraint_"+op+(reverse?"_reverse":"_forward"),h->constraint(h,reverse,op));
        return tests;
    }
    private static void twoBogeys(GameTestHelper h,boolean large,int engines){
        TrainTestFixture.create(h,"flat",large,false,true,setup->{
            if(engines==0)TrainIntegrationTests.unpower(setup);
            if(engines==2)SteamBogey.install(setup.getBlockEntity(TrainTestFixture.WHEEL.south(6)));
        },f->{
            var c=f.train.carriages.getFirst();var cs=TrainPhysics.carriage(c);
            h.assertTrue(c.isOnTwoBogeys()&&cs.layout.wheels.size()==2&&cs.layout.drives.size()==engines,"Native two-bogey carriage captures each installed engine once");
            for(var bogey:c.bogeys)if(bogey.bogeyData.getBoolean(SteamBogey.ENGINE))
                h.assertTrue(cs.engines.containsKey(BlockPos.of(bogey.bogeyData.getLong(SteamBogey.MOTOR_POS))),"Each actual bogey binds its own input rotor");
            int water=f.water();for(int i=0;i<20;i++)f.tick(1,false);
            h.assertTrue(Double.isFinite(f.train.speed)&&!f.train.derailed,"Two-bogey travel retains native carriage spacing");
            h.assertTrue(engines==0?f.train.speed==0&&f.water()==water:f.train.speed>0&&f.water()<water,"Only the installed wheel engines supply traction");
            f.reload();h.assertTrue(TrainPhysics.carriage(f.train.carriages.getFirst()).layout.drives.size()==engines,"Readback retains both wheel installation states");f.finish();
        });
    }
    private static void bearing(GameTestHelper h,Direction facing){
        var pos=new BlockPos(7,6,7);var tool=pos.relative(facing,2);
        TrainTestFixture.create(h,"flat",false,false,setup->{
            setup.setBlock(pos.below(2),Blocks.STONE);setup.setBlock(pos.below(),Blocks.STONE);
            setup.setBlock(pos,AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING,facing));
            setup.setBlock(pos.relative(facing),Blocks.SLIME_BLOCK);
            setup.setBlock(tool,AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING,facing));
        },f->{
            var cc=(CarriageContraption)f.entity().getContraption();
            h.assertTrue(!cc.canBeStabilized(facing,pos)&&f.entity().getPassengers().isEmpty(),"Native trains capture bearing payloads in the carriage, without stabilized child entities");
            h.assertTrue(cc.getBlocks().containsKey(h.absolutePos(tool).subtract(cc.anchor)),"Bearing-front work block belongs to the actual assembled train");
            f.tick(0,false);h.assertTrue(TrainPhysics.state(f.train).mass==cc.getBlocks().size()*TrainMechanics.BLOCK_MASS,"Bearing payload is included once in train inertia");f.finish();
        });
    }
    private static void rebuild(GameTestHelper h,Rotation rotation,String change){
        TrainTestFixture.create(h,"flat",false,false,setup->{},f->{
            f.tick(1,false);var c=f.train.carriages.getFirst();var cs=TrainPhysics.carriage(c);
            var cc=(CarriageContraption)f.entity().getContraption();
            if(change.equals("empty_water"))c.storage.getFluids().drain(Integer.MAX_VALUE,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            int initial=cs.layout.blocks,fuel=f.items(Items.COAL),water=f.water();
            cs.apply(cc,1,false,true);cc.returnStorageForDisassembly(c.storage);
            var transform=new StructureTransform(cc.anchor,Direction.Axis.Y,rotation,Mirror.NONE);
            var wheel=transform.apply(BlockPos.ZERO);
            BlockPos boiler=transform.apply(h.absolutePos(TrainTestFixture.BOILER).subtract(cc.anchor));
            BlockPos lever=transform.apply(h.absolutePos(new BlockPos(8,4,5)).subtract(cc.anchor));
            BlockPos mass=transform.apply(h.absolutePos(new BlockPos(10,3,9)).subtract(cc.anchor));
            f.close();cc.addBlocksToWorld(h.getLevel(),transform);
            h.runAfterDelay(25,()->{
                var engine=h.getLevel().getBlockEntity(wheel);
                switch(change){
                    case "engine"->{
                        var player=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);player.setShiftKeyDown(true);
                        var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(wheel),Direction.UP,wheel,false);
                        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,net.minecraft.world.InteractionHand.MAIN_HAND,wheel,hit));
                    }
                    case "hose"->SteamHoseLinks.unlink(engine);
                    case "heater"->h.getLevel().setBlockAndUpdate(boiler.below(),Blocks.AIR.defaultBlockState());
                    case "lever"->h.getLevel().setBlockAndUpdate(lever,Blocks.AIR.defaultBlockState());
                    case "mass"->h.getLevel().setBlockAndUpdate(mass,Blocks.AIR.defaultBlockState());
                }
                var next=new CarriageContraption(rotation.rotate(Direction.SOUTH));
                try{h.assertTrue(next.assemble(h.getLevel(),wheel),"Rotated train rebuild uses native assembly after a real world edit");}
                catch(com.simibubi.create.content.contraptions.AssemblyException e){throw new IllegalStateException(e);}
                var layout=((TrainAssembly.LayoutHolder)next).differentialcreate$layout();
                h.assertTrue(layout.drives.size()==(change.equals("engine")||change.equals("hose")?0:1),"Rebuild recomputes the drivetrain from current world blocks");
                if(change.equals("engine"))h.assertTrue(layout.installedEngines==0,"Removed wheel engine cannot remain in a cached layout");
                if(change.equals("heater"))h.assertTrue(layout.boilers.values().stream().flatMap(b->b.heaters().stream()).noneMatch(b->b.burner()!=null||b.fixedHeat()>0),"Removed burner cannot remain as a cached heat source");
                if(change.equals("lever"))h.assertTrue(layout.levers.isEmpty(),"Removed lever cannot remain as a cached control");
                if(change.equals("mass"))h.assertTrue(layout.blocks==initial-1,"Rebuild releases exactly the removed structural mass");
                var stock=new com.simibubi.create.content.contraptions.minecart.TrainCargoManager();
                for(var info:next.getBlocks().values()){var at=info.pos().offset(next.anchor);stock.addBlock(h.getLevel(),info.state(),at,info.pos(),h.getLevel().getBlockEntity(at));}
                stock.initialize();int foundFuel=0,foundWater=0;
                var inv=stock.getAllItems();for(int i=0;i<inv.getSlots();i++)if(inv.getStackInSlot(i).is(Items.COAL))foundFuel+=inv.getStackInSlot(i).getCount();
                for(var tank:stock.getFluids().storages.values())foundWater+=tank.getFluidInTank(0).getAmount();
                h.assertTrue(foundFuel==fuel&&foundWater==water,"Rotated disassembly and rebuild preserve actual mounted cargo: coal="+foundFuel+"/"+fuel+" water="+foundWater+"/"+water+" storage="+stock.getFluids().storages.keySet());h.succeed();
            });
        });
    }
    private static void constraint(GameTestHelper h,boolean reverse,String op){
        TrainTestFixture.create(h,op.equals("end")?reverse?"start":"end":"flat",false,false,setup->{},f->{
            TrainPhysics.shift(f.train,0);f.train.speed=(reverse?-1:1)*.8;
            if(op.equals("crash"))f.train.crash();
            if(op.equals("stall")){f.train.speedBeforeStall=f.train.speed;f.train.carriages.getFirst().stalled=true;}
            boolean blocked=false;for(int i=0;i<(op.equals("readback")?5:op.equals("unloaded")?120:40);i++){f.tick(0,false);blocked|=f.train.carriages.getFirst().blocked;if(i==0&&op.equals("stall"))h.assertTrue(Math.abs(f.train.speed)<.8,"A stale pre-stall speed cannot erase this tick's dissipated momentum");}
            if(op.equals("end"))h.assertTrue(blocked&&f.train.speed==0,"Native track end removes collision momentum in either direction: blocked="+blocked+" speed="+f.train.speed+" point="+f.train.carriages.getFirst().getLeadingPoint().position);
            if(op.equals("crash"))h.assertTrue(f.train.derailed&&f.train.graph==null&&f.train.speed==0,"Derailed train brakes without graph access or resurrection of collision speed");
            if(op.equals("stall"))h.assertTrue(Math.abs(f.train.speed)<.8&&f.train.speedBeforeStall==null,"Stalled actor callbacks cannot restore an old saved train velocity");
            if(op.equals("readback")){double before=f.train.speed;f.reload();h.assertTrue(f.train.speed==before,"Native moving readback preserves signed momentum");f.tick(0,true);h.assertTrue(Math.abs(f.train.speed)<=Math.abs(before),"Readback braking dissipates rather than restores energy");}
            if(op.equals("unloaded")){h.assertTrue(f.entity()==null,"Moving train actually leaves the test's loaded chunk area");double speed=f.train.speed;int water=f.water(),fuel=f.items(Items.COAL);f.reload();h.assertTrue(f.entity()==null&&f.train.speed==speed&&f.water()==water&&f.items(Items.COAL)==fuel,"Native unloaded readback preserves cargo and momentum without duplicating an entity");}
            f.finish();
        });
    }
}
