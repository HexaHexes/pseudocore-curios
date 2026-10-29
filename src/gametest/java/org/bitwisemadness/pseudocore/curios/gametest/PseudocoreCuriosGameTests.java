package org.bitwisemadness.pseudocore.curios.gametest;

import com.mojang.authlib.GameProfile;
import dev.gearevo.gearevolution.reconcile.PlayerGearReconciler;
import dev.gearevo.gearevolution.reconcile.ReconciliationMode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;

/**
 * Smoke test plus the NBT-retention gate (#2): a core that evolves through
 * Gear Evolution must keep its captured mob, or a lost component is a lost
 * mob - the worst failure this mod family can have.
 *
 * <p>The retention test drives the pack's <b>real</b> trigger, confirmed by the
 * pack owner: kill a mob with the gear held. The death policy awards the EP,
 * Gear Evolution kicks off the evolution (deferring it through
 * {@code DeathEvolutionEffects}, which is why the assertion runs on a later
 * tick), and the assertions read the truth back from the hand slot,
 * black-box: the item moved to the next tier of this mod's chain, and the
 * {@code contained_mob} component - type, UUID and full NBT - came through
 * unchanged. The dragon line needs Ice and Fire on the classpath and is not
 * staged here; the evolution machinery is shared, so one line covers both.</p>
 */
