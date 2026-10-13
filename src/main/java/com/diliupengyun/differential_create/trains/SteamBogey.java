package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** An installed engine is part of Create's existing bogey, including its native save and style data. */
@EventBusSubscriber(modid="differentialcreate")
public final class SteamBogey {
    public static final String ENGINE="DCSteamEngine", MOTOR_POS="DCEnginePos", RPM="DCEngineRPM", TURNS="DCEngineTurns", SAMPLE="DCEngineSample";
    private static final Map<ServerLevel,Set<AbstractBogeyBlockEntity>> LOADED=new WeakHashMap<>();
    private SteamBogey() {}
    public static boolean powered(BlockEntity be) {
        return be instanceof AbstractBogeyBlockEntity bogey && bogey.getBogeyData().getBoolean(ENGINE);
    }
    public static boolean install(AbstractBogeyBlockEntity bogey) {
        if(powered(bogey))return false;
        var data=bogey.getBogeyData().copy(); data.putBoolean(ENGINE,true);data.putBoolean("DCGeared",true);
        bogey.setBogeyData(data);bogey.notifyUpdate();loaded(bogey);return true;
    }
    public static void loaded(AbstractBogeyBlockEntity bogey) {
        if(!(bogey.getLevel() instanceof ServerLevel level))return;
        LOADED.computeIfAbsent(level,l->Collections.newSetFromMap(new WeakHashMap<>())).add(bogey);
        SteamHoseLinks.ensureRegistered(bogey);
    }
    @SubscribeEvent public static void tick(LevelTickEvent.Post event) {
        if(!(event.getLevel() instanceof ServerLevel level)||level.getGameTime()%20!=0)return;
        var entries=LOADED.get(level);if(entries==null)return;
        entries.removeIf(be->be.isRemoved()||be.getLevel()!=level);
        for(var be:entries)SteamHoseLinks.ensureRegistered(be);
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent.RightClickBlock event) {
        var player=event.getEntity();var level=event.getLevel();
        if(!player.mayBuild()||!(level.getBlockEntity(event.getPos()) instanceof AbstractBogeyBlockEntity bogey))return;
        var stack=player.getItemInHand(event.getHand());
        boolean install=AllBlocks.STEAM_ENGINE.isIn(stack);
        boolean remove=stack.isEmpty()&&player.isShiftKeyDown()&&powered(bogey);
        if(!install&&!remove)return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if(level.isClientSide)return;
        if(install) {
            if(install(bogey)) {
                if(!player.isCreative())stack.shrink(1);
                player.displayClientMessage(Component.translatable("gui.differentialcreate.bogey.installed"),true);
            } else player.displayClientMessage(Component.translatable("gui.differentialcreate.bogey.already_installed"),true);
        } else {
            SteamHoseLinks.unlink(bogey);
            var data=bogey.getBogeyData().copy();
            for(String key:List.of(ENGINE,MOTOR_POS,RPM,TURNS,SAMPLE,"DCGeared"))data.remove(key);
            bogey.setBogeyData(data);bogey.notifyUpdate();
            var returned=AllBlocks.STEAM_ENGINE.asStack();
            if(!player.getInventory().add(returned))player.drop(returned,false);
            player.displayClientMessage(Component.translatable("gui.differentialcreate.bogey.removed"),true);
        }
    }
    @SubscribeEvent public static void drops(BlockDropsEvent event) {
        if(!powered(event.getBlockEntity()))return;
        var p=event.getPos();
        event.getDrops().add(new ItemEntity(event.getLevel(),p.getX()+.5,p.getY()+.5,p.getZ()+.5,AllBlocks.STEAM_ENGINE.asStack()));
    }
}
