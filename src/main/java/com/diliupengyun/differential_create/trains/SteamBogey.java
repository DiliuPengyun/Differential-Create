package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.diliupengyun.differential_create.content.ModContent;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** An installed engine is part of Create's existing bogey, including its native save and style data. */
@EventBusSubscriber(modid="differentialcreate")
public final class SteamBogey {
    public static final String ENGINE="DCSteamEngine", MOTOR_POS="DCEnginePos", RPM="DCEngineRPM", TURNS="DCEngineTurns", SAMPLE="DCEngineSample";
    public static final String CONDENSER="DCCondenser";
    public static final double CONDENSER_WATER_USE=.1;
    private static final Map<ServerLevel,Set<AbstractBogeyBlockEntity>> LOADED=new WeakHashMap<>();
    private SteamBogey() {}
    public static boolean powered(BlockEntity be) {
        return be instanceof AbstractBogeyBlockEntity bogey && bogey.getBogeyData().getBoolean(ENGINE);
    }
    public static boolean install(AbstractBogeyBlockEntity bogey) {
        if(powered(bogey))return false;
        var data=bogey.getBogeyData().copy(); data.putBoolean(ENGINE,true);
        bogey.setBogeyData(data);bogey.notifyUpdate();loaded(bogey);return true;
    }
    public static boolean condensing(BlockEntity be) {
        return powered(be) && ((AbstractBogeyBlockEntity) be).getBogeyData().getBoolean(CONDENSER);
    }
    public static boolean installCondenser(AbstractBogeyBlockEntity bogey) {
        if(!powered(bogey)||condensing(bogey))return false;
        var data=bogey.getBogeyData().copy();data.putBoolean(CONDENSER,true);
        bogey.setBogeyData(data);bogey.notifyUpdate();return true;
    }
    private static void installationEffect(AbstractBogeyBlockEntity bogey) {
        var level=bogey.getLevel();var pos=bogey.getBlockPos();
        // Match Create's ManualApplicationRecipe without destroying the bogey or its data.
        level.playSound(null,pos,SoundEvents.COPPER_BREAK,SoundSource.PLAYERS,1,1.45f);
        level.levelEvent(2001,pos,Block.getId(bogey.getBlockState()));
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
        if(player.isSpectator()||!player.mayBuild()||!(level.getBlockEntity(event.getPos()) instanceof AbstractBogeyBlockEntity bogey))return;
        var stack=player.getItemInHand(event.getHand());
        boolean install=AllBlocks.STEAM_ENGINE.isIn(stack);
        boolean condenser=ModContent.CONDENSER_PIPE.isIn(stack);
        boolean remove=player.getMainHandItem().isEmpty()&&player.getOffhandItem().isEmpty()&&player.isShiftKeyDown()&&powered(bogey);
        if(!install&&!condenser&&!remove)return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if(level.isClientSide)return;
        if(install) {
            if(install(bogey)) {
                if(!player.isCreative())stack.shrink(1);
                installationEffect(bogey);
            } else player.displayClientMessage(Component.translatable("gui.differentialcreate.bogey.already_installed"),true);
        } else if(condenser) {
            if(installCondenser(bogey)) {
                if(!player.isCreative())stack.shrink(1);
                installationEffect(bogey);
            } else player.displayClientMessage(Component.translatable("gui.differentialcreate.bogey."
                +(powered(bogey)?"condenser_already_installed":"condenser_requires_engine")),true);
        } else {
            boolean hadCondenser=condensing(bogey);
            SteamHoseLinks.unlink(bogey);
            var data=bogey.getBogeyData().copy();
            for(String key:List.of(ENGINE,CONDENSER,MOTOR_POS,RPM,TURNS,SAMPLE))data.remove(key);
            bogey.setBogeyData(data);bogey.notifyUpdate();
            var returned=AllBlocks.STEAM_ENGINE.asStack();
            if(!player.getInventory().add(returned))player.drop(returned,false);
            if(hadCondenser) {
                var pipe=ModContent.CONDENSER_PIPE.asStack();
                if(!player.getInventory().add(pipe))player.drop(pipe,false);
            }
            player.displayClientMessage(Component.translatable("gui.differentialcreate.bogey.removed"),true);
        }
    }
    @SubscribeEvent public static void drops(BlockDropsEvent event) {
        if(!powered(event.getBlockEntity()))return;
        var p=event.getPos();
        event.getDrops().add(new ItemEntity(event.getLevel(),p.getX()+.5,p.getY()+.5,p.getZ()+.5,AllBlocks.STEAM_ENGINE.asStack()));
        if(condensing(event.getBlockEntity()))
            event.getDrops().add(new ItemEntity(event.getLevel(),p.getX()+.5,p.getY()+.5,p.getZ()+.5,ModContent.CONDENSER_PIPE.asStack()));
    }
}
