package com.diliupengyun.differential_create.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;

/** Storyboard identifiers and localized text delivery. */
public enum PonderLesson {
    DYNAMICS("dynamics",
        "Acceleration, Inertia, and Flywheels",
        "Limited power makes shafts speed up and slow down gradually.",
        "Disconnect the input shaft: energy stored in the Flywheel keeps the press working.",
        "The small cog turns faster and drives the upper press.",
        "Add two Copper Ingots to each press together; the faster press finishes first."
    ),
    CLUTCH("clutch",
        "Disconnecting Clutches and Using the Brass Clutch",
        "The engaged Clutch passes rotation from the input to the Drill.",
        "Set redstone to 15: the Clutch opens while the input keeps turning.",
        "Slowing the input leaves the disconnected output spinning independently.",
        "Empty-hand hold right-click on the Brass panel to adjust engagement."
    ),
    RATIO("ratio",
        "Adjusting Speed and Direction with the Ratio Controller",
        "Set the lower shaft/top cog speed ratio, from -16 to +16.",
        "Set -1: the lower shaft reverses relative to the top cog.",
        "Set 0: the input keeps turning, with the output disconnected.",
        "Set +2: the lower shaft spins twice as fast.",
        "Ctrl + right-click sets precise ratios, here +1.5."
    ),
    LOAD_GAUGE("load_gauge",
        "Reading Power Demand with the Load Gauge",
        "Load compares sustained power demand with available drive power, excluding stored inertia.",
        "Midscale means 100%% load and signal 8; infinite overload gives 15.",
        "Without power, spinning on inertia gives no load reading or redstone signal.",
        "Use Goggles to read the current power demand and supply."
    ),
    SPEED_GAUGE("speed_gauge",
        "Measuring Shaft Speed and Adjusting Dial Range",
        "The Speedometer measures actual speed at its own shaft.",
        "Hold right-click on the dial with a Wrench to change its range."
    ),
    PUMP_DIRECTION("pump_direction",
        "Pump Flow and Reversing Direction",
        "Watch the pump arrow and the changing levels in both tanks.",
        "Use a Wrench to change the pump direction: water slows, then reverses.",
        "Cut the drive; water and the pump keep moving briefly."
    ),
    PUMP_NETWORK("pump_network",
        "Filling Pipes and Displacing Air",
        "An empty pipe contains air. Watch this glass section.",
        "Fill the supply tank and start the pump: water pushes air ahead.",
        "Watch the receiving tank rise as water flows into it."
    ),
    BEARING("bearing",
        "Assembling Bearings and Spinning After Power Is Cut",
        "Empty-hand right-click assembles the beam; input power starts it turning.",
        "Lower the input speed; the beam gradually slows down.",
        "Cutting power leaves the beam assembled and spinning on inertia."
    ),
    STRUCTURE_LOAD("structure_load",
        "Moving Structures and Powering Their Tools",
        "Input power moves the carriage along the gantry shaft.",
        "The Harvester spends power when it contacts mature crops.",
        "Reverse the input to bring the Harvester back."
    ),
    CONTINUOUS_WORK("continuous_work",
        "Slow Processing and Working After Power Is Cut",
        "Watch the press complete a copper sheet as its shaft turns.",
        "A slower input makes the next pressing stroke slower.",
        "Cut power: the Flywheel finishes this last copper sheet."
    ),
    EJECTOR_WORK("ejector_work",
        "Charging, Firing, and Retargeting Ejectors",
        "Turn the input shaft to charge the Ejector.",
        "Once charged, cut power and add an ingot: it still launches.",
        "Choose a nearer target; the next ingot lands on the middle Depot."
    ),
    CRUSHER_WORK("crusher_work",
        "Crushing Wheel Speeds and Feeding Items",
        "Same-way rotation can crush items when the wheels turn at different speeds.",
        "Wheel speeds and directions decide which side accepts items.",
        "On screen, left -16 and right -32 feed downward.",
        "Throw items in from the feed side; here, from above."
    ),
    STEAM_POWER("steam_power",
        "Boiler Steam and Engine Speed",
        "Heat and water start both engines on this boiler.",
        "Both engines draw steam from this same boiler.",
        "Remove the heat sources to stop steam supply. Unloaded engines keep spinning on inertia."
    ),
    TRAIN_POWER("train_power",
        "Installing Steam Power on Trains",
        "Before assembly, install power by using a Steam Engine on a bogey.",
        "Connect the boiler and powered bogey with a Steam Hose, within 48 blocks.",
        "Fit a Condenser Pipe on the bogey to recover 90%% of steam water.",
        "Separate tanks supply water; chests/barrels supply fuel. Vaults cannot supply fuel."
    ),
    TRAIN_DRIVING("train_driving",
        "Driving, Shifting Gears, and Stopping Trains",
        "W accelerates; S brakes. Install a Gear Lever for X/Z shifting.",
        "Neutral disconnects the wheels; pressing W still runs the engine.",
        "Brake near the station; stop before finishing your drive.",
        "When stopped, right-click the Controls again to finish driving."
    ),
    PORTABLE_DOCKING("portable_docking",
        "Wrench Settings for Portable Interfaces",
        "Select Wait for Redstone with a Wrench. Empty interfaces stay connected until powered.",
        "While Docked keeps the comparator at 15 until the heads fully retract.",
        "Docking Stage outputs 0 when idle, 8 while docking or retracting, and 15 when connected."
    ),
    MINECART_DYNAMICS("minecart_dynamics",
        "Inertia and Reversing Minecart Structures",
        "The loaded minecart gradually accelerates when the rails drive it.",
        "The second cart and attached cargo travel together with the first.",
        "Reverse the rails: the carts slow down before changing direction."
    ),
    SEQUENCER("sequencer",
        "Sequenced Gearshift Angles and Holding Position",
        "This program turns forward twice, then back half a turn.",
        "It slows near the target angle, then holds position.",
        "Once it finishes, another redstone pulse starts the program again."
    ),
    FAN_WORK("fan_work",
        "Fan Processing and Air Drag",
        "Air passing through lava smelts the ore into an ingot.",
        "Raise the speed and add another ore: faster blades, the same processing time.",
        "Cut power: the fan slows and its airflow weakens."
    );

