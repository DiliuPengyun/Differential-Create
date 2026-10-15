package com.diliupengyun.differential_create.tests;

import java.util.*;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.*;

/** Resource boundaries and carriage composition use native captured storage. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainSupplyPartitionTests {
    private static void add(List<TestFunction> tests,String name,int timeout,Consumer<GameTestHelper> run){
        if(name.matches(System.getProperty("differentialcreate.trains.select",".*")))
            tests.add(new TestFunction(name,name,"differentialcreate_trains:kineticphysicstests.physics_test",timeout,0,true,run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases(){
        var result=new ArrayList<TestFunction>();
        for(int water:new int[]{0,1,9,10,11,199,200,201})for(double valve:new double[]{0,.001,.03,1})for(boolean two:new boolean[]{false,true})
            add(result,"train_supply_"+water+"_valve_"+Double.toString(valve).replace('.','_')+"_engines_"+(two?2:1),100,h->supply(h,water,valve,two));
        for(boolean large:new boolean[]{false,true})for(String op:new String[]{"plain","no_lever","second_lever","extra_mass","readback"})
            add(result,"train_structure_"+(large?"large":"small")+"_upright_"+op,100,h->structure(h,large,op));
        for(boolean reverse:new boolean[]{false,true})for(String resource:new String[]{"water","fuel","both"})
            add(result,"train_cross_carriage_"+resource+(reverse?"_reverse":"_forward"),160,h->cross(h,resource,reverse));
        for(String track:new String[]{"flat","up","down","curve"})for(boolean reverse:new boolean[]{false,true})
            add(result,"train_coupled_"+track+(reverse?"_reverse":"_forward"),160,h->coupled(h,track,reverse));
        return result;
    }
    private static int water(Train t){int n=0;for(var c:t.carriages){var fluids=c.storage.getFluids();for(int i=0;i<fluids.getTanks();i++)if(fluids.getFluidInTank(i).is(Fluids.WATER))n+=fluids.getFluidInTank(i).getAmount();}return n;}
    private static int items(Train t,Item item){int n=0;for(var c:t.carriages){var inv=c.storage.getAllItems();for(int i=0;i<inv.getSlots();i++)if(inv.getStackInSlot(i).is(item))n+=inv.getStackInSlot(i).getCount();}return n;}
    private static void supply(GameTestHelper h,int amount,double valve,boolean two){
        TrainIntegrationTests.withTrain(h,amount,two,setup->{},t->{
            t.throttle=valve;int coal=items(t,Items.COAL);double previous=0;
            for(int i=0;i<20;i++){
                TrainPhysics.manual(t,1,false);t.tick(h.getLevel());
                int stock=water(t);double buffer=t.carriages.stream().flatMap(c->TrainPhysics.carriage(c).water.values().stream()).mapToDouble(Double::doubleValue).sum();
                double consumed=amount-stock-buffer;
                h.assertTrue(stock>=0&&buffer>=0&&buffer<1.00000001&&consumed>=previous-1e-8&&consumed<=amount+1e-8,"Every admission is paid once from finite mounted water");previous=consumed;
                h.assertTrue(Double.isFinite(t.speed)&&Math.abs(t.speed)<1,"Small finite supplies cannot create unbounded momentum");
            }
            if(amount==0||valve==0)h.assertTrue(t.speed==0,"Zero usable water or admission grants no traction");
            if(valve==0)h.assertTrue(items(t,Items.COAL)==coal&&water(t)==amount,"Closed admission consumes neither water nor new fuel");
            if(amount>0&&valve>0)h.assertTrue(previous>0,"Every nonzero admission consumes actual water");
            h.succeed();
        },true);
    }
    private static void structure(GameTestHelper h,boolean large,String op){
        TrainTestFixture.create(h,"flat",large,false,setup->{
            if(op.equals("no_lever"))setup.setBlock(new BlockPos(8,4,5),Blocks.AIR);
            if(op.equals("second_lever"))setup.setBlock(new BlockPos(8,4,6),com.diliupengyun.differential_create.content.ModContent.GEAR_LEVER.getDefaultState());
            if(op.equals("extra_mass")){setup.setBlock(new BlockPos(2,6,4),Blocks.STONE);setup.setBlock(new BlockPos(2,7,4),Blocks.STONE);for(int x=2;x<11;x++)for(int z=4;z<10;z++)setup.setBlock(new BlockPos(x,8,z),Blocks.STONE);}
        },f->{
            var cs=TrainPhysics.carriage(f.train.carriages.getFirst());var layout=cs.layout;
            h.assertTrue(layout.drives.size()==1&&layout.boilers.size()==1,"Native wheel variant captures exactly one engine and boiler");
            if(op.equals("no_lever")){TrainPhysics.shift(f.train,3);h.assertTrue(!TrainPhysics.hasLever(f.train)&&TrainPhysics.state(f.train).gear==1,"A train without a gear lever ignores shift requests");}
            if(op.equals("second_lever")){h.assertTrue(layout.levers.size()==2,"Both actual gear levers are assembled");TrainPhysics.shift(f.train,3);h.assertTrue(TrainPhysics.state(f.train).gear==3,"Shared gear update retains one train state");}
            f.tick(1,false);
            h.assertTrue(TrainPhysics.state(f.train).mass==layout.blocks*TrainMechanics.BLOCK_MASS,"Captured non-air structure determines actual inertia");
            if(op.equals("extra_mass"))h.assertTrue(layout.blocks>100,"Large glued structure participates in native assembly");
            if(op.equals("readback")){double speed=f.train.speed;int stock=f.water(),fuel=f.items(Items.COAL);f.reload();h.assertTrue(f.train.speed==speed&&f.water()==stock&&f.items(Items.COAL)==fuel,"Loaded native readback preserves momentum and cargo");}
            f.finish();
        });
    }
    private static void cross(GameTestHelper h,String resource,boolean reverse){
        TrainTestFixture.create(h,"flat",false,false,setup->{},first->{
            first.close();
            TrainTestFixture.create(h,"flat",false,false,TrainIntegrationTests::unpower,second->{
                second.close();var a=first.train.carriages.getFirst();var b=second.train.carriages.getFirst();
                var la=TrainPhysics.carriage(a).layout;var lb=TrainPhysics.carriage(b).layout;
                h.assertTrue(lb.drives.isEmpty()&&lb.installedEngines==0,"Storage carriage was assembled with an ordinary unpowered bogey");
                if(resource.equals("water")||resource.equals("both"))a.storage.getFluids().drain(Integer.MAX_VALUE,FluidAction.EXECUTE);
                if(resource.equals("fuel")||resource.equals("both")){var inv=a.storage.getFuelItems();for(int i=0;i<inv.getSlots();i++)inv.extractItem(i,64,false);}
                var t=new Train(UUID.randomUUID(),null,null,List.of(a,b),List.of(8),false,0);int initial=water(t),fuel=items(t,Items.COAL);
                for(int i=0;i<20;i++){TrainPhysics.manual(t,reverse?-1:1,false);t.tick(h.getLevel());}
                h.assertTrue(t.speed*(reverse?-1:1)>0&&water(t)<initial&&items(t,Items.COAL)==fuel-1,"Actual mounted inventory on another carriage supplies water and fuel in either direction");
                h.assertTrue(TrainPhysics.carriage(a).layout==la&&TrainPhysics.carriage(b).layout.drives.isEmpty(),"Unpowered storage carriage contributes no duplicate drive");h.succeed();
            });
        });
    }
    private static void coupled(GameTestHelper h,String track,boolean reverse){
        TrainTestFixture.create(h,track,false,false,setup->{},first->{
            first.close();
            TrainTestFixture.create(h,track,false,false,TrainIntegrationTests::unpower,second->{
                var f=TrainTestFixture.couple(first,second);var a=f.train.carriages.getFirst();var b=f.train.carriages.getLast();
                a.storage.getFluids().drain(Integer.MAX_VALUE,FluidAction.EXECUTE);
                var fuel=a.storage.getFuelItems();for(int i=0;i<fuel.getSlots();i++)fuel.extractItem(i,64,false);
                double initial=a.getLeadingPoint().position;int stock=water(f.train),coal=items(f.train,Items.COAL);
                for(int i=0;i<20;i++)f.tick(reverse?-1:1,false);
                h.assertTrue(!f.train.derailed&&Double.isFinite(f.train.speed)&&!a.blocked&&!b.blocked,"Actual coupled carriages retain spacing on native "+track+" track");
                h.assertTrue((reverse?-1:1)*(a.getLeadingPoint().position-initial)>0,"Loaded coupled train follows signed native rail travel");
                h.assertTrue(water(f.train)<stock&&items(f.train,Items.COAL)==coal-1,"Moving locomotive consumes supplies from the actual unpowered coupled carriage");
                int blocks=f.train.carriages.stream().mapToInt(c->TrainPhysics.carriage(c).layout.blocks).sum();
                h.assertTrue(TrainPhysics.state(f.train).mass==blocks*TrainMechanics.BLOCK_MASS,"Both actual carriages contribute inertia once");f.finish();
            });
        });
    }
}
