package org.bitwisemadness.pseudocore.curios.gametest;

import com.mojang.authlib.GameProfile;
import dev.gearevo.gearevolution.reconcile.PlayerGearReconciler;
import dev.gearevo.gearevolution.reconcile.ReconciliationMode;
import java.util.UUID;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.curios.ActiveCore;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.PseudocoreCuriosConfig;
import org.bitwisemadness.pseudocore.curios.PseudocoreCuriosEvents;
import org.bitwisemadness.pseudocore.curios.death.DeadMobComponent;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageManager;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageRecord;
import org.bitwisemadness.pseudocore.curios.linkage.ModAttachments;
import org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

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
        ItemStack stack = core(helper, copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to lose");
        ContainedMobComponent before = stack.get(ModDataComponents.CONTAINED_MOB.get());
        check(before != null, "capture must store the contained_mob component");
        PseudocoreCurios.LOGGER.info("retention stage: captured");

        // Put Gear Evolution at the copper tier's cap - 5,500, the maxEP this mod's own
        // addon_chains declares (#33's armor-aligned curve) - and reconcile once, as live play
        // has by the time gear fills: reconcile wires the chain's caps and marks the stack tracked.
        DataComponentType<Double> maxEp = (DataComponentType<Double>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("tensura", "max_existence_point"))
                .orElse(null);
        DataComponentType<Double> ep = (DataComponentType<Double>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("tensura", "existence_point"))
                .orElse(null);
        check(maxEp != null && ep != null,
                "Tensura's existence_point / max_existence_point components must be registered");
        stack.set(maxEp, 5500.0);
        stack.set(ep, 5500.0);
        // The core rides the main hand from the start: the pack's trigger is a
        // kill with the gear HELD, and kill-EP attribution reads the held item.
        player.setItemSlot(EquipmentSlot.MAINHAND, stack);
        PlayerGearReconciler.reconcileVanillaInventory(player, ReconciliationMode.APPLY);
        ItemStack core = player.getMainHandItem();
        check(!core.isEmpty(), "the core must still occupy the slot after reconciliation");
        PseudocoreCurios.LOGGER.info("retention stage: reconciled");
        check(core.get(ModDataComponents.CONTAINED_MOB.get()) != null,
                "reconciliation must keep the contained_mob component");

        // The pack's real trigger: a kill with the gear held. Every damage pipeline bounces in this
        // stack (both refused pre-health), so the test drives the death itself: the award paths key
        // on a death event with the player as source - what die(playerAttack(player)) fires, core in hand.
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
        ItemStack stack = core(helper, copper);
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
        ItemStack stack = core(helper, copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed for a release to track");
        Vec3 pos = Vec3.atBottomCenterOf(cow.blockPosition()).add(0.0, 1.0, 0.0);
        check(PseudocoreItem.releaseContained(stack, helper.getLevel(), pos, 0.0F, player),
                "release must succeed");
        UUID linkage = stack.get(ModLinkageComponents.LINKAGE.get());

        // Capture discarded the original; the tagged entity is the copy that releaseContained
        // spawned. It wanders, then its chunk unloads: NeoForge fires the leave event with the
        // live entity, and the handler records the final position - driven through the real event.
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
        ItemStack stack = core(helper, copper);
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
        check(helper2.isStackValid(slotContext, core(helper, copper)),
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
        ItemStack stack = core(helper, copper);
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
        ItemStack stack = core(helper, copper);
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
        ItemStack stack = core(helper, copper);
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

            // The waypoint half's server side (#13): the exact payload the branch hands to
            // PacketDistributor - payloadFor is the one builder unreachable() calls, one line away -
            // carrying the record's name, its position with the message's rounding, and the dimension id.
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
        ItemStack coreA = core(helper, copper);
        check(PseudocoreItem.tryCapture(player, cow, coreA) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, coreA);
        check(ReleaseRecallAction.handle(player) == null,
                "core A must release its own mob (#11)");
        UUID linkageA = coreA.get(ModLinkageComponents.LINKAGE.get());
        check(linkageA != null, "core A's release must stamp it");
        LinkageRecord recordA = LinkageManager.find(player, linkageA);
        check(recordA != null, "core A's release must record its mob");

        ItemStack coreB = core(helper, copper);
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
        ItemStack stack = core(helper, copper);
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
        ItemStack hand = core(helper, copper);
        check(PseudocoreItem.tryCapture(player, cow, hand) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        player.setItemSlot(EquipmentSlot.MAINHAND, hand);
        // A second, empty core in the Curios slot: the air press must never
        // touch it - that independence is the whole report (#34).
        equipInSlot(player, core(helper, copper));

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

    /**
     * #14 gate (a): off by default, deaths stay deaths - same atomic body
     * as every config-mutating test here (a gametest function runs to
     * completion within its tick unless it schedules, so sibling tests'
     * flip windows cannot interleave).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathSaveWithoutTheEnchantmentLeavesTheKillAlone(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        // releasedSlotCore carries only wild_capture - no preservation - so
        // this probe is exactly the "core without the enchantment" case (#67).
        ItemStack stack = releasedSlotCore(helper, player, cow);
        UUID mob = stampedMob(helper, player, stack);

        var doomed = helper.getLevel().getEntity(mob);
        check(doomed != null, "the released cow must exist before the hit");
        doomed.hurt(player.damageSources().playerAttack(player), 20.0F);

        // Death state, not storage absence: a naturally dead mob lingers in
        // the level for its death animation (capture's discard is immediate,
        // which is why the preserve tests may assert absence).
        check(doomed instanceof net.minecraft.world.entity.LivingEntity living
                        && living.isDeadOrDying(),
                "without the enchantment the cow must simply die");
        check(slotStack(player).get(ModLinkageComponents.DEAD_MOB.get()) == null
                        && !slotStack(player).has(ModDataComponents.CONTAINED_MOB.get()),
                "without the enchantment nothing may be written into the core");

        helper.succeed();
    }

    /**
     * #14 gate (b): the preserve path end to end - configured source,
     * lethal hit, capture-first, migration - plus the press reporting the
     * dead state instead of releasing (delayed hop runs after the config
     * is restored; the press never reads the switch).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathSavePreservesIntoTheSlotCore(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack stack = releasedSlotCore(helper, player, cow);
        makePreserving(helper, stack);
        UUID mob = stampedMob(helper, player, stack);

        helper.getLevel().getEntity(mob)
                .hurt(player.damageSources().playerAttack(player), 20.0F);

        check(helper.getLevel().getEntity(mob) == null,
                "the tracked mob must leave the world - captured, not merely dead");
        DeadMobComponent dead = slotStack(player).get(ModLinkageComponents.DEAD_MOB.get());
        check(dead != null,
                "the slot core must carry the death record (#14)");
        check(dead.entityUuid().toString().equals(mob.toString()),
                "the record must name the tracked mob, got " + dead.entityUuid()
                        + " vs " + mob);
        check(!slotStack(player).has(ModDataComponents.CONTAINED_MOB.get()),
                "the live half must be stripped - base sees an empty core");
        check(dead.damageType().toString().equals("minecraft:player_attack"),
                "the whitelisted source must be recorded, got " + dead.damageType());

        helper.runAfterDelay(ReleaseRecallAction.COOLDOWN_TICKS + 10, () -> {
            var refusal = ReleaseRecallAction.handle(player);
            check(refusal == ReleaseRecallAction.Refusal.HAS_DEAD,
                    "a dead-record core must report HAS_DEAD instead of releasing or recalling (#14), got "
                            + refusal);
            helper.succeed();
        });
    }

    /** #14 gate (c): an unwhitelisted source just kills - nothing written. */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathSaveIgnoresUnwhitelistedSources(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack stack = releasedSlotCore(helper, player, cow);
        makePreserving(helper, stack);
        UUID mob = stampedMob(helper, player, stack);

        var doomed = helper.getLevel().getEntity(mob);
        check(doomed != null, "the released cow must exist before the hit");
        // cactus is a real, non-whitelisted type - the whitelist, not the
        // enchantment, is what this pins.
        doomed.hurt(helper.getLevel().damageSources().cactus(), 20.0F);
        check(doomed instanceof net.minecraft.world.entity.LivingEntity living
                        && living.isDeadOrDying(),
                "a non-whitelisted source must kill normally even with the enchantment");
        check(slotStack(player).get(ModLinkageComponents.DEAD_MOB.get()) == null,
                "a non-whitelisted source must never write the death record");

        helper.succeed();
    }

    /**
     * #14 gate (d): linkage strictness with the widening on - only the
     * core that released the mob may receive it. The wrong-stamped core
     * sits in the slot (spec: "a different core in the slot stays
     * untouched"), the right one waits in the inventory.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathSaveFindsOnlyTheLinkageCore(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack rightCore = releasedSlotCore(helper, player, cow);
        UUID mob = stampedMob(helper, player, rightCore);
        UUID linkage = rightCore.get(ModLinkageComponents.LINKAGE.get());

        // Move the released core into the inventory, park a stranger in the slot.
        ItemStack inSlot = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        equipInSlot(player, inSlot);
        // Enchant BEFORE the inventory takes it: 1.21.1's Inventory.add
        // places a component-copy (copyWithCount + grow), so the enchantment
        // must be on the stack first to ride into the slot (#67).
        makePreserving(helper, rightCore);
        check(player.getInventory().add(rightCore),
                "the released core must fit into the main inventory");

        PseudocoreCuriosConfig.COMMON.deathSaveAllowNonSlotCores.set(true);
        try {
            helper.getLevel().getEntity(mob)
                    .hurt(player.damageSources().playerAttack(player), 20.0F);

            ItemStack found = null;
            for (ItemStack candidate : player.getInventory().items) {
                if (linkage.equals(candidate.get(ModLinkageComponents.LINKAGE.get()))) {
                    found = candidate;
                    break;
                }
            }
            check(found != null && found.get(ModLinkageComponents.DEAD_MOB.get()) != null,
                    "the linkage-matching core in the inventory must receive the mob (#14)");
            check(inSlot.get(ModLinkageComponents.DEAD_MOB.get()) == null
                            && !inSlot.has(ModDataComponents.CONTAINED_MOB.get()),
                    "a different core in the slot must stay untouched (#14)");
        } finally {
            PseudocoreCuriosConfig.COMMON.deathSaveAllowNonSlotCores.set(false);
        }

        helper.succeed();
    }

    /** #14 gate (e): another player owns it now -> gate refuses -> death. */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathSaveYieldsToTheAntiTheftGate(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var wolf = helper.spawn(EntityType.WOLF, 1, 1, 1);
        ItemStack stack = releasedSlotCore(helper, player, wolf);
        UUID mob = stampedMob(helper, player, stack);
        var tracked = helper.getLevel().getEntity(mob);
        check(tracked instanceof TamableAnimal, "a wolf is tamable");
        ((TamableAnimal) tracked).setOwnerUUID(java.util.UUID.randomUUID());

        makePreserving(helper, stack);
        tracked.hurt(player.damageSources().playerAttack(player), 20.0F);
        check(tracked instanceof net.minecraft.world.entity.LivingEntity living
                        && living.isDeadOrDying(),
                "the gate refuses the capture, so the wolf dies normally");
        check(slotStack(player).get(ModLinkageComponents.DEAD_MOB.get()) == null,
                "a refused capture must write nothing");

        helper.succeed();
    }

    /**
     * Capture into the curios slot, release through the real press: the
     * returned stack is the emptied, linkage-stamped core sitting in the
     * slot - the state every death test starts from.
     */
    private static ItemStack releasedSlotCore(GameTestHelper helper, ServerPlayer player,
                                               net.minecraft.world.entity.LivingEntity mob) {
        return releasedSlotCore(helper, player, mob, 0);
    }

    /**
     * Same, into a chosen slot (#71): the press resolves the active
     * stack, so slot 6 also exercises the read-time fallback.
     */
    private static ItemStack releasedSlotCore(GameTestHelper helper, ServerPlayer player,
                                               net.minecraft.world.entity.LivingEntity mob,
                                               int slot) {
        ItemStack stack = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        check(PseudocoreItem.tryCapture(player, mob, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, slot, stack);
        check(ReleaseRecallAction.handle(player) == null,
                "the first press must release");
        check(stack.get(ModLinkageComponents.LINKAGE.get()) != null,
                "release must stamp the core");
        check(!stack.has(ModDataComponents.CONTAINED_MOB.get()),
                "the released core must be empty of live storage");
        return stack;
    }

    /** The released copy's UUID - the record names exactly that mob. */
    private static UUID stampedMob(GameTestHelper helper, ServerPlayer player, ItemStack core) {
        LinkageRecord record = LinkageManager.find(player,
                core.get(ModLinkageComponents.LINKAGE.get()));
        check(record != null, "release must record the mob");
        return UUID.fromString(record.mobUuid());
    }

    /**
     * #15 row 1 (default mode): sneak-clear on a filled core runs the
     * shared release - the mob spawns at the player's feet and the core
     * empties, same result as the hotkey. Driven handler-level (the issue
     * wording); the non-sneak air click is #34's press, covered there.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void clearDefaultsToReleaseAndEmptiesTheCore(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack hand = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        check(PseudocoreItem.tryCapture(player, cow, hand) == InteractionResult.SUCCESS,
                "capture must succeed so there is a filled core to clear");
        player.setItemSlot(EquipmentSlot.MAINHAND, hand);
        check(PseudocoreCuriosConfig.clearAction()
                        == org.bitwisemadness.pseudocore.curios.hotkey.ClearActionMode.RELEASE,
                "clear must default to release (#15)");

        org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, hand);

        check(hand.get(ModDataComponents.CONTAINED_MOB.get()) == null,
                "the cleared core must be empty of its mob");
        // By UUID: release restores the stored UUID, and every test's player
        // shares the world spawn - only this cow proves THIS release.
        check(helper.getLevel().getEntity(cow.getUUID()) != null,
                "release mode must respawn the captured mob at the player - the hotkey's result");

        helper.succeed();
    }

    /**
     * #15 row 3 (destroy mode): the first click only arms, the second
     * within the window executes - nothing is deleted on one click, and
     * the mob never spawns (destruction is destruction). Mode injected
     * directly: no live-config mutation spans the delayed hop.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void clearDestroyNeedsTheConfirmStep(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack hand = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        check(PseudocoreItem.tryCapture(player, cow, hand) == InteractionResult.SUCCESS,
                "capture must succeed so there is a filled core to destroy");
        player.setItemSlot(EquipmentSlot.MAINHAND, hand);

        var destroy = org.bitwisemadness.pseudocore.curios.hotkey.ClearActionMode.DESTROY;
        org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, hand, destroy);
        check(hand.get(ModDataComponents.CONTAINED_MOB.get()) != null,
                "the first click must only arm - one click can never eat a dragon (#15)");

        helper.runAfterDelay(org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction.COOLDOWN_TICKS + 10,
                () -> {
                    org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, hand, destroy);
                    check(hand.get(ModDataComponents.CONTAINED_MOB.get()) == null
                                    && !hand.has(ModLinkageComponents.DEAD_MOB.get()),
                            "the confirming click must delete the stored mob (#15)");
                    // By the captured mob's UUID: release would RESTORE
                    // it into the world, destroy never does - and the
                    // shared spawn holds sibling tests' cows.
                    check(helper.getLevel().getEntity(cow.getUUID()) == null,
                            "destroy mode must never spawn the mob");
                    helper.succeed();
                });
    }

    /**
     * #15 row 1 (death record): wiped instantly, config-independent - the
     * record's explicit exit. Built directly rather than through the death
     * flow: this row must not care how the record got there (or what the
     * config says).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void clearWipesDeathDataInstantly(GameTestHelper helper) {
        var player = mockPlayer(helper);
        ItemStack hand = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        hand.set(ModLinkageComponents.DEAD_MOB.get(),
                new org.bitwisemadness.pseudocore.curios.death.DeadMobComponent(
                        ResourceLocation.withDefaultNamespace("cow"),
                        java.util.UUID.randomUUID(), new net.minecraft.nbt.CompoundTag(),
                        ResourceLocation.withDefaultNamespace("player_attack"),
                        "player", System.currentTimeMillis()));
        player.setItemSlot(EquipmentSlot.MAINHAND, hand);

        // Default config is release - irrelevant here either way: no live
        // mob exists, so no mode applies.
        org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, hand);

        check(!hand.has(ModLinkageComponents.DEAD_MOB.get()),
                "the death record must be wiped instantly (#15)");
        check(!hand.has(ModDataComponents.CONTAINED_MOB.get()),
                "a wipe writes nothing else");

        helper.succeed();
    }

    /**
     * #15 row 2: an empty press is a silent no-op - and specifically does
     * NOT consume the shared cooldown (proven by an immediate press
     * reaching its own refusal instead of the throttle).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void clearOnEmptyIsSilentAndFree(GameTestHelper helper) {
        var player = mockPlayer(helper);
        ItemStack hand = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        player.setItemSlot(EquipmentSlot.MAINHAND, hand);

        org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, hand);

        check(!hand.has(ModLinkageComponents.DEAD_MOB.get())
                        && !hand.has(ModDataComponents.CONTAINED_MOB.get()),
                "an empty no-op changes nothing");
        var after = ReleaseRecallAction.press(player, hand);
        check(after == ReleaseRecallAction.Refusal.NOT_LINKED,
                "the no-op must not throttle the real triggers - expected the press's own "
                        + "NOT_LINKED refusal, got " + after);

        helper.succeed();
    }

    /**
     * #28: the upgrade recipe (#113) must carry the linkage stamp - the
     * addon's component rides pseudocore's generic component copy - and the
     * upgraded core must still recall the mob the *original* core
     * released. Driven through the real recipe (`pseudocore:copper_to_iron`,
     * shipped unconditionally since alpha.7), so the serializer, the copy
     * and the recall all get exercised end to end.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void upgradeRecipeKeepsTheLinkageStamp(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        var copper = BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_pseudocore"))
                .orElse(null);
        check(copper != null, "the copper core must exist");
        ItemStack stack = core(helper, copper);
        check(PseudocoreItem.tryCapture(player, cow, stack) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, stack);
        check(ReleaseRecallAction.handle(player) == null, "the first press must release (#11)");
        UUID linkage = stack.get(ModLinkageComponents.LINKAGE.get());
        check(linkage != null, "release must stamp the core");
        LinkageRecord record = LinkageManager.find(player, linkage);
        check(record != null, "release must record the mob");

        var recipes = helper.getLevel().getServer().getRecipeManager();
        var holder = recipes.byKey(
                ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_to_iron")).orElse(null);
        check(holder != null && holder.value() instanceof CraftingRecipe,
                "pseudocore:copper_to_iron must be loaded - the pin carries the upgrade recipes (#28)");
        CraftingRecipe recipe = (CraftingRecipe) holder.value();
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        CraftingInput input = CraftingInput.of(3, 3, java.util.List.of(
                ItemStack.EMPTY, iron, ItemStack.EMPTY,
                iron, stack, iron,
                ItemStack.EMPTY, iron, ItemStack.EMPTY));
        check(recipe.matches(input, helper.getLevel()),
                "the cross of four iron ingots around the core must match");
        ItemStack upgraded = recipe.assemble(input, helper.getLevel().registryAccess());
        check(upgraded.is(org.bitwisemadness.pseudocore.registry.ModItems.IRON_PSEUDOCORE.get()),
                "the upgrade must craft the iron core");
        check(linkage.equals(upgraded.get(ModLinkageComponents.LINKAGE.get())),
                "the upgrade must carry the linkage stamp verbatim (#28)");

        equipInSlot(player, upgraded);
        helper.runAfterDelay(ReleaseRecallAction.COOLDOWN_TICKS + 10, () -> {
            check(ReleaseRecallAction.handle(player) == null,
                    "the upgraded core must recall the mob the original released (#28)");
            ContainedMobComponent restored = slotStack(player).get(ModDataComponents.CONTAINED_MOB.get());
            check(restored != null, "recall must restore the contained_mob component");
            check(restored.entityUuid().toString().equals(record.mobUuid()),
                    "recall must resolve THE original record, got " + restored.entityUuid()
                            + " vs " + record.mobUuid());
            check(LinkageManager.find(player, linkage) == null,
                    "the recall must forget the record like any other");
            helper.succeed();
        });
    }

    /**
     * #48: the air press must claim a DRAGON core's click - the listener's
     * old {@code instanceof PseudocoreItem} gate silently excluded the whole
     * line ({@code DragonPseudocoreItem} extends IaF's horn item). Drives
     * the listener itself; the shared throttle proves the press ran: the
     * second (direct) press can only answer COOLDOWN because the first one
     * consumed the window. Needs IaF staged for gametests - without it the
     * dragon line does not even register.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void dragonCorePressesThroughTheAirListener(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var dragonCore = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("pseudocore", "copper_dragon_pseudocore"))
                .orElse(null);
        check(dragonCore != null,
                "the dragon line must exist in this environment - IaF is staged for gametests (#48)");
        ItemStack hand = new ItemStack(dragonCore);
        player.setItemSlot(EquipmentSlot.MAINHAND, hand);

        var event = new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
        PseudocoreCuriosEvents.onRightClickItem(event);

        check(event.isCanceled(),
                "the air listener must claim a dragon core's click (#48 - instanceof gate regression)");
        var after = ReleaseRecallAction.press(player, hand);
        check(after == ReleaseRecallAction.Refusal.COOLDOWN,
                "the listener must route into the press - the consumed cooldown is the proof, got " + after);

        helper.succeed();
    }

    /**
     * #51: the air press must also claim an OFFHAND core's click - the
     * listener's old main-hand-only filter ignored the second hand
     * entirely. Main hand stays empty (the vanilla condition under which
     * the offhand use runs at all); the shared throttle again proves the
     * press ran.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void offhandCorePressesThroughTheAirListener(GameTestHelper helper) {
        var player = mockPlayer(helper);
        ItemStack hand = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        player.setItemSlot(EquipmentSlot.OFFHAND, hand);

        var event = new PlayerInteractEvent.RightClickItem(player, InteractionHand.OFF_HAND);
        PseudocoreCuriosEvents.onRightClickItem(event);

        check(event.isCanceled(),
                "the air listener must claim an offhand core's click (#51)");
        var after = ReleaseRecallAction.press(player, hand);
        check(after == ReleaseRecallAction.Refusal.COOLDOWN,
                "the listener must route into the press - the consumed cooldown is the proof, got " + after);

        helper.succeed();
    }

    /**
     * #60: the clear gesture's destroy wipes the element trait together
     * with the storage - a cleared core forgets its dragon element and
     * is back to "never held one" for the branch evolution. The trait
     * rides GE's API through pseudocore's bridge; the two-step confirm
     * and the shared cooldown are the destroy test's, unchanged.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void clearDestroyWipesTheElementTrait(GameTestHelper helper) {
        var player = mockPlayer(helper);
        check(net.neoforged.fml.ModList.get().isLoaded("gearevolution"),
                "Gear Evolution must be staged for the trait test (#60)");
        var dragonCore = new ItemStack(BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("pseudocore", "netherite_dragon_pseudocore"))
                .orElse(null));
        check(dragonCore.getItem() != Items.AIR, "the netherite dragon core must exist");
        org.bitwisemadness.pseudocore.util.GearEvolutionBridge.setSpecialization(dragonCore, "ice");
        check("ice".equals(org.bitwisemadness.pseudocore.util.GearEvolutionBridge
                        .getSpecialization(dragonCore)),
                "the test must start with an element set");
        // Filled, not just trait-marked: an empty core is a silent no-op
        // for the clear gesture (#15), so the destroy never runs on it.
        dragonCore.set(org.bitwisemadness.pseudocore.registry.ModDataComponents.CONTAINED_MOB.get(),
                new org.bitwisemadness.pseudocore.item.component.ContainedMobComponent(
                        ResourceLocation.withDefaultNamespace("cow"), java.util.UUID.randomUUID(),
                        new net.minecraft.nbt.CompoundTag()));
        player.setItemSlot(EquipmentSlot.MAINHAND, dragonCore);

        var destroy = org.bitwisemadness.pseudocore.curios.hotkey.ClearActionMode.DESTROY;
        org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, dragonCore, destroy);
        check("ice".equals(org.bitwisemadness.pseudocore.util.GearEvolutionBridge
                        .getSpecialization(dragonCore)),
                "arming the confirm must not wipe the trait yet");

        // The record-only wipe is a clear too (#60): instant and
        // config-independent, and it forgets the element as well.
        var recordCore = new ItemStack(dragonCore.getItem());
        org.bitwisemadness.pseudocore.util.GearEvolutionBridge.setSpecialization(recordCore, "fire");
        recordCore.set(ModLinkageComponents.DEAD_MOB.get(), new DeadMobComponent(
                ResourceLocation.withDefaultNamespace("cow"), java.util.UUID.randomUUID(),
                new net.minecraft.nbt.CompoundTag(), ResourceLocation.withDefaultNamespace("player_attack"),
                "player", System.currentTimeMillis()));
        org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, recordCore, destroy);
        check(!recordCore.has(ModLinkageComponents.DEAD_MOB.get()),
                "the death record must wipe instantly, config-independent");
        check(org.bitwisemadness.pseudocore.util.GearEvolutionBridge
                        .getSpecialization(recordCore).isEmpty(),
                "the record wipe must clear the element trait too (#60)");

        helper.runAfterDelay(org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction
                .COOLDOWN_TICKS + 10, () -> {
            org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction.handle(player, dragonCore, destroy);
            check(org.bitwisemadness.pseudocore.util.GearEvolutionBridge
                            .getSpecialization(dragonCore).isEmpty(),
                    "the destroy must wipe the element trait with the storage (#60)");
            helper.succeed();
        });
    }

    /**
     * The press ability (#69): registered against core's registry, its
     * handler routes the curios slot press through the native pipeline,
     * and its availability projects this addon's own shared throttle -
     * so the bar's overlay, the bar's activation and the hotkey are one
     * verb with one window.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void pressAbilityRoutesThroughTheNativePipeline(GameTestHelper helper) {
        check(ModList.get().isLoaded("ability_core"),
                "ability-core must be staged for the ability test (#69)");

        // The definition exists with its metadata.
        var definition = org.bitwisemadness.abilitycore.discovery.AbilityDiscovery
                .get(org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID);
        check(definition.isPresent(), "the press ability must be registered");
        check(definition.get().activationType()
                        == org.bitwisemadness.abilitycore.api.ActivationType.INSTANT,
                "the press must be an instant ability");

        var player = mockPlayer(helper);

        // Empty slot -> core's UNAVAILABLE vocabulary (the native path's answer).
        var empty = org.bitwisemadness.abilitycore.AbilityCore.HANDLERS.get(
                        org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID)
                .orElseThrow()
                .activate(org.bitwisemadness.abilitycore.api.ActivationRequest.of(
                                org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID,
                                org.bitwisemadness.abilitycore.api.ActivationSource.DIRECT_SLOT),
                        player);
        check(empty.outcome() == org.bitwisemadness.abilitycore.api.ActivationOutcome.REFUSED_UNAVAILABLE,
                "an empty slot must refuse UNAVAILABLE through core's vocabulary, got " + empty.outcome());

        // A filled slot releases: ACTED - the same press the hotkey runs.
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack hand = core(helper, org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        check(PseudocoreItem.tryCapture(player, cow, hand) == InteractionResult.SUCCESS,
                "capture must succeed so there is a mob to release");
        equipInSlot(player, hand);
        var acted = org.bitwisemadness.abilitycore.AbilityCore.HANDLERS.get(
                        org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID)
                .orElseThrow()
                .activate(org.bitwisemadness.abilitycore.api.ActivationRequest.of(
                                org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID,
                                org.bitwisemadness.abilitycore.api.ActivationSource.DIRECT_SLOT),
                        player);
        check(acted.outcome() == org.bitwisemadness.abilitycore.api.ActivationOutcome.ACTED,
                "a filled core must release through the ability, got " + acted.outcome());

        // The shared throttle, read through core's vocabulary: a second
        // press is refused as COOLDOWN - one window for bar and hotkey.
        var cooled = org.bitwisemadness.abilitycore.AbilityCore.HANDLERS.get(
                        org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID)
                .orElseThrow()
                .activate(org.bitwisemadness.abilitycore.api.ActivationRequest.of(
                                org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID,
                                org.bitwisemadness.abilitycore.api.ActivationSource.DIRECT_SLOT),
                        player);
        check(cooled.outcome() == org.bitwisemadness.abilitycore.api.ActivationOutcome.REFUSED_COOLDOWN,
                "the second press must be refused as COOLDOWN, got " + cooled.outcome());

        // The overlay projection: a known cooldown metric and a verdict.
        var overlay = org.bitwisemadness.abilitycore.api.AvailabilityProviders.availabilityFor(
                player, org.bitwisemadness.pseudocore.curios.compat.AbilityCoreBridge.PRESS_ID);
        check(overlay.isPresent(), "the availability provider must be declared");
        check(overlay.get().cooldown().isKnown()
                        && overlay.get().cooldown().knownValue().orElse(0.0) > 0.0,
                "the overlay must project the shared throttle as a known metric");
        check(overlay.get().isAvailable(),
                "a slot holding a core must project as available");

        helper.succeed();
    }

    /**
     * Read-time resolution (#71): the stored index is never rewritten by
     * looking at it - clamped to the live slots, fallen back to the first
     * non-empty, -1 only when every slot is empty.
     */
    @GameTest(template = "empty")
    public static void activeCoreReclampsToTheFirstNonEmpty(GameTestHelper helper) {
        var player = mockPlayer(helper);
        equipInSlot(player, 0, core(helper,
                org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get()));
        equipInSlot(player, 3, core(helper,
                org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get()));
        equipInSlot(player, 5, core(helper,
                org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get()));

        ModAttachments.setActiveCoreIndex(player, 99);
        check(ActiveCore.resolveIndex(player) == 5,
                "an out-of-range index must clamp to the last live slot, got "
                        + ActiveCore.resolveIndex(player));

        ModAttachments.setActiveCoreIndex(player, 3);
        check(ActiveCore.resolveIndex(player) == 3,
                "an index on a filled slot must resolve to it, got "
                        + ActiveCore.resolveIndex(player));

        // Removing the active core moves the resolution, never the selection.
        equipInSlot(player, 3, ItemStack.EMPTY);
        check(ModAttachments.activeCoreIndex(player) == 3,
                "resolution must not rewrite the stored selection (#71)");
        check(ActiveCore.resolveIndex(player) == 0,
                "removing the active core must fall back to the first non-empty, got "
                        + ActiveCore.resolveIndex(player));

        equipInSlot(player, 0, ItemStack.EMPTY);
        equipInSlot(player, 5, ItemStack.EMPTY);
        check(ActiveCore.resolveIndex(player) == -1,
                "six empty slots must resolve to -1");
        check(ActiveCore.resolveStack(player).isEmpty(),
                "six empty slots must resolve to EMPTY");

        helper.succeed();
    }

    /**
     * The press acts on the resolved active stack (#71): two cores hold
     * two mobs, the selection points at the second, and only that one
     * releases - the strip's frame and the verb read the same state.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void pressActsOnTheActiveCore(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var firstCow = helper.spawn(EntityType.COW, 1, 1, 1);
        var secondCow = helper.spawn(EntityType.COW, 2, 1, 2);
        ItemStack slotZero = core(helper,
                org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        ItemStack slotOne = core(helper,
                org.bitwisemadness.pseudocore.registry.ModItems.COPPER_PSEUDOCORE.get());
        check(PseudocoreItem.tryCapture(player, firstCow, slotZero) == InteractionResult.SUCCESS,
                "the first capture must succeed");
        check(PseudocoreItem.tryCapture(player, secondCow, slotOne) == InteractionResult.SUCCESS,
                "the second capture must succeed");
        equipInSlot(player, 0, slotZero);
        equipInSlot(player, 1, slotOne);
        ModAttachments.setActiveCoreIndex(player, 1);

        check(ReleaseRecallAction.handle(player) == null,
                "pressing the active stack must release");
        check(!slotStack(player, 1).has(ModDataComponents.CONTAINED_MOB.get()),
                "the active core must have released its mob");
        check(slotStack(player, 1).get(ModLinkageComponents.LINKAGE.get()) != null,
                "release must stamp the active core");
        check(slotStack(player, 0).has(ModDataComponents.CONTAINED_MOB.get()),
                "the inactive core must keep its mob");

        var linkage = slotStack(player, 1).get(ModLinkageComponents.LINKAGE.get());
        var roaming = helper.getLevel().getEntitiesOfClass(Cow.class,
                new AABB(player.position(), player.position()).inflate(16.0D));
        check(roaming.stream().anyMatch(cow -> linkage.equals(LinkageManager.taggedLinkage(cow))),
                "the roaming mob must be the active core's, got " + roaming.size() + " cow(s)");

        helper.succeed();
    }

    /**
     * The death search is position-independent (#71): the linkage core
     * sits in the sixth slot and still receives the record - a six-slot
     * search, not a slot-0 assumption.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathPreserveFindsTheSixthSlotCore(GameTestHelper helper) {
        var player = mockPlayer(helper);
        var cow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack stack = releasedSlotCore(helper, player, cow, 5);
        UUID mob = stampedMob(helper, player, stack);
        makePreserving(helper, stack);

        helper.getLevel().getEntity(mob)
                .hurt(player.damageSources().playerAttack(player), 20.0F);

        check(helper.getLevel().getEntity(mob) == null,
                "the tracked mob must leave the world - captured, not merely dead");
        DeadMobComponent dead = slotStack(player, 5).get(ModLinkageComponents.DEAD_MOB.get());
        check(dead != null,
                "the death search must find the linkage core in slot 6 of 6 (#71)");
        check(dead.entityUuid().toString().equals(mob.toString()),
                "the record must name the tracked mob, got " + dead.entityUuid() + " vs " + mob);
        check(slotStack(player, 0).isEmpty(),
                "no earlier slot may receive the record (#71)");

        helper.succeed();
    }

    /**
     * The active index survives a re-join (#71): the full save -&gt; load
     * round trip through Entity's attachment NBT - the exact path
     * {@code placeNewPlayer} runs with a player's data file - so the
     * selection stored before logging off is the selection on return.
     */
    /**
     * The active index survives a re-join (#71): the round trip through
     * Entity's attachment NBT using the exact pair {@code PlayerDataStorage}
     * runs for the player's data file - {@code saveWithoutId} on save
     * ({@code Entity.save} gates on {@code canSerialize}, and players are
     * {@code noSave}) and {@code Entity.load} on join - so the selection
     * stored before logging off is the selection on return.
     */
    @GameTest(template = "empty")
    public static void activeCoreIndexPersistsAcrossRejoin(GameTestHelper helper) {
        var player = mockPlayer(helper);
        ModAttachments.setActiveCoreIndex(player, 3);

        var saved = new net.minecraft.nbt.CompoundTag();
        player.saveWithoutId(saved);
        var attachmentsRoot =
                net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY;
        check(saved.contains(attachmentsRoot),
                "the save must carry the attachments root");
        check(saved.getCompound(attachmentsRoot).contains(
                        ModAttachments.ACTIVE_CORE_INDEX.getKey().location().toString()),
                "the save must carry the active index, got "
                        + saved.getCompound(attachmentsRoot).getAllKeys());

        var rejoined = mockPlayer(helper);
        check(ModAttachments.activeCoreIndex(rejoined) == 0,
                "a fresh join must start at the default index");
        rejoined.load(saved);
        check(ModAttachments.activeCoreIndex(rejoined) == 3,
                "the saved index must come back on re-join, got "
                        + ModAttachments.activeCoreIndex(rejoined));

        helper.succeed();
    }

    /** Drop a stack into the Curios slot through the raw handler write. */
    private static void equipInSlot(ServerPlayer player, ItemStack stack) {
        equipInSlot(player, 0, stack);
    }

    /** Drop a stack into one of the six Curios slots (#71). */
    private static void equipInSlot(ServerPlayer player, int slot, ItemStack stack) {
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        check(inventory != null, "the joined player must have a Curios inventory (#7)");
        inventory.getCurios().get(PseudocoreCurios.SLOT_ID)
                .getStacks().setStackInSlot(slot, stack);
    }

    /** The stack currently in the Curios slot. */
    private static ItemStack slotStack(ServerPlayer player) {
        return slotStack(player, 0);
    }

    /** The stack currently in one of the six Curios slots (#71). */
    private static ItemStack slotStack(ServerPlayer player, int slot) {
        return CuriosApi.getCuriosInventory(player).orElseThrow()
                .getCurios().get(PseudocoreCurios.SLOT_ID)
                .getStacks().getStackInSlot(slot);
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
    /**
     * A core carrying {@code wild_capture} (#159's universal gate): every
     * probe in this suite captures untamed mobs, so every probe core opens
     * the wild axis. The enchantment is inert for the refusals these tests
     * actually pin (anti-theft, already-full, gates) - it only ever opens
     * wild.
     */
    private static ItemStack core(GameTestHelper helper, Item item) {
        ItemStack stack = new ItemStack(item);
        var holder = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.WILD_CAPTURE);
        stack.enchant(holder, 1);
        return stack;
    }

    /** Add the preservation enchantment to a probe core (#67). */
    private static void makePreserving(GameTestHelper helper, ItemStack stack) {
        stack.enchant(helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.PRESERVATION), 1);
    }

    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        PseudocoreCurios.LOGGER.info("retention stage: mockPlayer building");
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(java.util.UUID.randomUUID(), "PseudocoreRetention");
        var player = new ServerPlayer(server, helper.getLevel(), profile,
                ClientInformation.createDefault());

        // A real netty channel so placeNewPlayer's packet writes land somewhere: a bare
        // Connection has a null channel and vanilla's first send NPEs. channelActive() assigns
        // Connection.channel, and the embedded channel supplies a non-null remote address for it.
        var embedded = new io.netty.channel.embedded.EmbeddedChannel();
        embedded.pipeline().addFirst(new io.netty.channel.ChannelInboundHandlerAdapter());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        try {
            connection.channelActive(embedded.pipeline().firstContext());
        } catch (Exception ignored) {
            // Best effort - the placement fallback below covers a partial join.
        }

        // NeoForge validates every custom payload against the connection's negotiated channels, and
        // our mock never ran the handshake - so Curios's login sync would be rejected; ad-hoc channels
        // are the override - the list is every server->client SPacket id it registers (overshoot harmless).
        var curiosChannels = net.neoforged.neoforge.network.registration.ChannelAttributes
                .getOrCreateAdHocChannels(connection);
        for (var id : new String[]{
                "sync_data", "set_icons", "sync_curios", "sync_stack",
                "sync_modifiers", "sync_render", "sync_active_state",
                "page", "break", "grabbed_item", "quick_move"}) {
            curiosChannels.add(ResourceLocation.fromNamespaceAndPath("curios", id));
        }
        // ManasCore's login sync is bus-caught (non-fatal) but still prints a stack trace per
        // payload; whitelisting keeps the test log readable - the full cross-product of its payload
        // namespaces and sync fragments (scanned from the nested jars) covers every right pair.
        for (var ns : new String[]{"manascore_config", "manascore_storage",
                "manascore_world_storage", "manascore_registry_storage"}) {
            for (var frag : new String[]{"sync_config_to_client", "sync_chunk_storage",
                    "sync_entity_storage", "sync_world_storage"}) {
                curiosChannels.add(ResourceLocation.fromNamespaceAndPath(ns, frag));
            }
        }
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath("neoforge", "advanced_open_screen"));
        // IaF CE syncs NeoForge attachments to tracking players on every living tick and does
        // NOT catch a rejected send (#48's environment discovery): without this id, the first
        // living entity near a mock player crashes the whole suite once Ice and Fire is staged.
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath("neoforge", "sync_attachments"));
        // Tensura's gamerule pushes (integer and boolean variants) are
        // bus-caught but stack-trace per send; same readability treatment
        // as ManasCore above.
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath("tensura", "send_gamerule_update_integer"));
        curiosChannels.add(ResourceLocation.fromNamespaceAndPath("tensura", "send_gamerule_update_boolean"));
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
        // The #159 economy charges captures from the player's pools; suite-wide
        // huge pools keep the cost out of these tests' way (one-way pin, never
        // restored - the same rule the retired tamedMobsOnly pin followed).
        org.bitwisemadness.pseudocore.util.TensuraBridge.writePools(player, 1.0E9D, 1.0E9D);
        return player;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
