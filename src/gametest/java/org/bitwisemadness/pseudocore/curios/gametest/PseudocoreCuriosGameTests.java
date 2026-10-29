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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageManager;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageRecord;
import org.bitwisemadness.pseudocore.curios.linkage.ModAttachments;
import org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents;
import org.bitwisemadness.pseudocore.curios.net.UnreachableRecallS2C;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

import java.util.UUID;

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

    @GameTest(template = "empty")
    public static void releaseStampsCoreTagsMobAndRecords(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed for a release to track");
        Vec3 pos = Vec3.atBottomCenterOf(cow.blockPosition()).add(0.0, 1.0, 0.0);
        check(PseudocoreItem.releaseContained(stack, helper.getLevel(), pos, 0.0F, player),
                "release must succeed");

        UUID linkage = stack.get(ModLinkageComponents.LINKAGE.get());
        check(linkage != null, "the released core must carry the linkage stamp (#8)");
        // Capture discarded the original entity; the tag and record belong to
        // the copy releaseContained spawned at the release position.
        var released = helper.getLevel().getEntitiesOfClass(Cow.class, new AABB(pos, pos).inflate(3));
        check(!released.isEmpty(), "the released cow must exist in the world");
        var spawned = released.get(0);
        check(linkage.equals(LinkageManager.taggedLinkage(spawned)),
                "the released mob's tag must carry the same linkage");
        check(player.getUUID().equals(LinkageManager.taggedOwner(spawned)),
                "the tag must record the releasing player");
        LinkageRecord record = LinkageManager.find(player, linkage);
        check(record != null, "the attachment must record the release");
        check(spawned.getUUID().toString().equals(record.mobUuid()),
                "the record must name the released mob");
        check(helper.getLevel().dimension().location().toString().equals(record.dimension()),
                "the record must carry the dimension");
        check(cow.getName().getString().equals(record.name()),
                "the record must carry the display name");

        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unloadRefreshKeepsLastKnownPosition(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed for a release to track");
        Vec3 pos = Vec3.atBottomCenterOf(cow.blockPosition()).add(0.0, 1.0, 0.0);
        check(PseudocoreItem.releaseContained(stack, helper.getLevel(), pos, 0.0F, player),
                "release must succeed");
        UUID linkage = stack.get(ModLinkageComponents.LINKAGE.get());

        // Capture discarded the original; the tagged entity is the copy that
        // releaseContained spawned. It wanders, then its chunk unloads:
        // NeoForge fires the leave event with the live entity, and the handler
        // records the final position - driven through the real event.
        var released = helper.getLevel().getEntitiesOfClass(Cow.class, new AABB(pos, pos).inflate(3));
        check(!released.isEmpty(), "the released cow must exist in the world");
        var spawned = released.get(0);
        spawned.teleportTo(spawned.getX() + 50.0, spawned.getY(), spawned.getZ() + 30.0);
        NeoForge.EVENT_BUS.post(new EntityLeaveLevelEvent(spawned, helper.getLevel()));

        LinkageRecord record = LinkageManager.find(player, linkage);
        check(record != null, "the record must survive the leave event");
        check(Math.abs(record.x() - spawned.getX()) < 0.01 && Math.abs(record.z() - spawned.getZ()) < 0.01,
                "the unload refresh must store the mob's final position, got " + record.x()
                        + "/" + record.z() + " vs " + spawned.getX() + "/" + spawned.getZ());

        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void linkageRecordsAreCappedAndForgettable(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed for a release to track");
        Vec3 pos = Vec3.atBottomCenterOf(cow.blockPosition()).add(0.0, 1.0, 0.0);
        check(PseudocoreItem.releaseContained(stack, helper.getLevel(), pos, 0.0F, player),
                "release must succeed");
        UUID first = stack.get(ModLinkageComponents.LINKAGE.get());

        // Flood past the cap through the same API the release listener uses.
        UUID last = null;
        for (int i = 0; i < ModAttachments.MAX_LINKAGES + 40; i++) {
            last = LinkageManager.remember(player, stack, cow);
        }
        check(ModAttachments.of(player).size() == ModAttachments.MAX_LINKAGES,
                "the cap must bound the attachment (#18)");
        check(LinkageManager.find(player, first) == null,
                "the oldest records must be evicted first");
        check(LinkageManager.find(player, last) != null,
                "the newest record must survive the cap");

        LinkageManager.forget(player, last);
        check(LinkageManager.find(player, last) == null,
                "forget must drop a consumed record (#18)");

        helper.succeed();
    }

    /**
     * The Curios slot itself (#7): players get one {@code pseudocore} slot of
     * size 1, it accepts a core, and it refuses anything else. The accept
     * half pins the whole data chain at once - slot definition loaded from
     * {@code curios/slots}, player grant from {@code curios/entities}, and
     * the {@code curios:tag} validator reading {@code curios:pseudocore},
     * whose membership comes from this addon's tag file. A typo in any of
     * the three files fails one of the checks below with its own message.
     */
    @GameTest(template = "empty")
    public static void slotGrantsAndValidatesCores(GameTestHelper helper) {
        var player = mockPlayer(helper);

        var inventory = CuriosApi.getCuriosInventory(player);
        check(inventory.isPresent(), "the joined player must have a Curios inventory (#7)");
        check(inventory.get().getCurios().containsKey("pseudocore"),
                "the entity grant must put a pseudocore slot on players (#7)");

        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        // The two flags are rendering context (visible / cosmetic), not
        // validity inputs - false/false is a plain visible-slot context.
        var slotContext = new SlotContext("pseudocore", player, 0, false, false);
        var helper2 = CuriosApi.getCuriosHelper();
        check(helper2.isStackValid(slotContext, new ItemStack(copper)),
                "the slot must accept a core - the curios:tag validator must see it in curios:pseudocore (#7)");
        check(!helper2.isStackValid(slotContext, new ItemStack(Items.STICK)),
                "the slot must reject a non-core (#7)");

        helper.succeed();
    }

    /**
     * The packet handler's three refusals (#11): empty slot, non-core in the
     * slot, sender without the slot. Each press is driven through the same
     * {@link ReleaseRecallAction#handle} the payload calls, so the assertions
     * read the exact refusal the player would be told.
     */
    @GameTest(template = "empty")
    public static void hotkeyRefusesBadSlotStates(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        check(inventory != null, "the joined player must have a Curios inventory (#11)");
        check(inventory.getCurios().get(PseudocoreCurios.SLOT_ID) != null,
                "the entity grant must put a pseudocore slot on players (#7)");

        // Fresh slot: nothing in it yet.
        check(ReleaseRecallAction.handle(player) == ReleaseRecallAction.Refusal.NO_CORE,
                "an empty slot must refuse with NO_CORE (#11)");

        // Something that is not a core. The raw handler write bypasses GUI
        // validation on purpose: the server must refuse by ITS own check, not
        // by Curios's - the packet path is not the GUI path.
        inventory.getCurios().get(PseudocoreCurios.SLOT_ID)
                .getStacks().setStackInSlot(0, new ItemStack(Items.STICK));
        check(ReleaseRecallAction.handle(player) == ReleaseRecallAction.Refusal.NOT_CORE,
                "a non-core in the slot must refuse with NOT_CORE (#11)");

        // Then the slot itself disappears: rebuild the inventory map without
        // it (setCurios is the server->client sync path, so this is the real
        // shape of a sender whose grant is absent).
        var remaining = new java.util.HashMap<>(inventory.getCurios());
        remaining.remove(PseudocoreCurios.SLOT_ID);
        inventory.setCurios(remaining);
        check(ReleaseRecallAction.handle(player) == ReleaseRecallAction.Refusal.NO_SLOT,
                "a sender without the slot must refuse with NO_SLOT (#11)");

        helper.succeed();
    }

    /**
     * The hotkey's happy path plus its spam bar (#11): a press releases the
     * filled core in the slot through the Phase-1 API - emptied stack, mob
     * standing at the player - and an immediate second press hits the
     * cooldown. Beast line only, matching the classpath rule the retention
     * test documents: dragon cores need Ice and Fire, which the CI
     * gametest environment does not ship.
     */
    @GameTest(template = "empty")
    public static void hotkeyReleasesCoreAndEnforcesCooldown(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a filled core to release");

        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        check(inventory != null, "the joined player must have a Curios inventory (#11)");
        inventory.getCurios().get(PseudocoreCurios.SLOT_ID)
                .getStacks().setStackInSlot(0, stack);

        check(ReleaseRecallAction.handle(player) == null,
                "a filled core in the slot must release (#11)");
        ItemStack inSlot = inventory.getCurios().get(PseudocoreCurios.SLOT_ID)
                .getStacks().getStackInSlot(0);
        check(inSlot.get(ModDataComponents.CONTAINED_MOB.get()) == null,
                "the press must empty the slot's core (#11)");
        // The released copy stands where the press put it: at the player's
        // feet (the original was discarded by capture, so any cow here is it).
        var released = helper.getLevel().getEntitiesOfClass(Cow.class,
                new AABB(player.position(), player.position()).inflate(4));
        check(!released.isEmpty(),
                "the mob must spawn at the player on a packet release (#11)");

        check(ReleaseRecallAction.handle(player) == ReleaseRecallAction.Refusal.COOLDOWN,
                "an immediate second press must hit the cooldown (#11)");

        helper.succeed();
    }

    /**
     * The happy recall (#12): release fills the record, the second press -
     * past the cooldown - resolves the core's own linkage to that record and
     * re-captures THE tracked mob through the gates: component restored with
     * the same mob UUID, the entity leaves the world (captured, not
     * duplicated), and the record is forgotten (#18's consumer contract).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void recallCapturesTheTrackedMob(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, stack);

        check(ReleaseRecallAction.handle(player) == null, "the first press must release (#11)");
        UUID linkage = stack.get(ModLinkageComponents.LINKAGE.get());
        check(linkage != null, "release must stamp the core");
        LinkageRecord record = LinkageManager.find(player, linkage);
        check(record != null, "release must record the mob");

        helper.runAfterDelay(ReleaseRecallAction.COOLDOWN_TICKS + 10, () -> {
            check(ReleaseRecallAction.handle(player) == null,
                    "the second press must recall the tracked mob (#12)");
            ContainedMobComponent restored = slotStack(player).get(ModDataComponents.CONTAINED_MOB.get());
            check(restored != null, "recall must restore the contained_mob component (#12)");
            check(restored.entityUuid().toString().equals(record.mobUuid()),
                    "recall must restore THE tracked mob, got " + restored.entityUuid()
                            + " vs " + record.mobUuid());
            check(LinkageManager.find(player, linkage) == null,
                    "a successful recall must forget its record (#18's consumer contract)");
            // By UUID, never by area scan: every mock player shares the world
            // spawn, so nearby cows belong to sibling tests - only the tracked
            // one proves this test's capture.
            check(helper.getLevel().getEntity(UUID.fromString(record.mobUuid())) == null,
                    "the tracked mob must be gone from the world - captured, not duplicated");

            helper.succeed();
        });
    }

    /**
     * Unloaded or absent mob, loaded chunks only (#12): after the release,
     * the mob is discarded (the leave event re-records its final spot) and
     * the next press reports {@code UNREACHABLE} - asserted as the
     * handler's result, never the chat text - with nothing captured. The
     * dimension-lookup half (cross-dimension only when the level exists)
     * shares this branch; a dimension cannot be unregistered from a test,
     * so that half stays code-covered by the same guard.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void recallReportsTheUnreachableMob(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, stack);

        check(ReleaseRecallAction.handle(player) == null, "the first press must release (#11)");
        UUID linkage = stack.get(ModLinkageComponents.LINKAGE.get());
        check(linkage != null, "release must stamp the core");
        LinkageRecord record = LinkageManager.find(player, linkage);
        check(record != null, "release must record the mob");

        // By UUID: the shared spawn area holds sibling tests' released cows,
        // so an area query could discard the wrong one - the record names
        // exactly this test's mob.
        var tracked = helper.getLevel().getEntity(UUID.fromString(record.mobUuid()));
        check(tracked != null, "the released cow must exist in the world");
        tracked.discard();

        helper.runAfterDelay(ReleaseRecallAction.COOLDOWN_TICKS + 10, () -> {
            var refusal = ReleaseRecallAction.handle(player);
            check(refusal == ReleaseRecallAction.Refusal.UNREACHABLE,
                    "an absent mob must report UNREACHABLE (#12), got " + refusal);
            check(slotStack(player).get(ModDataComponents.CONTAINED_MOB.get()) == null,
                    "an unreachable recall must not capture anything");
            check(slotStack(player).get(ModLinkageComponents.LINKAGE.get()) != null,
                    "the core keeps its stamp - the record stays answerable");

            // The waypoint half's server side (#13): the exact payload the
            // branch hands to PacketDistributor - payloadFor is the one
            // builder unreachable() calls, one line away - carrying the
            // record's name, its position with the message's rounding, and
            // the dimension id.
            var waypoint = ReleaseRecallAction.payloadFor(record);
            check(waypoint.name().equals(record.name()),
                    "the waypoint must carry the mob's name, got " + waypoint.name());
            check(waypoint.x() == Math.round(record.x())
                            && waypoint.y() == Math.round(record.y())
                            && waypoint.z() == Math.round(record.z()),
                    "the waypoint must carry the record's position, got "
                            + waypoint.x() + "/" + waypoint.y() + "/" + waypoint.z() + " vs "
                            + record.x() + "/" + record.y() + "/" + record.z());
            check(waypoint.dimension().equals(record.dimension()),
                    "the waypoint must carry the dimension, got " + waypoint.dimension());

            helper.succeed();
        });
    }

    /**
     * Linkage scoping, the wrong-core half (#12): core A released the cow,
     * so core A would recall it - but a fresh core B carries no stamp and
     * must answer NOT_LINKED with the cow still free. The record lookup is
     * keyed by the core's own linkage, never "whatever this player released
     * last", so another core cannot pull another core's mob.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void wrongCoreNeverRecallsAnotherMobsMob(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack coreA = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, coreA) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, coreA);
        check(ReleaseRecallAction.handle(player) == null,
                "core A must release its own mob (#11)");
        UUID linkageA = coreA.get(ModLinkageComponents.LINKAGE.get());
        check(linkageA != null, "core A's release must stamp it");
        LinkageRecord recordA = LinkageManager.find(player, linkageA);
        check(recordA != null, "core A's release must record its mob");

        ItemStack coreB = new ItemStack(copper);
        check(coreB.get(ModLinkageComponents.LINKAGE.get()) == null,
                "a fresh core carries no stamp");
        helper.runAfterDelay(ReleaseRecallAction.COOLDOWN_TICKS + 10, () -> {
            equipInSlot(player, coreB);
            var refusal = ReleaseRecallAction.handle(player);
            check(refusal == ReleaseRecallAction.Refusal.NOT_LINKED,
                    "a fresh core must refuse with NOT_LINKED, got " + refusal);
            check(slotStack(player).get(ModDataComponents.CONTAINED_MOB.get()) == null,
                    "the wrong core must capture nothing");
            // By UUID: the shared spawn area is full of sibling tests' cows,
            // so only this record's mob proves core A's cow is untouched.
            check(helper.getLevel().getEntity(UUID.fromString(recordA.mobUuid())) != null,
                    "core A's cow must still be free in the world");

            helper.succeed();
        });
    }

    /**
     * Anti-theft re-applies on recall (#12): the tracked wolf's owner is
     * switched to another player between release and recall, and the capture
     * gate refuses - {@code GATED}, the gate's own message, nothing stored,
     * the wolf still free. The gates decide, not this mod: the recall path
     * only hands the mob to the same {@code tryCapture} the click uses.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void recallReappliesTheAntiTheftGate(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var wolf = helper.spawn(EntityType.WOLF, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack stack = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, wolf, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, stack);
        check(ReleaseRecallAction.handle(player) == null, "the first press must release (#11)");
        UUID wolfLinkage = stack.get(ModLinkageComponents.LINKAGE.get());
        check(wolfLinkage != null, "release must stamp the core");
        LinkageRecord wolfRecord = LinkageManager.find(player, wolfLinkage);
        check(wolfRecord != null, "release must record the mob");

        // By UUID: the shared spawn area holds sibling tests' released mobs.
        var trackedWolf = helper.getLevel().getEntity(UUID.fromString(wolfRecord.mobUuid()));
        check(trackedWolf != null, "the released wolf must exist in the world");
        check(trackedWolf instanceof TamableAnimal, "a wolf is tamable");
        ((TamableAnimal) trackedWolf).setOwnerUUID(UUID.randomUUID());

        helper.runAfterDelay(ReleaseRecallAction.COOLDOWN_TICKS + 10, () -> {
            var refusal = ReleaseRecallAction.handle(player);
            check(refusal == ReleaseRecallAction.Refusal.GATED,
                    "another player's owned mob must refuse with GATED (#12), got " + refusal);
            check(slotStack(player).get(ModDataComponents.CONTAINED_MOB.get()) == null,
                    "the anti-theft gate must block the capture");
            check(helper.getLevel().getEntity(UUID.fromString(wolfRecord.mobUuid())) != null,
                    "the wolf must still be free in the world");

            helper.succeed();
        });
    }

    /**
     * Air right-click release and recall (#34): a core held <b>in hand</b> -
     * deliberately not the slot's - works through the same event players
     * fire, sharing the hotkey's cooldown. Pinned: the click is consumed,
     * the press acts on the hand stack while the slot's (different, empty)
     * core stays untouched, a second immediate click is throttled, and
     * after the cooldown the emptied hand core recalls its own mob with the
     * record forgotten. Dragon line excluded by scope (see #34: the class
     * cannot be named on this classpath).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void airClickReleasesAndRecallsFromTheHand(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack hand = new ItemStack(copper);
        check(PseudocoreItem.tryCapture(player, cow, hand) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        player.setItemSlot(EquipmentSlot.MAINHAND, hand);
        // A second, empty core in the Curios slot: the air press must never
        // touch it - that independence is the whole report (#34).
        equipInSlot(player, new ItemStack(copper));

        var first = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
                .RightClickItem(player, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(first);
        check(first.isCanceled(), "the air press must consume the click (#34)");
        check(hand.get(ModDataComponents.CONTAINED_MOB.get()) == null,
                "the hand core must be released by the air press");
        UUID linkage = hand.get(ModLinkageComponents.LINKAGE.get());
        check(linkage != null, "the air release must stamp the hand core");
        LinkageRecord record = LinkageManager.find(player, linkage);
        check(record != null, "the air release must record the mob");
        check(slotStack(player).get(ModLinkageComponents.LINKAGE.get()) == null
                        && slotStack(player).get(ModDataComponents.CONTAINED_MOB.get()) == null,
                "the slot's own core must stay untouched (#34)");

        // Same throttle as the hotkey: an immediate second click does nothing.
        var immediate = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
                .RightClickItem(player, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(immediate);
        check(hand.get(ModDataComponents.CONTAINED_MOB.get()) == null,
                "the shared cooldown must block an immediate second press (#11/#34)");

        helper.runAfterDelay(ReleaseRecallAction.COOLDOWN_TICKS + 10, () -> {
            var second = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
                    .RightClickItem(player, InteractionHand.MAIN_HAND);
            NeoForge.EVENT_BUS.post(second);
            check(second.isCanceled(), "the recalling press must consume the click too");
            ContainedMobComponent restored = hand.get(ModDataComponents.CONTAINED_MOB.get());
            check(restored != null, "the emptied hand core must recall its mob (#34)");
            check(restored.entityUuid().toString().equals(record.mobUuid()),
                    "the hand core must recall THE mob it released");
            check(helper.getLevel().getEntity(UUID.fromString(record.mobUuid())) == null,
                    "the recalled mob must leave the world");
            check(LinkageManager.find(player, linkage) == null,
                    "the air recall must forget its record like the hotkey does");
            check(slotStack(player).get(ModLinkageComponents.LINKAGE.get()) == null,
                    "the slot's core must stay untouched through the recall too");

            helper.succeed();
        });
    }

    /** Drop a stack into the Curios slot through the raw handler write. */
    private static void equipInSlot(ServerPlayer player, ItemStack stack) {
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        check(inventory != null, "the joined player must have a Curios inventory (#7)");
        inventory.getCurios().get(PseudocoreCurios.SLOT_ID)
                .getStacks().setStackInSlot(0, stack);
    }

    /** The stack currently in the Curios slot. */
    private static ItemStack slotStack(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player).orElseThrow()
                .getCurios().get(PseudocoreCurios.SLOT_ID)
                .getStacks().getStackInSlot(0);
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
        // Full cross-product of its payload namespaces and sync fragments
        // (scanned from the nested module jars): wrong pairs are never sent,
        // right pairs are always covered - no whack-a-mole per run.
        for (var ns : new String[]{"manascore_config", "manascore_storage",
                "manascore_world_storage", "manascore_registry_storage"}) {
            for (var frag : new String[]{"sync_config_to_client", "sync_chunk_storage",
                    "sync_entity_storage", "sync_world_storage"}) {
                curiosChannels.add(ResourceLocation.fromNamespaceAndPath(ns, frag));
            }
        }
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath("neoforge", "advanced_open_screen"));
        // Our own clientbound payload (#13): the mock connection accepts only
        // listed ad-hoc channels, and unreachable() sends this one.
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath(
                PseudocoreCurios.MOD_ID, "unreachable_recall"));

        try {
            server.getPlayerList().placeNewPlayer(connection, player,
                    CommonListenerCookie.createInitial(profile, false));
            PseudocoreCurios.LOGGER.info("retention stage: placeNewPlayer returned");
        } catch (RuntimeException joinNoise) {
            PseudocoreCurios.LOGGER.info(
                    "retention stage: placeNewPlayer aborted ({}), fetching from the level", joinNoise.getMessage());
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
