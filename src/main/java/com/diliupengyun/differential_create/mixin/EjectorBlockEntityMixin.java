package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity.State;
import com.simibubi.create.content.logistics.depot.EntityLauncher;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.data.IntAttached;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EjectorBlockEntity.class)
public abstract class EjectorBlockEntityMixin implements ShaftWork, IEjectorDynamics {
    @Shadow EntityLauncher launcher;
    @Shadow LerpedFloat lidProgress;
    @Shadow State state;
    @Shadow boolean launch;
    @Shadow int scanCooldown;
    @Shadow ItemStack trackedItem;
    @Shadow List<IntAttached<ItemStack>> launchedItems;
    @Shadow Pair<Vec3, BlockPos> earlyTarget;
    @Shadow float earlyTargetTime;
    @Shadow protected abstract void ejectIfTriggered();
    @Shadow protected abstract Direction getFacing();

    @Unique private final EjectorCharge differentialcreate$charge = new EjectorCharge();
    @Unique private final Map<IntAttached<ItemStack>, EjectorFlight> differentialcreate$flights = new IdentityHashMap<>();
    @Unique private EjectorFlight differentialcreate$launching;

    @Override public EjectorCharge differentialcreate$charge() { return differentialcreate$charge; }
    @Override public List<IntAttached<ItemStack>> differentialcreate$launchedItems() { return launchedItems; }
    @Override public EjectorFlight differentialcreate$flight(IntAttached<ItemStack> item) { return differentialcreate$flights.get(item); }
    @Unique private double differentialcreate$required() { return EjectorCharge.required(launcher.getHorizontalDistance(), launcher.getVerticalDistance()); }
    @Unique private boolean differentialcreate$winding() {
        return state == State.RETRACTING && !(lidProgress.getChaseTarget() == 1 && !lidProgress.settled());
    }
    @Override public double differentialcreate$remainingTravel() {
        return !((EjectorBlockEntity) (Object) this).isVirtual() && differentialcreate$winding()
            ? differentialcreate$charge.remaining(differentialcreate$required()) : Double.POSITIVE_INFINITY;
    }
    @Override public void differentialcreate$advanceTravel(double travel) {
        var self = (EjectorBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide || !Double.isFinite(differentialcreate$remainingTravel())) return;
        differentialcreate$charge.advance(travel, differentialcreate$required());
        differentialcreate$publishCharge();
        self.setChanged();
        if (differentialcreate$charge.remaining(differentialcreate$required()) == 0) {
            state = State.CHARGED;
            self.notifyUpdate();
        }
    }
    @Unique private void differentialcreate$publishCharge() {
        lidProgress.updateChaseTarget(0);
        lidProgress.updateChaseSpeed(0);
        lidProgress.setValue((float) (differentialcreate$charge.remaining(differentialcreate$required()) / differentialcreate$required()));
    }
    @Unique private EjectorFlight differentialcreate$snapshot() {
        var flight = new EjectorFlight(launcher.getHorizontalDistance(), launcher.getVerticalDistance(), getFacing());
        flight.earlyTarget = earlyTarget;
        flight.earlyTargetTime = earlyTargetTime;
        return flight;
    }

