package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.gametest.*;

/** Native carriage capture and persistence of separated, partly overlapping silhouettes. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainAerodynamicsTests {
    private TrainAerodynamicsTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests=new ArrayList<TestFunction>();
        for(var direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))
            for(String mode:List.of("hidden","side","height","inverted","reload")) {
                String name="train_air_shadow_"+direction.getName()+"_"+mode;
                if(!name.matches(System.getProperty("differentialcreate.trains.select",".*"))) continue;
                tests.add(new TestFunction(name,name,"differentialcreate_trains:kineticphysicstests.physics_test",40,0,true,
                    h->check(h,direction,mode)));
            }
        return tests;
    }
    private static TrainAssembly.Layout capture(GameTestHelper h,Direction direction,Collection<BlockPos> cells) {
        var cc=new CarriageContraption(direction);cc.anchor=h.absolutePos(new BlockPos(2,2,2));
        for(var pos:cells) cc.getBlocks().put(pos,new StructureBlockInfo(pos,AllBlocks.RAILWAY_CASING.getDefaultState(),null));
        // Saved air cells are ignored instead of enlarging the silhouette.
        var air=new BlockPos(30,30,30);
        cc.getBlocks().put(air,new StructureBlockInfo(air,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),null));
        return TrainAssembly.capture(h.getLevel(),cc);
    }
    private static BlockPos point(Direction direction,int lateral,int height,int depth) {
        return new BlockPos(direction.getStepZ()*lateral+direction.getStepX()*depth,height,
            -direction.getStepX()*lateral+direction.getStepZ()*depth);
    }
    private static Carriage carriage(TrainAssembly.Layout layout,boolean inverted) {
        var bogey=new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),inverted,new net.minecraft.nbt.CompoundTag());
        var c=new Carriage(bogey,null,0);
        ((CarriageSteamState.Holder)c).differentialcreate$steam(new CarriageSteamState(layout));
        return c;
    }
    private static void check(GameTestHelper h,Direction direction,String mode) {
        var front=new ArrayList<BlockPos>();
        for(int x=-1;x<=1;x++) for(int y=0;y<4;y++) front.add(point(direction,x,y,0));
        // Two separated opaque layers in the same carriage produce one projection.
        for(int x=-1;x<=1;x++) for(int y=0;y<4;y++) front.add(point(direction,x,y,7));
        var leading=capture(h,direction,front);
        int x=mode.equals("side")?2:0,y=mode.equals("height")?4:0;
        var trailing=capture(h,direction,List.of(point(direction,x,y,0),point(direction,x,y,9)));
        h.assertTrue(leading.frontalArea==12 && trailing.frontalArea==1,"Native capture deduplicates separated opaque layers and ignores air");
        if(mode.equals("reload")) {
            var tag=leading.write();h.assertTrue(!tag.contains("Area"),"No old area-only summary is written");
            leading=TrainAssembly.Layout.read(tag);trailing=TrainAssembly.Layout.read(trailing.write());
            h.assertTrue(leading.frontalProjection.size()==12,"Native saved layout retains the actual silhouette");
        }
        var a=carriage(leading,false);var b=carriage(trailing,mode.equals("inverted"));
        var train=new Train(UUID.randomUUID(),null,new TrackGraph(),List.of(a,b),List.of(30),false,0);
        double expected=mode.equals("hidden")||mode.equals("reload")||mode.equals("inverted")&&!b.leadingBogey().isUpsideDown()?12:13;
        h.assertTrue(TrainPhysics.frontalArea(train)==expected,"Whole-train shading counts only exposed side/height cells, including inverted bogeys: area="
            +TrainPhysics.frontalArea(train)+" inverted="+b.leadingBogey().isUpsideDown());
        h.assertTrue(a.anyAvailableEntity()==null && b.anyAvailableEntity()==null,"Shadowing remains authoritative with every carriage entity unloaded");
        var independent=new Train(UUID.randomUUID(),null,new TrackGraph(),List.of(carriage(trailing,false)),List.of(),false,0);
        h.assertTrue(TrainPhysics.frontalArea(independent)==1,"An independent train never borrows another train's shadow");
        h.succeed();
    }
}