@GameTestHolder(PseudocoreCurios.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PseudocoreCuriosGameTests {

    private PseudocoreCuriosGameTests() {
    }

    /** The mod is loaded and the empty structure template resolves. */
    @GameTest(template = "empty")
    public static void mod_loads(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    @SuppressWarnings("unchecked")
    public static void evolutionKeepsTheCapturedMob(GameTestHelper helper) {
        PseudocoreCurios.LOGGER.info("retention stage: entered");
        var player = mockPlayer(helper);
        PseudocoreCurios.LOGGER.info("retention stage: player joined");
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);

        // Fill a copper core the way capture does - same component, same data.
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to lose");
        ContainedMobComponent before = stack.get(ModDataComponents.CONTAINED_MOB.get());
        check(before != null, "capture must store the contained_mob component");
        PseudocoreCurios.LOGGER.info("retention stage: captured");

        // Put Gear Evolution at the copper tier's cap - 1,500, the maxEP this
        // mod's own addon_chains declares - and reconcile once, as live play
        // has by the time gear fills: reconcile wires the chain's caps and
        // marks the stack tracked.
        DataComponentType<Double> maxEp = (DataComponentType<Double>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("tensura", "max_existence_point"))
                .orElse(null);
        DataComponentType<Double> ep = (DataComponentType<Double>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("tensura", "existence_point"))
                .orElse(null);
        check(maxEp != null && ep != null,
                "Tensura's existence_point / max_existence_point components must be registered");
        stack.set(maxEp, 1500.0);
        stack.set(ep, 1500.0);
        // The core rides the main hand from the start: the pack's trigger is a
        // kill with the gear HELD, and kill-EP attribution reads the held item.
        player.setItemSlot(EquipmentSlot.MAINHAND, stack);
        PlayerGearReconciler.reconcileVanillaInventory(player, ReconciliationMode.APPLY);
        ItemStack core = player.getMainHandItem();
        check(!core.isEmpty(), "the core must still occupy the slot after reconciliation");
        PseudocoreCurios.LOGGER.info("retention stage: reconciled");
        check(core.get(ModDataComponents.CONTAINED_MOB.get()) != null,
                "reconciliation must keep the contained_mob component");

        // The pack's real trigger: a kill with the gear held. In this stack
        // every damage pipeline bounces (both player-sourced and generic were
        // refused pre-health), so the test drives the death itself: the award
        // paths key on the death event with the player as the source, which is
        // exactly what die(playerAttack(player)) fires. The core is already in
        // the main hand from the reconcile setup above.
        cow.setHealth(0.0F);
        cow.die(player.damageSources().playerAttack(player));
        PseudocoreCurios.LOGGER.info("retention stage: death driven");
        check(cow.isRemoved() || cow.getHealth() <= 0.0F,
                "the probe cow must die for the kill award to run, health="
                        + cow.getHealth() + " removed=" + cow.isRemoved());

        PseudocoreCurios.LOGGER.info("retention stage: delay scheduled");
        helper.runAfterDelay(10, () -> {
            PseudocoreCurios.LOGGER.info("retention stage: callback fired");
            ItemStack after = player.getMainHandItem();
            check(!after.isEmpty(), "the evolved core must still be in the hand");
            ResourceLocation afterId = BuiltInRegistries.ITEM.getKey(after.getItem());
            check("iron_pseudocore".equals(afterId.getPath()),
                    "a carried core at full EP must evolve on a kill along this mod's chain, got " + afterId);

            ContainedMobComponent kept = after.get(ModDataComponents.CONTAINED_MOB.get());
            check(kept != null,
                    "evolution must keep the contained_mob component - a lost component is a lost mob (#2)");
            check(before.entityType().equals(kept.entityType()),
                    "the stored mob's type must survive, got " + kept.entityType() + " vs " + before.entityType());
            check(before.entityUuid().equals(kept.entityUuid()),
                    "the stored mob's UUID must survive, got " + kept.entityUuid() + " vs " + before.entityUuid());
            check(before.entityData().equals(kept.entityData()),
                    "the stored mob's NBT must survive byte for byte - this is the whole test (#2)");
            PseudocoreCurios.LOGGER.info("retention stage: checks passed");
            helper.succeed();
        });
    }

    /**
     * A real, connected ServerPlayer: constructed raw, then joined through
     * {@code PlayerList.placeNewPlayer} - the real join flow, which
     * transitions the connection to PLAY before login events fire. That
     * ordering matters: the naive mock factory triggers Curios's
     * {@code sync_data} while the connection is still handshaking, and
     * NeoForge rejects the payload ("Payload may not be sent to the client"),
     * aborting the login before the player ever reaches the level - which in
     * turn makes kill attribution refuse all damage. {@code placeNewPlayer}
     * sends its packets over a channel-less connection, where they are
     * dropped rather than rejected.
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        PseudocoreCurios.LOGGER.info("retention stage: mockPlayer building");
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(java.util.UUID.randomUUID(), "PseudocoreRetention");
        var player = new ServerPlayer(server, helper.getLevel(), profile,
                ClientInformation.createDefault());

        // A real netty channel so placeNewPlayer's packet writes land somewhere:
        // a bare Connection has a null channel and vanilla's first send NPEs.
        // channelActive() is what assigns Connection.channel, and the embedded
        // channel supplies a non-null remote address for the same call.
        var embedded = new io.netty.channel.embedded.EmbeddedChannel();
        embedded.pipeline().addFirst(new io.netty.channel.ChannelInboundHandlerAdapter());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        try {
            connection.channelActive(embedded.pipeline().firstContext());
        } catch (Exception ignored) {
            // Best effort - the placement fallback below covers a partial join.
        }

        // NeoForge validates every custom payload against the connection's
        // negotiated channels (NetworkRegistry.checkPacket), and our mock
        // never ran the configuration handshake - so Curios's login-time
        // sync would be rejected ("Payload may not be sent to the client").
        // Ad-hoc channels are the connection-local override for exactly this;
        // they need the channel attached first. The list is every
        // server->client SPacket id Curios registers (the class-name ->
        // snake_case convention matches both payloads observed rejecting);
        // whitelist overshoot is harmless - unsent ids are simply never asked
        // about.
        var curiosChannels = net.neoforged.neoforge.network.registration.ChannelAttributes
                .getOrCreateAdHocChannels(connection);
        for (var id : new String[]{
                "sync_data", "set_icons", "sync_curios", "sync_stack",
                "sync_modifiers", "sync_render", "sync_active_state",
                "page", "break", "grabbed_item", "quick_move"}) {
            curiosChannels.add(ResourceLocation.fromNamespaceAndPath("curios", id));
        }
        // ManasCore's login sync is bus-caught (non-fatal) but still prints a
        // stack trace per payload; whitelisting keeps the test log readable.
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath(
                "manascore_config", "sync_config_to_client"));
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath(
                "manascore_storage", "sync_chunk_storage"));

        try {
            server.getPlayerList().placeNewPlayer(connection, player,
                    CommonListenerCookie.createInitial(profile, false));
            PseudocoreCurios.LOGGER.info("retention stage: placeNewPlayer returned");
        } catch (RuntimeException joinNoise) {
            // If placement completed before the failure, the player is usable.
            for (var placed : helper.getLevel().players()) {
                if (placed.getUUID().equals(profile.getId())) {
                    return placed;
                }
            }
            throw new GameTestAssertException(
                    "placeNewPlayer failed before the player was placed: " + joinNoise);
        }
        return player;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
