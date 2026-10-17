package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("differentialcreate_ejector")
@PrefixGameTestTemplate(false)
public final class EjectorIntegrationTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (double speed : new double[]{0, .01, 128, -128, 8192})
            cases.add(test("motion_" + Double.toString(speed).replace('.', '_'), h -> EjectorTests.motion(h, speed)));
        for (int direction : new int[]{-1, 1}) cases.add(test("full_" + direction, h -> EjectorTests.full(h, direction)));
        for (int count : new int[]{1, 64}) cases.add(test("launch_" + count, h -> EjectorTests.launch(h, count)));
        cases.add(test("retarget", EjectorTests::retarget));
        cases.add(test("persistence", EjectorTests::persistence));
        cases.add(test("redstone", EjectorTests::redstone));
        cases.add(test("blocked_inventory", EjectorTests::inventory));
        cases.add(test("flight_snapshots", EjectorTests::snapshots));
        cases.add(test("collision", EjectorTests::collision));
        cases.add(test("entity_launch", EjectorTests::entity));
        cases.add(test("unpaid_trigger", EjectorTests::unpaidTrigger));
        cases.add(test("cancel_pending", EjectorTests::cancelPending));
        cases.add(new TestFunction("machine_activity", "machine_activity", "differentialcreate_ejector:kineticphysicstests.physics_test",
            1600, 0, true, KineticPhysicsTests::idleLoadRegression));
        return cases;
    }
    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction(name, name, "differentialcreate_ejector:kineticphysicstests.physics_test", 180, 0, true, h -> {
            KineticPhysicsTests.forceImpactChunks(h);
            body.accept(h);
        });
    }
}
