package com.diliupengyun.differential_create.tests;

import java.util.*;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.*;

/** Operations on real train actors and mounted storage, not minecart stand-ins. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainOperationTests {
    private static final BlockPos TOOL=new BlockPos(7,4,9);
    private static void add(List<TestFunction> tests,String name,int timeout,Consumer<GameTestHelper> run){
        if(name.matches(System.getProperty("differentialcreate.trains.select",".*")))
            tests.add(new TestFunction(name,name,"differentialcreate_trains:kineticphysicstests.physics_test",timeout,0,true,run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases(){
        var tests=new ArrayList<TestFunction>();
        for(boolean saw:new boolean[]{false,true})for(String op:new String[]{"partial","disable","remove","replace","same_type","bedrock","reload","powered_finish"})
            add(tests,"train_tool_"+(saw?"saw_":"drill_")+op,260,h->cut(h,saw,op));
        for(boolean fluid:new boolean[]{false,true})for(String op:new String[]{"idle","creeping","fast","redstone","removed","disabled","reconnect","reload","supply","out_of_range","misaligned","wrong_face","wrong_kind","full_storage"})
            add(tests,"train_port_"+(fluid?"fluid_":"item_")+op,320,h->port(h,fluid,op));
        for(String track:new String[]{"flat","up","down","curve"})for(boolean large:new boolean[]{false,true})for(int direction:new int[]{-1,1})
            add(tests,"train_track_"+track+(large?"_large":"_small")+(direction<0?"_reverse":"_forward"),120,h->track(h,track,large,direction));
        for(String op:new String[]{"water_exhaust_refill","fuel_exhaust_refill","closed_burning","no_heat","no_lever","rapid_shift","npc_shift","manual_takeover"})
            add(tests,"train_transition_"+op,220,h->transition(h,op));
        return tests;
    }
    private static void cut(GameTestHelper h,boolean saw,String op){
        TrainTestFixture.create(h,"flat",false,false, setup->setup.setBlock(TOOL,(saw?AllBlocks.MECHANICAL_SAW:AllBlocks.MECHANICAL_DRILL)
            .getDefaultState().setValue(BlockStateProperties.FACING,Direction.NORTH)),f->{
            var ctx=f.actor();var target=f.contact(ctx);var original=(saw?Blocks.OAK_LOG:Blocks.STONE).defaultBlockState();
            h.getLevel().setBlockAndUpdate(target,original);TrainPhysics.shift(f.train,0);f.train.speed=.005;
            class Run{int ticks,changedAt;boolean changed;double paid;void tick(){
                try{
                    ticks++;TrainPhysics.manual(f.train,changed&&op.equals("powered_finish")?1:0,false);
                    if(!changed&&ticks>=75){
                        paid=ctx.data.getDouble("DCActorTravel");
                        h.assertTrue(paid>0&&f.train.speed==0&&h.getLevel().getBlockState(target).equals(original),"Finite initial momentum produces only a partial train cut: paid="+paid+" speed="+f.train.speed+" target="+target+" contact="+f.contact(ctx)+" state="+h.getLevel().getBlockState(target)+" actor="+ctx.state+" data="+ctx.data);
                        switch(op){
                            case "disable"->f.entity().getContraption().setActorsActive(ItemStack.EMPTY,false);
                            case "remove"->h.getLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());
                            case "replace"->h.getLevel().setBlockAndUpdate(target,(saw?Blocks.BIRCH_LOG:Blocks.DEEPSLATE).defaultBlockState());
                            case "same_type"->{h.getLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(target,original);}
                            case "bedrock"->h.getLevel().setBlockAndUpdate(target,Blocks.BEDROCK.defaultBlockState());
                            case "reload"->f.reload();
                            case "powered_finish"->TrainPhysics.shift(f.train,1);
                        }changed=true;changedAt=ticks;
                    }
                    if(changed&&ticks>=changedAt+10&&!op.equals("powered_finish")){
                        var now=f.actor();double after=now.data.getDouble("DCActorTravel");
                        h.assertTrue(op.equals("partial")||op.equals("reload")?after==paid:after==0,"Operation preserves only work belonging to its live target: "+op+" paid="+paid+" after="+after);
                        h.assertTrue(f.train.speed==0,"Target transitions do not restore spent momentum");f.finish();return;
                    }
                    if(changed&&op.equals("powered_finish")){
                        if(h.getLevel().getBlockState(target).isAir()){h.assertTrue(f.items(saw?Items.OAK_LOG:Items.COBBLESTONE)>0,"Native cutting drops enter train inventory");f.finish();return;}
                    }
                    if(ticks>220)h.fail("Train tool failed to finish: "+op+" data="+f.actor().data+" speed="+f.train.speed);
                    h.runAfterDelay(1,this::tick);
                }catch(RuntimeException|Error e){f.close();throw e;}
            }}h.runAfterDelay(1,new Run()::tick);
        });
    }
    private static void port(GameTestHelper h,boolean fluid,String op){
        var block=fluid?AllBlocks.PORTABLE_FLUID_INTERFACE:AllBlocks.PORTABLE_STORAGE_INTERFACE;
        TrainTestFixture.create(h,"flat",false,false,setup->setup.setBlock(TOOL,block.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST)),f->{
            var ctx=f.actor();var body=f.entity().toGlobalVector(Vec3.atCenterOf(ctx.localPos),1);
            var normal=f.entity().applyRotation(Vec3.atLowerCornerOf(Direction.EAST.getNormal()),1);var direction=Direction.getNearest(normal.x,normal.y,normal.z);
            var target=BlockPos.containing(body).relative(direction,op.equals("out_of_range")?4:2).offset(0,op.equals("misaligned")?1:0,0);
            var fixed=op.equals("wrong_kind")?fluid?AllBlocks.PORTABLE_STORAGE_INTERFACE:AllBlocks.PORTABLE_FLUID_INTERFACE:block;
            h.getLevel().setBlockAndUpdate(target,fixed.getDefaultState().setValue(BlockStateProperties.FACING,op.equals("wrong_face")?direction:direction.getOpposite()));
            var owner=(PortableStorageInterfaceBlockEntity)h.getLevel().getBlockEntity(target);var state=((PortableEndpoint)owner).differentialcreate$docking();
            TrainPhysics.shift(f.train,0);f.train.speed=op.equals("fast")?.4:op.equals("creeping")?.001:0;
            double initial=f.train.carriages.getFirst().getLeadingPoint().position;
            class Run{int ticks,opened=-1;boolean acted;DockingSession first;net.neoforged.neoforge.fluids.capability.IFluidHandler oldFluid;IItemHandler oldItem;
                void tick(){try{
                    ticks++;TrainPhysics.manual(f.train,op.equals("supply")&&acted?1:0,false);
                    if((op.equals("out_of_range")||op.equals("misaligned")||op.equals("wrong_face")||op.equals("wrong_kind"))&&ticks>=60){h.assertTrue(opened<0&&!owner.canTransfer(),"Train interface rejects invalid geometry or a mismatched storage kind");f.finish();return;}
                    if(op.equals("fast")&&ticks>=20){h.assertTrue(!owner.canTransfer()&&f.train.carriages.getFirst().getLeadingPoint().position>initial+2,"Fast train crosses a missed port without teleporting or free braking");f.finish();return;}
                    if(state.phase==DockingState.Phase.TRANSFER&&opened<0){opened=ticks;first=state.session();
                        h.assertTrue(owner.canTransfer()&&Math.abs(f.train.speed)<1e-6,"Native train port opens only after physical parking");
                        if(fluid)oldFluid=h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK,target,null);else oldItem=h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,target,null);
                    }
                    if(opened>=0&&!acted){acted=true;
                        if(op.equals("supply")){
                            if(fluid){oldFluid.drain(Integer.MAX_VALUE,FluidAction.EXECUTE);int capacity=0;for(int i=0;i<oldFluid.getTanks();i++)capacity+=oldFluid.getTankCapacity(i);h.assertTrue(oldFluid.fill(new FluidStack(Fluids.WATER,capacity),FluidAction.EXECUTE)==capacity,"Real fluid interface refills only reserve tanks");h.assertTrue(f.train.carriages.getFirst().storage.getFluids().storages.keySet().stream().noneMatch(TrainPhysics.carriage(f.train.carriages.getFirst()).layout.boilers::containsKey),"Boilers expose no extra storage to the real interface");}
                            else{for(int i=0;i<oldItem.getSlots();i++)oldItem.extractItem(i,64,false);h.assertTrue(ItemHandlerHelper.insertItem(oldItem,new ItemStack(Items.COAL),false).isEmpty(),"Real item interface supplies burner fuel");}
                            TrainPhysics.shift(f.train,1);h.getLevel().setBlockAndUpdate(target.above(),Blocks.REDSTONE_BLOCK.defaultBlockState());owner.neighbourChanged();
                        }
                        else if(op.equals("full_storage")){
                            if(fluid){int capacity=0;for(int i=0;i<oldFluid.getTanks();i++)capacity+=oldFluid.getTankCapacity(i);for(int i=0;i<=oldFluid.getTanks();i++)if(oldFluid.fill(new FluidStack(Fluids.WATER,capacity),FluidAction.EXECUTE)==0)break;int before=f.water();h.assertTrue(before==capacity&&oldFluid.fill(new FluidStack(Fluids.WATER,1),FluidAction.EXECUTE)==0&&f.water()==before,"Full mounted train tanks reject overflow without losing water");}
                            else{for(int i=0;i<oldItem.getSlots();i++)oldItem.insertItem(i,new ItemStack(Items.DIAMOND,64),false);int before=f.items(Items.DIAMOND);h.assertTrue(ItemHandlerHelper.insertItem(oldItem,new ItemStack(Items.DIAMOND),false).is(Items.DIAMOND)&&f.items(Items.DIAMOND)==before,"Full mounted train cargo rejects overflow without losing items");}
                        }
                        else if(op.equals("removed"))h.getLevel().removeBlock(target,false);
                        else if(op.equals("disabled"))f.entity().getContraption().setActorsActive(new ItemStack(block.get()),false);
                        else if(op.equals("redstone")||op.equals("reconnect")){h.getLevel().setBlockAndUpdate(target.above(),Blocks.REDSTONE_BLOCK.defaultBlockState());owner.neighbourChanged();}
                        else if(op.equals("reload")){f.reload();h.assertTrue(!first.valid(),"Native train reload invalidates old interface session");}
                        else if(fluid){int before=f.water();h.assertTrue(oldFluid.drain(125,FluidAction.SIMULATE).getAmount()==125&&f.water()==before,"Train fluid simulation does not debit water");h.assertTrue(oldFluid.drain(125,FluidAction.EXECUTE).getAmount()==125&&f.water()==before-125,"Train interface drains real mounted water once");}
                        else{int before=f.items(Items.DIAMOND);h.assertTrue(ItemHandlerHelper.insertItem(oldItem,new ItemStack(Items.DIAMOND),true).isEmpty()&&f.items(Items.DIAMOND)==before,"Train item simulation changes no cargo");h.assertTrue(ItemHandlerHelper.insertItem(oldItem,new ItemStack(Items.DIAMOND),false).isEmpty()&&f.items(Items.DIAMOND)==before+1,"Train port inserts exactly one item");}
                    }
                    if(op.equals("supply")&&opened>=0&&ticks>opened+20){checkOld();h.assertTrue(f.train.speed>0&&TrainPhysics.carriage(f.train.carriages.getFirst()).burners.values().stream().anyMatch(b->b.fuel>0),"Interface supply is consumed by the native boiler and restores traction");if(!fluid)h.assertTrue(f.items(Items.COAL)==0,"The supplied coal is consumed exactly once");f.finish();return;}
                    if(opened>=0&&ticks>opened+12&&(op.equals("removed")||op.equals("disabled")||op.equals("redstone")||op.equals("reload"))){checkOld();f.finish();return;}
                    if(op.equals("reconnect")&&opened>=0&&ticks==opened+12){checkOld();h.getLevel().removeBlock(target.above(),false);owner.neighbourChanged();}
                    if(op.equals("reconnect")&&opened>=0&&ticks>opened+12&&state.phase==DockingState.Phase.TRANSFER){checkOld();h.assertTrue(state.session().valid(),"Stationary train establishes a fresh port generation");f.finish();return;}
                    if(opened>=0&&state.phase==DockingState.Phase.IDLE&&!op.equals("reconnect")&&!op.equals("supply")){checkOld();h.assertTrue(f.train.speed==0,"Disconnect cannot relaunch the parked train");f.finish();return;}
                    if(ticks>270)h.fail("Train port did not finish: "+op+" phase="+state.phase+" failure="+state.failure+" managed="+state.managed+" body="+body+" current="+f.entity().toGlobalVector(Vec3.atCenterOf(ctx.localPos),1)+" target="+target+" initialFace="+normal+" face="+f.entity().applyRotation(Vec3.atLowerCornerOf(Direction.EAST.getNormal()),1)+" actor="+ctx.state+" data="+ctx.data);
                    h.runAfterDelay(1,this::tick);
                }catch(RuntimeException|Error e){f.close();throw e;}}
                void checkOld(){h.assertTrue(!first.valid(),"Released train port session stays invalid");if(fluid)h.assertTrue(oldFluid.fill(new FluidStack(Fluids.WATER,1),FluidAction.EXECUTE)==0,"Old fluid capability cannot modify train cargo");else h.assertTrue(ItemHandlerHelper.insertItem(oldItem,new ItemStack(Items.DIAMOND),false).is(Items.DIAMOND),"Old item capability cannot modify train cargo");}
            }h.runAfterDelay(1,new Run()::tick);
        });
    }
    private static void track(GameTestHelper h,String track,boolean large,int direction){
        TrainTestFixture.create(h,track,large,false,setup->{},f->{
            TrainPhysics.shift(f.train,0);f.train.speed=direction*.05;double before=f.train.carriages.getFirst().getLeadingPoint().position;
            for(int i=0;i<20;i++)f.tick(0,false);
            double after=f.train.carriages.getFirst().getLeadingPoint().position;
            h.assertTrue(Double.isFinite(f.train.speed)&&direction*(after-before)>0,"Native "+track+" track accepts signed finite travel");
            if(track.equals("up")||track.equals("down"))h.assertTrue(Math.abs(TrainPhysics.state(f.train).grade)>.05,"Grade comes from native travelling points");
            if(track.equals("curve"))h.assertTrue(f.train.carriages.getFirst().getLeadingPoint().edge.isTurn(),"Actual Bezier track traversed");
            f.finish();
        });
    }
    private static void transition(GameTestHelper h,String op){
        TrainIntegrationTests.withTrain(h,4000,false,setup->{if(op.equals("no_heat"))setup.setBlock(TrainTestFixture.BOILER.below(),Blocks.AIR);if(op.equals("no_lever"))setup.setBlock(new BlockPos(8,4,5),Blocks.AIR);},t->{
            var c=t.carriages.getFirst();var cs=TrainPhysics.carriage(c);var inventory=c.storage.getFuelItems();
            var reserve=c.storage.getFluids().storages.entrySet().stream().filter(e->!cs.layout.boilers.containsKey(e.getKey())).findFirst().orElseThrow().getValue();
            if(op.equals("water_exhaust_refill")){reserve.drain(Integer.MAX_VALUE,FluidAction.EXECUTE);reserve.fill(new FluidStack(Fluids.WATER,5),FluidAction.EXECUTE);}
            if(op.equals("fuel_exhaust_refill"))for(int slot=0;slot<inventory.getSlots();slot++)inventory.extractItem(slot,64,false);
            for(int i=0;i<40;i++){TrainPhysics.manual(t,1,false);t.tick(h.getLevel());}
            if(op.endsWith("exhaust_refill")){
                if(op.startsWith("water")){h.assertTrue(reserve.getFluidInTank(0).isEmpty(),"Finite reserve is exhausted");reserve.fill(new FluidStack(Fluids.WATER,500),FluidAction.EXECUTE);}
                else{h.assertTrue(cs.burners.values().stream().allMatch(b->b.fuel==0),"Empty fuel storage provides only native passive heat");ItemHandlerHelper.insertItem(inventory,new ItemStack(Items.COAL),false);}
                double before=t.speed;for(int i=0;i<20;i++){TrainPhysics.manual(t,1,false);t.tick(h.getLevel());}h.assertTrue(t.speed>before,"Real resupply restores finite traction");
            }else if(op.equals("no_heat"))h.assertTrue(t.speed==0,"Missing heat provides no steam traction");
            else if(op.equals("no_lever")){TrainPhysics.shift(t,3);h.assertTrue(TrainPhysics.state(t).gear==1&&t.speed>0,"No lever keeps the default first gear");}
            else if(op.equals("closed_burning")){int remaining=cs.burners.values().iterator().next().remaining;int stock=inventory.getStackInSlot(0).getCount();t.throttle=0;for(int i=0;i<10;i++){TrainPhysics.manual(t,1,false);t.tick(h.getLevel());}h.assertTrue(cs.burners.values().iterator().next().remaining==remaining-10&&inventory.getStackInSlot(0).getCount()==stock,"Closing admission keeps fuel expiry but takes no new coal");}
            else if(op.equals("rapid_shift")){double speed=t.speed;for(int gear:new int[]{3,0,2,1,3,0})TrainPhysics.shift(t,gear);h.assertTrue(t.speed==speed,"Repeated shift commands cannot inject momentum");}
            else{var s=TrainPhysics.state(t);s.gear=1;s.engineRpm=450;s.lastShift=s.ticks-41;t.runtime.paused=false;TrainPhysics.navigate(t);if(op.equals("manual_takeover"))TrainPhysics.manual(t,1,false);t.tick(h.getLevel());h.assertTrue(s.gear==(op.equals("npc_shift")?2:1),"Only an unclaimed navigation command may select gears");}
            h.succeed();
        },true);
    }
}
