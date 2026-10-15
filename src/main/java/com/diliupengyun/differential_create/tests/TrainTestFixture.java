package com.diliupengyun.differential_create.tests;

import java.util.*;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.diliupengyun.differential_create.kinetics.TrainActorMotion;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.*;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.*;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;

/** Native assembly and rail travel; a scenario may set its initial momentum once.
 * Tests schedule actual server ticks, never inject replacement speed during work. */
final class TrainTestFixture {
    static final BlockPos BOILER = new BlockPos(2,4,4), WATER = new BlockPos(9,4,8), WHEEL = new BlockPos(6,4,5), CHEST = new BlockPos(9,4,4);
    final GameTestHelper h;
    final TrackGraph graph;
    Train train;
    private TrainTestFixture(GameTestHelper h, Train train) { this.h=h; this.train=train; this.graph=train.graph; }
    static void create(GameTestHelper h, String track, boolean large, boolean upsideDown,
                       Consumer<GameTestHelper> customise, Consumer<TrainTestFixture> ready) {
        create(h,track,large,upsideDown,false,customise,ready);
    }
    static void create(GameTestHelper h, String track, boolean large, boolean upsideDown, boolean twoBogeys,
                       Consumer<GameTestHelper> customise, Consumer<TrainTestFixture> ready) {
        create(h,track,large,upsideDown,twoBogeys,null,customise,ready);
    }
    static void create(GameTestHelper h, String track, boolean large, boolean upsideDown, boolean twoBogeys,
                       SteamHosePort port, Consumer<GameTestHelper> customise, Consumer<TrainTestFixture> ready) {
        var parts=TrainIntegrationTests.structure(h,4000,false);
        if(large) {
            h.setBlock(WHEEL, AllBlocks.LARGE_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS,Direction.Axis.Z));
            SteamBogey.install(h.getBlockEntity(WHEEL));
        }
        for(int x=2;x<=10;x++) for(int z=4;z<=9;z++) {
            var at=new BlockPos(x,3,z); if(!parts.contains(at)) h.setBlock(at,Blocks.STONE);
        }
        for(var at:List.of(WHEEL.west(),WHEEL.east(),WHEEL.north(),WHEEL.south())) if(!parts.contains(at)) h.setBlock(at,Blocks.STONE);
        if(twoBogeys){
            for(int x=2;x<=10;x++)for(int z=10;z<=12;z++)h.setBlock(new BlockPos(x,3,z),Blocks.STONE);
            h.setBlock(WHEEL.south(6),(large?AllBlocks.LARGE_BOGEY:AllBlocks.SMALL_BOGEY).getDefaultState().setValue(AbstractBogeyBlock.AXIS,Direction.Axis.Z));
        }
        customise.accept(h);
        h.getLevel().addFreshEntity(new SuperGlueEntity(h.getLevel(),SuperGlueEntity.span(h.absolutePos(new BlockPos(2,3,4)),h.absolutePos(new BlockPos(12,9,12)))));
        h.runAfterDelay(25,()-> {
            if(SteamBogey.powered(h.getBlockEntity(WHEEL))) {
                if(port == null) SteamHoseLinks.connect(h.getBlockEntity(WHEEL),h.getBlockEntity(BOILER));
                else SteamHoseLinks.connect(h.getBlockEntity(WHEEL),h.getBlockEntity(BOILER),port);
            }
            if(twoBogeys&&SteamBogey.powered(h.getBlockEntity(WHEEL.south(6))))SteamHoseLinks.connect(h.getBlockEntity(WHEEL.south(6)),h.getBlockEntity(BOILER));
            ((FluidTankBlockEntity)h.getBlockEntity(WATER)).getControllerBE().getTankInventory().setFluid(new FluidStack(Fluids.WATER,4000));
            h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,h.absolutePos(CHEST),null).insertItem(0,new ItemStack(Items.COAL,16),false);
            var cc=new CarriageContraption(Direction.SOUTH);
            try { h.assertTrue(cc.assemble(h.getLevel(),h.absolutePos(WHEEL)),"Native train fixture assembles"); }
            catch(com.simibubi.create.content.contraptions.AssemblyException e){throw new IllegalStateException(e);}
            cc.removeBlocksFromWorld(h.getLevel(),BlockPos.ZERO);
            double initial=track.equals("start")?2:20;
            var graph=new TrackGraph(); var origin=Vec3.atCenterOf(h.absolutePos(WHEEL)).subtract(0,0,initial);
            var end=origin.add(0,track.equals("up")?256:track.equals("down")?-256:0,2048);
            if(track.equals("curve")) end=origin.add(32,0,32);
            if(track.equals("end"))end=origin.add(0,0,22);
            var a=new DiscoveredLocation(h.getLevel(),origin).withNormal(new Vec3(0,1,0)).materialA(TrackMaterial.ANDESITE);
            var b=new DiscoveredLocation(h.getLevel(),end).withNormal(new Vec3(0,1,0)).materialA(TrackMaterial.ANDESITE);
            graph.createNodeIfAbsent(a);graph.createNodeIfAbsent(b);
            BezierConnection curve=track.equals("curve")?new BezierConnection(Couple.create(BlockPos.containing(origin),BlockPos.containing(end)),
                Couple.create(origin,end),Couple.create(new Vec3(0,0,1),new Vec3(-1,0,0)),Couple.create(new Vec3(0,1,0),new Vec3(0,1,0)),true,false,TrackMaterial.ANDESITE):null;
            graph.connectNodes(h.getLevel(),a,b,curve);
            var na=graph.locateNode(a);var nb=graph.locateNode(b);var edge=graph.getConnection(Couple.create(na,nb));
            var block=large?AllBlocks.LARGE_BOGEY.get():AllBlocks.SMALL_BOGEY.get(); double spacing=block.getWheelPointSpacing();
            var bogey=new CarriageBogey(block,upsideDown,cc.getBlocks().get(BlockPos.ZERO).nbt().getCompound("BogeyData").copy(),new TravellingPoint(na,nb,edge,initial+spacing/2,false),new TravellingPoint(na,nb,edge,initial-spacing/2,false));
            var rear=twoBogeys?new CarriageBogey(block,upsideDown,cc.getBlocks().get(new BlockPos(0,0,6)).nbt().getCompound("BogeyData").copy(),new TravellingPoint(na,nb,edge,initial-6+spacing/2,false),new TravellingPoint(na,nb,edge,initial-6-spacing/2,false)):null;
            var carriage=new Carriage(bogey,rear,twoBogeys?6:0);
            var train=new Train(UUID.randomUUID(),null,graph,List.of(carriage),List.of(),false,0);
            carriage.setContraption(h.getLevel(),cc);carriage.manageEntities(h.getLevel());
            var fixture=new TrainTestFixture(h,train);
            h.assertTrue(fixture.entity()!=null,"Native loaded carriage exists");
            Create.RAILWAYS.trackNetworks.put(graph.id,graph); Create.RAILWAYS.addTrain(train);
            try { ready.accept(fixture); }
            catch (RuntimeException|Error failure) { fixture.close(); throw failure; }
        });
    }
    CarriageContraptionEntity entity(){return train.carriages.getFirst().anyAvailableEntity();}
    MovementContext actor(){return entity().getContraption().getActors().stream().map(a->a.getRight()).filter(c->TrainActorMotion.drive(c)!=null).findFirst().orElseThrow();}
    BlockPos contact(MovementContext ctx){return BlockPos.containing(entity().toGlobalVector(Vec3.atCenterOf(ctx.localPos).add(MovementBehaviour.REGISTRY.get(ctx.state).getActiveAreaOffset(ctx)),1));}
    static TrainTestFixture couple(TrainTestFixture first,TrainTestFixture second){
        first.close();second.close();
        var a=first.train.carriages.getFirst();var b=second.train.carriages.getFirst();var lead=a.getLeadingPoint();
        second.train.forEachTravellingPoint(p->{p.node1=lead.node1;p.node2=lead.node2;p.edge=lead.edge;p.position-=8;});
        var train=new Train(UUID.randomUUID(),null,first.graph,List.of(a,b),List.of(8),false,0);
        var fixture=new TrainTestFixture(first.h,train);
        Create.RAILWAYS.trackNetworks.put(train.graph.id,train.graph);Create.RAILWAYS.addTrain(train);
        try { for(var c:train.carriages){c.updateContraptionAnchors();c.manageEntities(first.h.getLevel());} }
        catch (RuntimeException|Error failure) { fixture.close(); throw failure; }
        return fixture;
    }
    void tick(int direction,boolean brake){TrainPhysics.manual(train,direction,brake);train.tick(h.getLevel());}
    int items(Item item){var inventory=train.carriages.getFirst().storage.getAllItems();int n=0;for(int i=0;i<inventory.getSlots();i++)if(inventory.getStackInSlot(i).is(item))n+=inventory.getStackInSlot(i).getCount();return n;}
    int water(){var fluid=train.carriages.getFirst().storage.getFluids();int n=0;for(int i=0;i<fluid.getTanks();i++)if(fluid.getFluidInTank(i).is(Fluids.WATER))n+=fluid.getFluidInTank(i).getAmount();return n;}
    void reload(){
        boolean loaded=entity()!=null;
        var palette=new DimensionPalette();var tag=train.write(palette,h.getLevel().registryAccess());
        close();train=Train.read(tag,h.getLevel().registryAccess(),Map.of(train.graph.id,train.graph),palette);
        train.carriages.forEach(c->c.manageEntities(h.getLevel()));
        Create.RAILWAYS.trackNetworks.put(train.graph.id,train.graph);Create.RAILWAYS.addTrain(train);
        if(loaded)h.assertTrue(entity()!=null,"Native train read restores the loaded carriage entity");
    }
    void close(){Create.RAILWAYS.removeTrain(train.id);Create.RAILWAYS.trackNetworks.remove(graph.id);train.carriages.forEach(c->c.forEachPresentEntity(e->e.discard()));}
    void finish(){close();h.succeed();}
}
