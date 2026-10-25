package com.diliupengyun.differential_create.tests;

import java.util.*;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.ItemHandlerHelper;

@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainWorkBlockTests {
    private static final class RollerProfile extends com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour {
        com.simibubi.create.content.contraptions.actors.roller.PaveTask profile(com.simibubi.create.content.contraptions.behaviour.MovementContext context){return createHeightProfileForTracks(context);}
    }
    private static void add(List<TestFunction> tests,String name,Consumer<GameTestHelper> run){if(name.matches(System.getProperty("differentialcreate.trains.select",".*")))tests.add(new TestFunction(name,name,"differentialcreate_trains:kineticphysicstests.physics_test",500,0,true,run));}
    @GameTestGenerator public static Collection<TestFunction> cases(){
        var tests=new ArrayList<TestFunction>();
        for(String kind:new String[]{"harvester","plough","roller"})for(String op:new String[]{"partial","disable","replace","reload","powered_finish"})add(tests,"train_work_"+kind+"_"+op,h->farm(h,kind,op));
        for(String op:new String[]{"partial","disable","replace","reload","powered_finish"})add(tests,"train_work_roller_reverse_"+op,h->farm(h,"roller",op,-1));
        for(String kind:new String[]{"plant","place"})for(String op:new String[]{"unpowered","rolling","disabled","reload"})add(tests,"train_hand_"+kind+"_"+op,h->hand(h,kind,op));
        return tests;
    }
    private static void farm(GameTestHelper h,String kind,String op){
        farm(h,kind,op,1);
    }
    private static void farm(GameTestHelper h,String kind,String op,int direction){
        boolean harvest=kind.equals("harvester"),roller=kind.equals("roller");
        var tool=new BlockPos(7,roller?8:4,9);
        var block=harvest?AllBlocks.MECHANICAL_HARVESTER:roller?AllBlocks.MECHANICAL_ROLLER:AllBlocks.MECHANICAL_PLOUGH;
        TrainTestFixture.create(h,"flat",false,false,setup->{if(roller)for(int y=4;y<8;y++)setup.setBlock(new BlockPos(7,y,9),Blocks.STONE);setup.setBlock(tool,block.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,direction<0?Direction.SOUTH:Direction.NORTH));if(roller){var be=(com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity)setup.getBlockEntity(tool);be.filtering.setFilter(new ItemStack(Items.STONE));be.mode.setValue(1);}},f->{
            var ctx=f.actor();var target=f.contact(ctx);var ground=target.below();
            BlockPos[] pavingTarget={target};
            var pavingCells=new ArrayList<BlockPos>();
            h.getLevel().setBlockAndUpdate(ground,(harvest?Blocks.FARMLAND:roller?Blocks.BEDROCK:Blocks.DIRT).defaultBlockState());
            h.getLevel().setBlockAndUpdate(target,harvest?Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7,7):Blocks.AIR.defaultBlockState());
            if(roller){ItemHandlerHelper.insertItem(f.train.carriages.getFirst().storage.getAllItems(),new ItemStack(Items.STONE,4),false);
                var profile=new RollerProfile().profile(ctx);h.assertTrue(profile!=null&&!profile.keys().isEmpty(),"Train roller derives a native rail height profile");
                for(var cell:profile.keys()){var at=BlockPos.containing(cell.getFirst(),profile.get(cell),cell.getSecond());pavingCells.add(at);h.getLevel().setBlockAndUpdate(at,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(at.below(),Blocks.BEDROCK.defaultBlockState());}
            }
            TrainPhysics.shift(f.train,0);f.train.speed=direction*.0005;
            class Run{int ticks,changedAt;double paid;String firstMotion="";boolean changed;void tick(){try{
                ticks++;TrainPhysics.manual(f.train,changed&&op.equals("powered_finish")?direction:0,false);
                if(ticks==1&&roller)firstMotion="motion="+ctx.relativeMotion+" speed="+f.train.speed+" filter="+ctx.getFilterFromBE().item()+" pave="+pavingCells+" job="+ctx.data;
                if(!changed&&ticks>=20){paid=f.actor().data.getCompound("DCActorJob").getDouble("Travel");h.assertTrue(paid>0&&ContraptionActorWork.of(f.actor()).pending()&&f.train.speed==0,"Weak coasting train parks with a partially paid "+kind+" job: "+f.actor().data+" rel="+f.actor().relativeMotion+" stock="+f.items(Items.STONE)+" first="+firstMotion);
                    if(roller)pavingTarget[0]=BlockPos.of(f.actor().data.getCompound("DCActorJob").getLong("TargetPos"));
                    if(op.equals("disable"))f.entity().getContraption().setActorsActive(ItemStack.EMPTY,false);
                    if(op.equals("replace"))h.getLevel().setBlockAndUpdate(roller?pavingTarget[0]:harvest?target:ground,Blocks.STONE.defaultBlockState());
                    if(op.equals("reload"))f.reload();
                    if(op.equals("powered_finish"))TrainPhysics.shift(f.train,1);
                    changed=true;changedAt=ticks;
                }
                if(changed&&!op.equals("powered_finish")&&ticks>=changedAt+10){double now=f.actor().data.getCompound("DCActorJob").getDouble("Travel");h.assertTrue(op.equals("partial")||op.equals("reload")?now==paid:roller?now==0:!ContraptionActorWork.of(f.actor()).pending(),"Stopped job retains only its paid live-target progress: "+kind+"/"+op);if(roller&&(op.equals("disable")||op.equals("replace")))h.assertTrue(f.items(Items.STONE)+(ContraptionActorWork.of(f.actor()).pending()?1:0)==4,"Cancelled train paving returns its reserved material once, including any fresh reservation");f.finish();return;}
                if(changed&&op.equals("powered_finish")){boolean done=harvest?h.getLevel().getBlockState(target).is(Blocks.WHEAT)&&h.getLevel().getBlockState(target).getValue(BlockStateProperties.AGE_7)==0:roller?h.getLevel().getBlockState(pavingTarget[0]).is(Blocks.STONE):h.getLevel().getBlockState(ground).is(Blocks.FARMLAND);if(done){if(!roller)h.assertTrue(!ContraptionActorWork.of(f.actor()).pending(),"Real steam drive completes the paid train job");if(harvest)h.assertTrue(f.items(Items.WHEAT)>0,"Harvested crop enters actual train cargo");if(roller){long placed=pavingCells.stream().filter(p->h.getLevel().getBlockState(p).is(Blocks.STONE)).count();h.assertTrue(f.items(Items.STONE)+(ContraptionActorWork.of(f.actor()).pending()?1:0)+placed==4,"Each actually paved block consumes one material, including the next reservation: placed="+placed+" stock="+f.items(Items.STONE)+" data="+f.actor().data);}f.finish();return;}}
                if(ticks>420)h.fail("Train "+kind+" job timed out: "+f.actor().data+" target="+h.getLevel().getBlockState(target)+" speed="+f.train.speed);
                h.runAfterDelay(1,this::tick);
            }catch(RuntimeException|Error e){f.close();throw e;}}}h.runAfterDelay(1,new Run()::tick);
        });
    }
    private static void hand(GameTestHelper h,String kind,String op){
        boolean plant=kind.equals("plant");var pos=new BlockPos(7,4,9);
        TrainTestFixture.create(h,"flat",false,false,setup->setup.setBlock(pos,AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING,Direction.DOWN)),f->{
            var ctx=f.actor();ctx.blockEntityData.putString("Mode","USE");var job=ContraptionActorWork.of(ctx).hand();
            job.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(plant?Items.WHEAT_SEEDS:Items.COBBLESTONE,4));
            var target=f.contact(ctx);h.getLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(target.below(),plant?Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE,7):Blocks.DIRT.defaultBlockState());
            TrainPhysics.shift(f.train,0);f.train.speed=op.equals("unpowered")?0:.05;
            if(op.equals("disabled"))f.entity().getContraption().setActorsActive(ItemStack.EMPTY,false);
            class Run{int ticks;boolean reloaded;void tick(){try{
                ticks++;TrainPhysics.manual(f.train,0,false);
                if(op.equals("reload")&&!reloaded&&ticks==5){f.reload();reloaded=true;}
                boolean placed=h.getLevel().getBlockState(target).is(plant?Blocks.WHEAT:Blocks.COBBLESTONE);
                if(op.equals("unpowered")||op.equals("disabled")){if(ticks>=30){h.assertTrue(!placed&&ContraptionActorWork.of(f.actor()).hand().cartEnergy()==0,"Inactive train hand cannot grant free placement or energy");f.finish();return;}}
                else if(placed){var work=ContraptionActorWork.of(f.actor()).hand();var data=f.actor().data.getCompound(MovingDeployerWork.TAG);h.assertTrue(data.getDouble("Work")>0,"Train hand placement debits acquired carrier energy");h.assertTrue(work.player().getItemInHand(InteractionHand.MAIN_HAND).getCount()==3,"Successful native train placement consumes exactly one held material");f.finish();return;}
                if(ticks>400)h.fail("Train hand did not place: "+kind+"/"+op+" target="+target+" data="+f.actor().data);
                h.runAfterDelay(1,this::tick);
            }catch(RuntimeException|Error e){f.close();throw e;}}}h.runAfterDelay(1,new Run()::tick);
        });
    }
}
