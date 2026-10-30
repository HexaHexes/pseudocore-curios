package org.bitwisemadness.pseudocore.curios.client;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.net.UnreachableRecallS2C;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointWorld;
import xaero.common.minimap.waypoints.WaypointsManager;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;

/**
 * The only class that imports Xaero's Minimap (#13): drops a waypoint at
 * the mob's last-known position when a recall comes back unreachable.
 *
 * <p>Reached only from {@link UnreachableRecallS2C}'s client handler and
 * only after {@code ModList} reports {@code xaerominimap} - Xaero's mods
 * are client-only and optional, so nothing here ever loads on a server or
 * on a client without them. The whole body is wrapped in a {@link Throwable}
 * catch: a Xaero version moving under us must degrade to "no waypoint",
 * never to a broken payload handler.</p>
 *
 * <p>The mechanism replicates what Xaero's own deathpoint does, read out of
 * the 9067173 bytecode: resolve this world's waypoint world (creating the
 * per-dimension entry when Xaero has not seen this dimension yet), scale the
 * coordinates by the dimension division the same way (Xaero stores
 * waypoints in its own per-dimension scale), skip when a waypoint with the
 * same name and position already exists in any set of that world (so
 * hammering the key cannot spam the list), then add to the current set and
 * save the world through Xaero's own IO - the same add-and-save sequence
 * the deathpoint path runs.</p>
 */
public final class ClientWaypoint {

    private ClientWaypoint() {
    }

    /** Drop the waypoint for one unreachable recall; silently a no-op without Xaero. */
    public static void mark(UnreachableRecallS2C payload) {
        if (!ModList.get().isLoaded("xaerominimap")) {
            return;
        }
        try {
            var session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (!(session instanceof WaypointsManager manager)) {
                // No live session yet (title screen / between worlds).
                return;
            }

            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(payload.dimension()));
            String container = manager.getCurrentContainerID();
            String worldId = manager.getDimensionDirectoryName(dimension);
            if (container == null || worldId == null) {
                return;
            }
            WaypointWorld world = manager.getWorld(container, worldId);
            if (world == null) {
                // Xaero has no entry for this dimension yet - its own
                // addWorld builds one the way the deathpoint flow would.
                world = manager.addWorld(container, worldId);
            }
            if (world == null) {
                return;
            }

            WaypointSet set = world.getCurrentWaypointSet();
            if (set == null) {
                world.addWaypointSet("main");
                set = world.getCurrentWaypointSet();
            }
            if (set == null) {
                return;
            }

            double division = session.getDimensionHelper().getDimensionDivision(world);
            int waypointX = (int) Math.floor(Math.floor(payload.x()) * division);
            int waypointZ = (int) Math.floor(Math.floor(payload.z()) * division);

            for (WaypointSet other : world.getIterableWaypointSets()) {
                for (Waypoint existing : other.getWaypoints()) {
                    if (existing.getX() == waypointX && existing.getY() == payload.y()
                            && existing.getZ() == waypointZ && payload.name().equals(existing.getName())) {
                        return; // already marked - no spam on repeated presses
                    }
                }
            }

            Waypoint waypoint = new Waypoint(waypointX, payload.y(), waypointZ,
                    payload.name(), "", WaypointColor.RED, WaypointPurpose.NORMAL);
            set.add(waypoint, true);
            manager.getWorldManagerIO().saveWorld(world);
        } catch (Throwable xaeroNoise) {
            PseudocoreCurios.LOGGER.warn(
                    "Could not drop the recall waypoint at {} in {}: {}",
                    payload.name(), payload.dimension(), xaeroNoise.toString());
        }
    }
}
