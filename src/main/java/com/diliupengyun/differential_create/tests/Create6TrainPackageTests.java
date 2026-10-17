package com.diliupengyun.differential_create.tests;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.postbox.PostboxBlockEntity;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DeliverPackagesInstruction;
import com.simibubi.create.content.trains.schedule.destination.FetchPackagesInstruction;
import com.simibubi.create.content.trains.station.GlobalPackagePort;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import static com.diliupengyun.differential_create.tests.TrainScheduleScenario.*;

/** Real timetable movement, native mounted inventories and station mail transfer. */
@GameTestHolder("differentialcreate_logistics")
@PrefixGameTestTemplate(false)
public final class Create6TrainPackageTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (int direction : new int[]{-1, 1}) for (boolean fetch : new boolean[]{false, true})
            for (boolean loaded : new boolean[]{false, true}) {
                String name = "mail_" + (fetch ? "fetch" : "deliver") + "_" + (loaded ? "postbox" : "offline") + "_" + direction;
                tests.add(new TestFunction(name, name, "differentialcreate_logistics:kineticphysicstests.physics_test", 2400, 0, true,
                    h -> create(h, "flat", 1, "both", s -> mail(s, direction, fetch, loaded))));
            }
        return tests;
    }
    private static ItemStack box(String address, int count) {
        var box = PackageItem.containing(List.of(new ItemStack(Items.IRON_INGOT, count)));
        PackageItem.addAddress(box, address); return box;
    }
    private static int parcels(IItemHandler inventory, String address) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var item = inventory.getStackInSlot(slot);
            if (PackageItem.isPackage(item) && PackageItem.matchAddress(item, address)) count += item.getCount();
        }
        return count;
    }
    private static void insert(IItemHandler inventory, ItemStack stack) {
        var rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(inventory, stack, false);
        if (!rest.isEmpty()) throw new AssertionError("Mail fixture has no inventory space");
    }
    private static void mail(TrainScheduleScenario s, int direction, boolean fetch, boolean loaded) {
        s.park(direction);
        var destination = s.station(direction, 30, "Mail destination");
        var port = new GlobalPackagePort(); port.address = fetch ? "Depot" : "Destination";
        BlockPos position = s.h.absolutePos(loaded ? new BlockPos(14, 4, 7) : new BlockPos(10000, 4, 10000));
        destination.connectedPorts.put(position, port);
        IItemHandlerModifiable postbox;
        if (loaded) {
            s.h.getLevel().setBlockAndUpdate(position, AllBlocks.PACKAGE_POSTBOXES.get(DyeColor.WHITE).getDefaultState());
            var actual = (PostboxBlockEntity) s.h.getLevel().getBlockEntity(position);
            actual.trackedGlobalStation = new WeakReference<>(destination);
            postbox = actual.inventory;
            s.cleanup.add(() -> s.h.getLevel().removeBlock(position, false));
        } else {
            s.h.assertTrue(!s.h.getLevel().isLoaded(position), "offline port is outside loaded chunks");
            postbox = port.offlineBuffer;
        }
        var cargo = s.train().carriages.getFirst().storage.getAllItems();
        if (fetch) {
            insert(postbox, box("Destination", 17));
            insert(postbox, box("Depot", 3));
        } else {
            insert(cargo, box("Destination", 17));
            insert(cargo, box("Elsewhere", 3));
        }
        var entry = new ScheduleEntry();
        if (fetch) {
            var instruction = new FetchPackagesInstruction(); instruction.getData().putString("Text", "Destination");
            entry.instruction = instruction;
        } else entry.instruction = new DeliverPackagesInstruction();
        entry.conditions.add(List.of(delay(100)));
        s.schedule(false, entry);
        s.arrived(destination, () -> {
            s.h.assertTrue(parcels(postbox, "Destination") == (fetch ? 0 : 1)
                && parcels(cargo, "Destination") == (fetch ? 1 : 0), "native package instruction navigates, parks and transfers the matching parcel exactly once");
            if (fetch) s.h.assertTrue(parcels(postbox, "Depot") == 1, "retrieval leaves local incoming mail in its postbox");
            else s.h.assertTrue(parcels(cargo, "Elsewhere") == 1, "delivery retains mail for a different address");
            s.after(10, () -> {
                s.h.assertTrue(parcels(postbox, "Destination") + parcels(cargo, "Destination") == 1,
                    "repeated parked station transfer cannot duplicate a parcel");
                if (!loaded) s.h.assertTrue(port.primed, "offline transfer records the pending buffer restoration");
                s.succeed();
            });
        });
    }
}