    /** Keep native inventory, trigger and release animation rules; only the common clock winds on the server. */
    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true)
    private void differentialcreate$tick(CallbackInfo ci) {
        var self = (EjectorBlockEntity) (Object) this;
        if (self.isVirtual()) return;
        boolean server = !self.getLevel().isClientSide;
        State previous = state;
        if (scanCooldown > 0) scanCooldown--;
        if (launch) { launch = false; self.activateDeferred(); }
        differentialcreate$tickFlights(server);
        if (state == State.LAUNCHING) {
            lidProgress.chase(1, .8f, LerpedFloat.Chaser.EXP);
            lidProgress.tickChaser();
            if (lidProgress.getValue() > 1 - 1 / 16f && server) {
                state = State.RETRACTING;
                lidProgress.setValue(1);
            }
        }
        if (state == State.CHARGED) {
            lidProgress.setValue(0);
            lidProgress.updateChaseSpeed(0);
            if (server) ejectIfTriggered();
        }
        if (state == State.RETRACTING) {
            if (!differentialcreate$winding()) lidProgress.tickChaser();
            else {
                // Client prediction affects the lid only; authoritative work comes from accepted shaft travel.
                if (!server) differentialcreate$charge.advance(Math.abs(self.getSpeed()), differentialcreate$required());
                differentialcreate$publishCharge();
                double rate = Math.abs(self.getSpeed()) / differentialcreate$required();
                if (server && rate > 0) {
                    int period = (int) Math.min(Integer.MAX_VALUE, 1 + 1 / (rate * 5));
                    if (self.getLevel().getGameTime() % period == 0)
                        self.getLevel().playSound(null, self.getBlockPos(), SoundEvents.WOODEN_BUTTON_CLICK_OFF,
                            SoundSource.BLOCKS, .125f, 1.5f - lidProgress.getValue());
                }
            }
        }
        if (state != previous && server) self.notifyUpdate();
        ci.cancel();
    }
    @Unique private void differentialcreate$tickFlights(boolean server) {
        var self = (EjectorBlockEntity) (Object) this;
        if (server && !launchedItems.isEmpty()) self.setChanged();
        for (var iterator = launchedItems.iterator(); iterator.hasNext();) {
            var item = iterator.next();
            var flight = differentialcreate$flights.computeIfAbsent(item, ignored -> differentialcreate$snapshot());
            boolean tracked = item.getSecond() == trackedItem;
            Pair<Vec3, BlockPos> oldTarget = flight.earlyTarget;
            boolean hit = server && tracked && flight.scan(self, item.getFirst());
            if (tracked && server && oldTarget != flight.earlyTarget) {
                if (flight.horizontal() == launcher.getHorizontalDistance() && flight.vertical() == launcher.getVerticalDistance()
                    && flight.facing() == getFacing()) {
                    earlyTarget = flight.earlyTarget;
                    earlyTargetTime = flight.earlyTargetTime;
                }
                self.sendData();
            }
            if (hit || item.exceeds((int) flight.endTime())) {
                if (server) {
                    if (tracked) trackedItem = null;
                    flight.finish(self, item);
                    self.setChanged();
                }
                iterator.remove();
                differentialcreate$flights.remove(item);
            }
            item.increment();
        }
    }

    @Inject(method = "activate", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$requireCharge(CallbackInfo ci) {
        var self = (EjectorBlockEntity) (Object) this;
        // Native clients also accept LAUNCHING so a player's predicted bounce survives a server update.
        if (!self.isVirtual() && !self.getLevel().isClientSide && (state != State.CHARGED
            || differentialcreate$charge.remaining(differentialcreate$required()) > MachineWork.EPSILON)) ci.cancel();
    }
    @Inject(method = "activateDeferred", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$payForLaunch(CallbackInfo ci) {
        var self = (EjectorBlockEntity) (Object) this;
        if (self.isVirtual() || self.getLevel().isClientSide) return;
        if (state != State.CHARGED) return; // Native cannotLaunch handles all other states.
        if (differentialcreate$charge.remaining(differentialcreate$required()) > MachineWork.EPSILON) {
            state = State.RETRACTING;
            differentialcreate$publishCharge();
            self.notifyUpdate();
            ci.cancel();
            return;
        }
        differentialcreate$launching = differentialcreate$snapshot();
        differentialcreate$charge.consume();
        self.setChanged();
    }
    @Inject(method = "activateDeferred", at = @At("RETURN"))
    private void differentialcreate$launched(CallbackInfo ci) { differentialcreate$launching = null; }

    @Inject(method = "addToLaunchedItems", at = @At("RETURN"))
    private void differentialcreate$captureFlight(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (((EjectorBlockEntity) (Object) this).isVirtual() || launchedItems.isEmpty()) return;
        differentialcreate$flights.put(launchedItems.getLast(), differentialcreate$launching == null
            ? differentialcreate$snapshot() : differentialcreate$launching);
    }
    @Inject(method = "dropFlyingItems", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$dropFlights(CallbackInfo ci) {
        var self = (EjectorBlockEntity) (Object) this;
        if (self.isVirtual()) return;
        if (self.getLevel() != null && !self.getLevel().isClientSide)
            for (var item : launchedItems) {
                var flight = differentialcreate$flights.get(item);
                (flight == null ? differentialcreate$snapshot() : flight).drop(self, item);
            }
        launchedItems.clear();
        differentialcreate$flights.clear();
        trackedItem = null;
        ci.cancel();
    }
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$retarget(int horizontal, int vertical, CallbackInfo ci) {
        var self = (EjectorBlockEntity) (Object) this;
        if (self.isVirtual()) return;
        launcher.set(Math.max(1, horizontal), vertical);
        differentialcreate$charge.retarget(differentialcreate$required());
        earlyTarget = null;
        earlyTargetTime = 0;
        if (state != State.LAUNCHING) {
            if (differentialcreate$charge.remaining(differentialcreate$required()) <= MachineWork.EPSILON) {
                differentialcreate$charge.advance(0, differentialcreate$required());
                state = State.CHARGED;
            } else if (state == State.CHARGED) state = State.RETRACTING;
            differentialcreate$publishCharge();
        }
        self.setChanged();
        self.sendData();
        ci.cancel();
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void differentialcreate$write(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        tag.putDouble(EjectorCharge.KEY, differentialcreate$charge.travel());
        var groups = new ListTag();
        var indices = new int[launchedItems.size()];
        var groupIds = new IdentityHashMap<EjectorFlight, Integer>();
        int tracked = -1;
        for (int i = 0; i < launchedItems.size(); i++) {
            var item = launchedItems.get(i);
            var flight = differentialcreate$flights.computeIfAbsent(item, ignored -> differentialcreate$snapshot());
            indices[i] = groupIds.computeIfAbsent(flight, ignored -> { groups.add(flight.save()); return groups.size() - 1; });
            if (item.getSecond() == trackedItem) tracked = i;
        }
        tag.put("DCEjectorFlightGroups", groups);
        tag.putIntArray("DCEjectorFlightIndices", indices);
        tag.putInt("DCEjectorTracked", tracked);
        tag.putInt("DCEjectorScanCooldown", scanCooldown);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void differentialcreate$read(CompoundTag tag, HolderLookup.Provider registries, boolean packet, CallbackInfo ci) {
        var self = (EjectorBlockEntity) (Object) this;
        if (self.isVirtual()) return;
        if (!packet) launch = false;
        differentialcreate$launching = null;
        differentialcreate$charge.restore(tag.getDouble(EjectorCharge.KEY), differentialcreate$required());
        if (state == State.LAUNCHING) differentialcreate$charge.consume();
        else {
            if (state == State.CHARGED && differentialcreate$charge.remaining(differentialcreate$required()) > MachineWork.EPSILON)
                state = State.RETRACTING;
            differentialcreate$publishCharge();
        }
        differentialcreate$flights.clear();
        var groups = new ArrayList<EjectorFlight>();
        var saved = tag.getList("DCEjectorFlightGroups", Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) groups.add(EjectorFlight.load(saved.getCompound(i)));
        int[] indices = tag.getIntArray("DCEjectorFlightIndices");
        for (int i = 0; i < launchedItems.size(); i++)
            differentialcreate$flights.put(launchedItems.get(i), i < indices.length && indices[i] >= 0 && indices[i] < groups.size()
                ? groups.get(indices[i]) : differentialcreate$snapshot());
        int tracked = tag.contains("DCEjectorTracked") ? tag.getInt("DCEjectorTracked") : -1;
        trackedItem = tracked >= 0 && tracked < launchedItems.size() ? launchedItems.get(tracked).getSecond() : null;
        scanCooldown = Math.max(0, tag.getInt("DCEjectorScanCooldown"));
    }
}
