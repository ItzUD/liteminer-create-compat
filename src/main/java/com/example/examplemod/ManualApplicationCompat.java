
package com.example.examplemod;

import com.iamkaf.liteminer.Liteminer;
import com.iamkaf.liteminer.LiteminerPlayerState;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.deployer.ManualApplicationRecipe;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class ManualApplicationCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final TagKey<Block> WRENCH_PICKUP = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("create", "wrench_pickup")
    );

    private ManualApplicationCompat() {}

    public static void register() {

        InteractionEvent.RIGHT_CLICK_BLOCK.register(
                ManualApplicationCompat::onRightClickBlock
        );

        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGHEST,
                ManualApplicationCompat::onWrenchRightClickBlock
        );
    }

    // =====================================================
    // CREATE WRENCH COMPATIBILITY
    // =====================================================

    private static void onWrenchRightClickBlock(
            PlayerInteractEvent.RightClickBlock event
    ) {

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        Level level = player.level();

        if (level.isClientSide || !player.mayBuild()) {
            return;
        }

        if (Liteminer.instance == null) {
            return;
        }

        InteractionHand hand = event.getHand();
        ItemStack held = player.getItemInHand(hand);

        if (held.isEmpty() || !AllItems.WRENCH.isIn(held)) {
            return;
        }

        LiteminerPlayerState state =
                Liteminer.instance.getPlayerState(player);

        if (!state.getKeymappingState()) {
            return;
        }

        BlockPos origin = event.getPos().immutable();
        Direction face = event.getFace();

        event.setCanceled(true);

        var walkers = Liteminer.WALKERS;

        int shape = state.getShape();

        if (shape < 0 || shape >= walkers.size()) {
            LOGGER.warn("Invalid Liteminer shape index: {}", shape);
            return;
        }

        List<BlockPos> selected = walkers.get(shape)
                .walk(level, player, origin)
                .stream()
                .map(BlockPos::immutable)
                .distinct()
                .sorted(Comparator.comparingInt(
                        pos -> pos.distManhattan(origin)
                ))
                .toList();

        boolean sneaking = player.isShiftKeyDown();

        List<BlockPos> targets = Stream
                .concat(Stream.of(origin), selected.stream())
                .distinct()
                .filter(level::hasChunkAt)
                .filter(pos -> isSupportedWrenchTarget(
                        level.getBlockState(pos),
                        sneaking
                ))
                .toList();

        int processed = 0;

        /*
         * Prevent Liteminer from intercepting Create's own block-break
         * events while Create processes the wrench interactions.
         */
        boolean liteminerWasActive = state.getKeymappingState();

        state.setKeymappingState(false);

        try {

            for (BlockPos pos : targets) {

                if (player.isRemoved()) {
                    break;
                }

                ItemStack current = player.getItemInHand(hand);

                if (current.isEmpty() || !AllItems.WRENCH.isIn(current)) {
                    break;
                }

                BlockState blockState = level.getBlockState(pos);

                if (!isSupportedWrenchTarget(
                        blockState,
                        player.isShiftKeyDown()
                )) {
                    continue;
                }

                BlockHitResult hit = hitAt(pos, face);

                UseOnContext context = new UseOnContext(
                        player,
                        hand,
                        hit
                );

                InteractionResult result = current.getItem().useOn(context);

                if (result.consumesAction()) {
                    processed++;
                }
            }

        } finally {
            state.setKeymappingState(liteminerWasActive);
        }

        LOGGER.info(
                "Create wrench selection at {}: {} walker positions, {} eligible targets, {} processed",
                origin,
                selected.size(),
                targets.size(),
                processed
        );
    }

    private static boolean isSupportedWrenchTarget(
            BlockState state,
            boolean sneaking
    ) {

        if (state.isAir()) {
            return false;
        }

        if (state.getBlock() instanceof IWrenchable) {
            return true;
        }

        return sneaking && state.is(WRENCH_PICKUP);
    }

    // =====================================================
    // MANUAL APPLICATION COMPATIBILITY
    // =====================================================

    private static EventResult onRightClickBlock(
            Player entity,
            InteractionHand hand,
            BlockPos clickedPos,
            Direction face
    ) {

        if (!(entity instanceof ServerPlayer player)
                || player.level().isClientSide
                || Liteminer.instance == null) {

            return EventResult.pass();
        }

        Level level = player.level();

        if (!player.mayBuild()) {
            return EventResult.pass();
        }

        LiteminerPlayerState state =
                Liteminer.instance.getPlayerState(player);

        if (!state.getKeymappingState()) {
            return EventResult.pass();
        }

        ItemStack held = player.getItemInHand(hand);

        if (held.isEmpty() || AllItems.WRENCH.isIn(held)) {
            return EventResult.pass();
        }

        /*
         * Find any matching Create manual application recipe.
         * No ingredient or block whitelist is used.
         */
        Optional<ManualApplicationRecipe> originRecipe =
                findRecipe(level, level.getBlockState(clickedPos), held);

        if (originRecipe.isEmpty()) {
            return EventResult.pass();
        }

        var walkers = Liteminer.WALKERS;

        int shape = state.getShape();

        if (shape < 0 || shape >= walkers.size()) {
            return EventResult.pass();
        }

        BlockPos origin = clickedPos.immutable();

        List<BlockPos> selected = walkers.get(shape)
                .walk(level, player, origin)
                .stream()
                .map(BlockPos::immutable)
                .filter(pos -> !pos.equals(origin))
                .distinct()
                .sorted(Comparator.comparingInt(
                        pos -> pos.distManhattan(origin)
                ))
                .toList();

        int applied = 0;

        for (BlockPos pos : selected) {

            if (player.isRemoved()) {
                break;
            }

            ItemStack current = player.getItemInHand(hand);

            if (current.isEmpty() || !level.hasChunkAt(pos)) {
                continue;
            }

            Optional<ManualApplicationRecipe> recipe = findRecipe(
                    level,
                    level.getBlockState(pos),
                    current
            );

            if (recipe.isEmpty()) {
                continue;
            }

            boolean consumed = !player.isCreative()
                    && !current.has(DataComponents.UNBREAKABLE)
                    && !recipe.get().shouldKeepHeldItem();

            if (consumed && current.getCount() <= 1) {
                break;
            }

            applyRecipe(player, hand, pos, face);
            applied++;
        }

        LOGGER.info(
                "Create manual application at {}: {} candidates, {} applications",
                origin,
                selected.size(),
                applied
        );

        return EventResult.pass();
    }

    /*
     * Finds ANY registered Create manual application recipe that
     * accepts the supplied block state and held item.
     *
     * This includes recipes registered by other mods.
     */
    private static Optional<ManualApplicationRecipe> findRecipe(
            Level level,
            BlockState state,
            ItemStack held
    ) {

        if (held.isEmpty() || state.isAir()) {
            return Optional.empty();
        }

        RecipeType<Recipe<RecipeWrapper>> type =
                (RecipeType<Recipe<RecipeWrapper>>) (RecipeType<?>)
                        AllRecipeTypes.ITEM_APPLICATION.getType();

        return level.getRecipeManager()
                .getAllRecipesFor(type)
                .stream()
                .filter(holder ->
                        holder.value() instanceof ManualApplicationRecipe recipe
                                && recipe.testBlock(state)
                                && recipe.getRequiredHeldItem().test(held)
                )
                .map(holder -> (ManualApplicationRecipe) holder.value())
                .findFirst();
    }

    private static void applyRecipe(
            ServerPlayer player,
            InteractionHand hand,
            BlockPos pos,
            Direction face
    ) {

        BlockHitResult hit = hitAt(pos, face);

        PlayerInteractEvent.RightClickBlock replay =
                new PlayerInteractEvent.RightClickBlock(
                        player,
                        hand,
                        pos,
                        hit
                );

        ManualApplicationRecipe.manualApplicationRecipesApplyInWorld(
                replay
        );
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static BlockHitResult hitAt(
            BlockPos pos,
            Direction face
    ) {

        return new BlockHitResult(
                Vec3.atCenterOf(pos),
                face,
                pos,
                false
        );
    }
}
