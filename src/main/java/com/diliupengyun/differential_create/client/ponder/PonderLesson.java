package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;

/** Storyboard identifiers and localized text delivery. */
public enum PonderLesson {
    DYNAMICS("dynamics",
        "How Torque, Inertia, and Flywheels Work",
        "Limited torque and shaft inertia make speed change gradually.",
        "Flywheels store energy and can keep machines working after power is cut.",
        "Gearing up trades torque for speed; the small gear's press processes faster.",
        "Connections flex; torque exceeding a component's strength breaks it."
    ),
    CLUTCH("clutch",
        "Clutches, Slip, and the Brass Clutch",
        "Full engagement limits: standard 1024, Brass 32768 su/RPM.",
        "Redstone 0 fully engages, 15 disengages; intermediate signals reduce torque capacity.",
        "Disengaging the clutch lets the output coast on its inertia.",
        "With empty hands, hold right-click on Brass panels; redstone reduces engagement."
    ),
    RATIO("ratio",
        "Adjusting Gear Ratios with the Ratio Controller",
        "Set the lower shaft/top cog speed ratio, from -16 to +16.",
        "Negative ratios reverse; zero disconnects. The dial uses nonlinear spacing.",
        "Top cog input at +2 ratio doubles shaft speed and halves torque.",
        "Ctrl + right-click opens numeric input, rounded to sixteenths."
    ),
    LOAD_GAUGE("load_gauge",
        "Reading Power Demand with the Load Gauge",
        "Load compares sustained power demand with available drive power, excluding stored inertia.",
        "Midscale means 100%% load and signal 8; infinite overload gives 15.",
        "Without active power, coasting gives no load reading and signal zero.",
        "Goggles show details; a stationary network uses the starting torque budget."
    ),
    SPEED_GAUGE("speed_gauge",
        "Measuring Shaft Speed and Adjusting Dial Range",
        "The Speedometer measures actual speed at its own shaft.",
        "Hold right-click on the dial with a Wrench to change its range.",
        "Range changes the dial and comparator; Goggles still show actual RPM."
    ),
    PUMP_DIRECTION("pump_direction",
        "Pump Direction, Shaft Rotation, and Fluid Inertia",
        "Counterclockwise from East/Up/South pressurizes along the pump arrow.",
        "Reverse rotation reverses pressure; liquid inertia delays the flow reversal.",
        "A Wrench flips the arrow; reversing both arrow and rotation cancels out.",
        "Gravity can backdrive pumps; zero flow consumes no sustained pumping power."
    ),
    PUMP_NETWORK("pump_network",
        "Pipe Pressure Networks and Air Venting",
        "Loaded connected pipes share pressure calculations, without a fixed 16-block range.",
        "New pipes need air outlets; closed Valves block both air and liquid.",
        "Smart Pipes filter liquids while allowing air through."
    ),
    BEARING("bearing",
        "Assembling and Coasting Mechanical Bearings",
        "Glue the structure; empty-hand right-click assembles it. Repeat to disassemble.",
        "Structures start from rest; mass adds inertia and exposed surfaces add drag.",
        "Cutting power keeps the structure assembled and coasting."
    ),
    STRUCTURE_LOAD("structure_load",
        "Moving Structures and Contraption Tool Loads",
        "Moving structures accelerate under finite force against their inertia and air drag.",
        "Mounted tools consume drive energy when cutting, harvesting, ploughing, or rolling.",
        "Moving Deployers consume energy per action and wait when energy is insufficient."
    ),
    CONTINUOUS_WORK("continuous_work",
        "Machine Progress and Coasting Through Work",
        "Processing advances with shaft travel; idle machines release their processing load.",
        "Slow rotation still advances work; stopping preserves the current operation's progress.",
        "After power is cut, sufficient flywheel energy can finish an active operation."
    ),
    BACKTANK("backtank",
        "Charging Copper and Netherite Backtanks",
        "Backtanks charge in either direction: one air unit per tick at 128 RPM.",
        "Fractional charging progress survives breaking, pickup, and wearing the backtank.",
        "Full or waterlogged backtanks stop charging and release load, while retaining inertia."
    ),
    EJECTOR_WORK("ejector_work",
        "Winding and Retargeting Weighted Ejectors",
        "Ejectors wind with shaft travel; farther targets require more work.",
        "Fully wound ejectors fire without power; each launch consumes the full charge.",
        "Farther targets need extra winding; closer targets discard excess. Airborne trajectories persist."
    ),
    CRUSHER_WORK("crusher_work",
        "Crushing Wheel Relative Motion and Batch Work",
        "Crushing uses relative wheel motion and applies reaction torque to both wheels.",
        "Unequal same-direction speeds also crush when feeding inward; equal speeds cannot.",
        "A 64-item batch requires six times the work and finishes together."
    ),
    STEAM_POWER("steam_power",
        "Boiler Steam Supply and Engine Governor",
        "Heat, boiler size, and actual water supply constrain available steam.",
        "Low speed is torque-limited; high speed is power-limited. Engines share steam.",
        "Near 512 RPM the governor throttles steam; cutting steam allows coasting."
    ),
    TRAIN_POWER("train_power",
        "Powering Trains with Steam Engines, Hoses, and Condensers",
        "Before assembly, install power by using a Steam Engine on a bogey.",
        "Use a Steam Hose between boiler and powered bogey, within 48 blocks.",
        "Fit a Condenser Pipe to recover 90%% of exhaust water.",
        "Separate tanks supply water; chests/barrels supply fuel. Vaults are excluded."
    ),
    TRAIN_DRIVING("train_driving",
        "Driving Steam Trains, Shifting Gears, and Station Parking",
        "Drive: W throttles; S brakes. Install a Gear Lever for X/Z shifting.",
        "Neutral disconnects wheels; W still revs the engine and consumes water.",
        "Hold Space for station approach/parking; W releases. Brake early to avoid overruns.",
        "Scheduled conductors drive automatically; a Gear Lever enables automatic shifting."
    ),
    PORTABLE_DOCKING("portable_docking",
        "Portable Interface Braking, Docking, and Wrench Settings",
        "Face interfaces 1–2 blocks apart; transfer requires stopping. Excess speed overshoots.",
        "Successful transfers refresh the idle timer; retracting finishes before movement resumes.",
        "Use a Wrench: select release mode and mechanical occupancy feedback."
    ),
    MINECART_DYNAMICS("minecart_dynamics",
        "Minecart Contraption Mass, Rail Forces, and Couplings",
        "Mounting a structure adds inertia and air drag to its minecart.",
        "Rail propulsion and braking are finite; heavier carts accelerate and stop slower.",
        "Couplings transmit force between carts; mounted tools also consume their kinetic energy."
    ),
    SEQUENCER("sequencer",
        "Precise Travel Control with Sequenced Gearshifts",
        "Sequenced Gearshifts measure actual output travel for angle and distance commands.",
        "It brakes ahead based on inertia and load, then holds the target.",
        "Same-direction steps flow; reversals stop first. Completed sequences need another redstone pulse."
    ),
    FAN_WORK("fan_work",
        "Encased Fan Speed, Processing Rate, and Drag",
        "Water washes; fire smokes; lava blasts; soul fire haunts.",
        "Processing matches baseline at 64 RPM; faster rotation approaches fourfold throughput.",
        "Spinning fans continuously experience air drag; resisting torque grows with RPM squared."
    );

    public final String id;
    private final String title;
    private final String[] text;

    PonderLesson(String id, String title, String... text) {
        this.id = id; this.title = title; this.text = text;
    }

    public void title(CreateSceneBuilder scene) { scene.title(id, title); }
    public int steps() { return text.length; }
    /** Keep train captions above the chassis and out of its travel path. */
    public void caption(CreateSceneBuilder scene, int step, PonderPalette color) {
        scene.overlay().showText(90).colored(color).text(text[step]).independent(0)
            .placeNearTarget().attachKeyFrame();
        scene.idle(100);
    }
    public void show(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, int step) {
        scene.overlay().showText(90).text(text[step]).pointAt(util.vector().topOf(pos))
            .placeNearTarget().attachKeyFrame();
        scene.idle(100);
    }
    public void show(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, int step, PonderPalette color) {
        scene.overlay().showText(90).colored(color).text(text[step]).pointAt(util.vector().topOf(pos))
            .placeNearTarget().attachKeyFrame();
        scene.idle(100);
    }
}