    public final String id;
    private final String title;
    private final String[] text;

    PonderLesson(String id, String title, String... text) {
        this.id = id; this.title = title; this.text = text;
    }

    public void title(CreateSceneBuilder scene) { scene.title(id, title); }
    public int steps() { return text.length; }
    /** Keep captions beside elevated assemblies rather than across their heads. */
    private int captionOffset(int step) {
        return this == STEAM_POWER ? 135 : this == DYNAMICS && step == 2 ? 105 : 0;
    }
    /** Keep train captions above the chassis and out of its travel path. */
    public void caption(CreateSceneBuilder scene, int step, PonderPalette color) {
        scene.overlay().showText(90).colored(color).text(text[step]).independent(0)
            .placeNearTarget().attachKeyFrame();
        scene.idle(100);
    }
    /** Fixed-position caption while the caller performs the illustrated operation. */
    public void captionFor(CreateSceneBuilder scene, int step, PonderPalette color, int ticks) {
        scene.overlay().showText(ticks).colored(color).text(text[step]).independent(0)
            .placeNearTarget().attachKeyFrame();
    }
    /**
     * Keep the window at the default fixed slot instead of letting it cover the
     * machine. The target block is outlined for the same duration, replacing the
     * old leader line. Steam captions sit beside the lower stage to clear the assembly;
     * other lessons retain the default position.
     */
    public void show(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, int step) {
        scene.overlay().showText(90).text(text[step]).independent(captionOffset(step)).attachKeyFrame();
        scene.overlay().showOutline(PonderPalette.WHITE, new Object(), util.select().position(pos), 90);
        scene.idle(100);
    }
    public void show(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, int step, PonderPalette color) {
        scene.overlay().showText(90).colored(color).text(text[step]).independent(captionOffset(step)).attachKeyFrame();
        scene.overlay().showOutline(color, new Object(), util.select().position(pos), 90);
        scene.idle(100);
    }
    /** Same caption and outline, but the caller scripts the accompanying action and idle. */
    public void showFor(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos pos, int step,
                        PonderPalette color, int ticks) {
        scene.overlay().showText(ticks).colored(color).text(text[step]).independent(captionOffset(step)).attachKeyFrame();
        scene.overlay().showOutline(color, new Object(), util.select().position(pos), ticks);
    }
}
