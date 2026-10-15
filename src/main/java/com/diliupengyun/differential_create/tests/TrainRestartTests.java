package com.diliupengyun.differential_create.tests;

import java.io.IOException;
import java.util.*;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Disk save and reload in two separate server JVMs. No Train.read is called by resume. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainRestartTests {
    private static final SteamHosePort PORT = SteamHosePort.fromHit(BlockPos.ZERO, Direction.NORTH, new net.minecraft.world.phys.Vec3(.25, .6875, 0));
    @GameTestGenerator public static Collection<TestFunction> cases(){
        String phase=System.getProperty("differentialcreate.trains.restart","");
        if(!phase.equals("prepare")&&!phase.equals("resume"))return List.of();
        return List.of(new TestFunction("train_restart_"+phase,"train_restart_"+phase,"differentialcreate_trains:kineticphysicstests.physics_test",400,0,true,h->restart(h,phase.equals("resume"))));
    }
    private static void restart(GameTestHelper h,boolean resume){
        var path=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("train-coverage-restart.nbt");
        if(!resume){
            TrainTestFixture.create(h,"flat",false,false,false,PORT,setup->setup.setBlock(new BlockPos(7,4,9),AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING,Direction.NORTH)),f->{
                f.tick(1,false);TrainPhysics.shift(f.train,0);f.train.speed=0;
                // Move the native travelling points away from GameTest's reset
                // volume, then let real carriage management load that area.
                f.train.forEachTravellingPoint(p->p.position+=1000);
                var c=f.train.carriages.getFirst();c.updateContraptionAnchors();
                var centre=BlockPos.containing(c.getLeadingPoint().getPosition(f.graph));
                force(h,centre);c.manageEntities(h.getLevel());
                h.runAfterDelay(25,()->{
                    h.assertTrue(f.entity()!=null,"Restart fixture loads an actual carriage outside the reset test volume");
                    var ctx=f.actor();var target=f.contact(ctx);h.getLevel().setBlockAndUpdate(target,Blocks.STONE.defaultBlockState());f.train.speed=.005;
                    h.runAfterDelay(75,()->{
                        double paid=f.actor().data.getDouble("DCActorTravel");
                        h.assertTrue(paid>0&&f.train.speed==0&&!h.getLevel().getBlockState(target).isAir(),"Restart checkpoint contains a real stopped partial cut");
                        var tag=new CompoundTag();tag.putUUID("Train",f.train.id);tag.putLong("Target",target.asLong());tag.putDouble("Paid",paid);
                        tag.putInt("Water",f.water());tag.putInt("Coal",f.items(Items.COAL));tag.putInt("Gear",TrainPhysics.state(f.train).gear);
                        SteamHosePort.write(tag,PORT);
                        tag.putLong("PreviousJvm",ProcessHandle.current().pid());
                        try{NbtIo.writeCompressed(tag,path);}catch(IOException e){throw new IllegalStateException(e);}
                        Create.RAILWAYS.markTracksDirty();h.getLevel().getServer().saveEverything(true,true,false);
                        System.out.println("DC_TRAIN_RESTART_PREPARE_PASS jvm="+ProcessHandle.current().pid()+" train="+f.train.id+" paid="+paid);
                        h.succeed(); // retain the native manager and region-file state for resume
                    });
                });
            });
        }else{
            final CompoundTag tag;
            try{tag=NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap());}catch(IOException e){throw new IllegalStateException("Resume needs the saved named test world",e);}
            h.assertTrue(ProcessHandle.current().pid()!=tag.getLong("PreviousJvm"),"Resume runs in a genuinely new server JVM");
            var train=Create.RAILWAYS.trains.get(tag.getUUID("Train"));h.assertTrue(train!=null,"Native railway manager loads the saved train from disk");
            var target=BlockPos.of(tag.getLong("Target"));force(h,target);
            h.runAfterDelay(25,()->{
                var carriage=train.carriages.getFirst();carriage.manageEntities(h.getLevel());var entity=carriage.anyAvailableEntity();
                h.assertTrue(entity!=null,"Native disk reload creates the saved carriage in its original chunk");
                var port=SteamHosePort.read(tag);
                h.assertTrue(PORT.equals(port)&&port.equals(SteamHosePort.read(carriage.leadingBogey().bogeyData))
                    && port.equals(SteamHosePort.read(entity.getContraption().getBlocks().get(BlockPos.ZERO).nbt().getCompound("BogeyData"))),
                    "New server JVM restores the exact hose face and point in the train and moving block data");
                var actor=entity.getContraption().getActors().stream().map(a->a.getRight()).filter(c->AllBlocks.MECHANICAL_DRILL.has(c.state)).findFirst().orElseThrow();
                h.assertTrue(train.speed==0&&TrainPhysics.state(train).gear==tag.getInt("Gear")&&actor.data.getDouble("DCActorTravel")==tag.getDouble("Paid"),"Restart preserves paid work and neutral parking, without free progress");
                int water=0,coal=0;var fluids=carriage.storage.getFluids();for(int i=0;i<fluids.getTanks();i++)water+=fluids.getFluidInTank(i).getAmount();
                var items=carriage.storage.getAllItems();for(int i=0;i<items.getSlots();i++)if(items.getStackInSlot(i).is(Items.COAL))coal+=items.getStackInSlot(i).getCount();
                h.assertTrue(water==tag.getInt("Water")&&coal==tag.getInt("Coal"),"Restart neither restores consumed supplies nor duplicates mounted cargo");
                TrainPhysics.shift(train,1);
                class Run{int ticks;void tick(){TrainPhysics.manual(train,1,false);if(h.getLevel().getBlockState(target).isAir()){
                    h.assertTrue(train.speed>=0&&TrainPhysics.carriage(carriage).layout.drives.size()==1,"Saved steam drive resumes the actual native cut after restart");
                    Create.RAILWAYS.removeTrain(train.id);Create.RAILWAYS.trackNetworks.remove(train.graph.id);carriage.forEachPresentEntity(e->e.discard());
                    System.out.println("DC_TRAIN_RESTART_RESUME_PASS jvm="+ProcessHandle.current().pid()+" previous="+tag.getLong("PreviousJvm"));h.succeed();return;
                }if(++ticks>250)h.fail("Saved train did not complete its paid work after new-process restart");h.runAfterDelay(1,this::tick);}}h.runAfterDelay(1,new Run()::tick);
            });
        }
    }
    private static void force(GameTestHelper h,BlockPos centre){var chunk=new ChunkPos(centre);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)h.getLevel().setChunkForced(chunk.x+x,chunk.z+z,true);h.getLevel().getChunkAt(centre);}
}
