package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("differentialcreate_crusher")
@PrefixGameTestTemplate(false)
public final class CrushingIntegrationTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases=new ArrayList<TestFunction>();
        int i=0;
        for(double[] speeds:new double[][]{{64,-64},{128,-64},{64,0},{64,64},{128,64},{-64,128},{.01,0},{0,0},{8192,-4096}})
            cases.add(test("motion_"+i++,h->CrushingTests.motion(h,speeds[0],speeds[1])));
        for(int direction:new int[]{-1,1}) cases.add(test("completion_"+direction,h->CrushingTests.completion(h,direction)));
        for(int count:new int[]{1,2,4,64}) cases.add(test("batch_"+count,h->CrushingTests.batch(h,count)));
        cases.add(test("persistence",CrushingTests::persistence));
        cases.add(test("static_feed_presentation",CrushingTests::staticFeedPresentation));
        cases.add(test("entity_feed",CrushingTests::entityFeed));
        cases.add(test("independent_drives",h->CrushingTests.drives(h,false)));
        cases.add(test("clutch_drive",h->CrushingTests.drives(h,true)));
        for(Direction.Axis axis:Direction.Axis.values()) for(Direction side:new Direction[]{Direction.EAST,Direction.UP,Direction.SOUTH})
            if(axis!=side.getAxis()) cases.add(test("geometry_"+axis+"_"+side,h->CrushingTests.geometry(h,axis,side)));
        cases.add(test("machine_activity",KineticPhysicsTests::idleLoadRegression));
        cases.add(test("machining_energy",MachiningTests::run));
        return cases;
    }
    private static TestFunction test(String name,Consumer<GameTestHelper> body) {
        return new TestFunction(name,name,"differentialcreate_crusher:kineticphysicstests.physics_test",1600,0,true,h->{
            KineticPhysicsTests.forceImpactChunks(h);body.accept(h);
        });
    }
}
