package com.herasgarden.gardenlands.bluemap;

import com.flowpowered.math.vector.Vector2d;
import com.herasgarden.gardencore.api.land.PropertyAddress;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.territory.TerritoryDirectory;
import com.herasgarden.gardenlands.territory.TerritoryRecord;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Optional BlueMap bridge. This class is only instantiated when BlueMap is
 * present so GardenLands can still run without the optional dependency.
 */
public final class BlueMapIntegration {
    private static final String TERRITORY_SET_ID = "garden-territories";
    private static final String PROPERTY_SET_ID = "garden-properties-for-sale";
    private static final String CITY_SET_ID = "garden-cities";
    private static final String DISTRICT_SET_ID = "garden-districts";

    private final JavaPlugin plugin;
    private final ClaimDirectory claims;
    private final TerritoryDirectory territories;
    private final PropertyDirectory properties;
    private final String currencySymbol;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Consumer<BlueMapAPI> enableListener = ignored -> requestRefresh();

    public BlueMapIntegration(JavaPlugin plugin, ClaimDirectory claims, TerritoryDirectory territories,
                              PropertyDirectory properties, String currencySymbol) {
        this.plugin = plugin;
        this.claims = claims;
        this.territories = territories;
        this.properties = properties;
        this.currencySymbol = currencySymbol == null || currencySymbol.isBlank() ? "⟡" : currencySymbol;
    }

    public void start() {
        BlueMapAPI.onEnable(enableListener);
        requestRefresh();
    }

    public void stop() {
        BlueMapAPI.unregisterListener(enableListener);
        BlueMapAPI.getInstance().ifPresent(this::clearAllMarkers);
    }

    public void requestRefresh() {
        if (!plugin.isEnabled() || !refreshQueued.compareAndSet(false, true)) {
            return;
        }

        Runnable task = () -> {
            refreshQueued.set(false);
            if (!plugin.isEnabled()) return;
            BlueMapAPI.getInstance().ifPresent(this::refreshAll);
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void refreshAll(BlueMapAPI api) {
        refreshTerritories(api);
        refreshAdministrativeMarkers(api);
        refreshProperties(api);
    }

    private void clearAllMarkers(BlueMapAPI api) {
        for (BlueMapMap map : api.getMaps()) {
            map.getMarkerSets().remove(TERRITORY_SET_ID);
            map.getMarkerSets().remove(PROPERTY_SET_ID);
            map.getMarkerSets().remove(CITY_SET_ID);
            map.getMarkerSets().remove(DISTRICT_SET_ID);
        }
    }

    private void refreshTerritories(BlueMapAPI api) {
        clearTerritoryMarkers(api);

        MarkerSet markerSet = MarkerSet.builder()
                .label(plugin.getConfig().getString("bluemap.territory-layer-label", "Territories"))
                .toggleable(true)
                .defaultHidden(false)
                .sorting(10)
                .build();

        float shapeY = (float) plugin.getConfig().getDouble("bluemap.territory-shape-y", 64.0);
        int markers = 0;

        for (TerritoryRecord territory : territories.list()) {
            LandClaimRecord claim = claims.find(territory.claimId()).orElse(null);
            if (claim == null || claim.vertices().size() < 2) {
                continue;
            }

            World world = Bukkit.getWorld(claim.worldId());
            if (world == null) {
                continue;
            }

            Shape shape = shape(claim.vertices());
            ShapeMarker marker = ShapeMarker.builder()
                    .label(territory.name())
                    .detail("<b>" + html(territory.name()) + "</b><br>Territory<br>Area: "
                            + Math.round(claim.area()) + " blocks²")
                    .shape(shape, shapeY)
                    .centerPosition()
                    .lineWidth(3)
                    .lineColor(new Color(163, 181, 101, 0.95f))
                    .fillColor(new Color(163, 181, 101, 0.22f))
                    .build();

            boolean added = false;
            var blueWorld = api.getWorld(world);
            if (blueWorld.isPresent()) {
                for (BlueMapMap map : blueWorld.get().getMaps()) {
                    MarkerSet set = map.getMarkerSets().computeIfAbsent(TERRITORY_SET_ID, ignored -> markerSet);
                    set.getMarkers().put("territory-" + territory.claimId(), marker);
                    added = true;
                }
            }
            if (added) markers++;
        }

        plugin.getLogger().fine("Refreshed " + markers + " GardenLands territory marker(s) in BlueMap.");
    }

    private void clearTerritoryMarkers(BlueMapAPI api) {
        for (BlueMapMap map : api.getMaps()) {
            map.getMarkerSets().remove(TERRITORY_SET_ID);
        }
    }

    private void refreshAdministrativeMarkers(BlueMapAPI api) {
        for (BlueMapMap map : api.getMaps()) {
            map.getMarkerSets().remove(CITY_SET_ID);
            map.getMarkerSets().remove(DISTRICT_SET_ID);
        }

        MarkerSet cities = MarkerSet.builder()
                .label(plugin.getConfig().getString("bluemap.city-layer-label", "Cities"))
                .toggleable(true)
                .defaultHidden(false)
                .sorting(15)
                .build();
        MarkerSet districts = MarkerSet.builder()
                .label(plugin.getConfig().getString("bluemap.district-layer-label", "Districts"))
                .toggleable(true)
                .defaultHidden(true)
                .sorting(16)
                .build();

        double markerY = plugin.getConfig().getDouble("bluemap.administrative-marker-y", 72.0);

        for (LandClaimRecord claim : claims.all()) {
            boolean city = "CITY".equalsIgnoreCase(claim.type());
            boolean district = "DISTRICT".equalsIgnoreCase(claim.type());
            if ((!city && !district) || claim.name() == null || claim.name().isBlank()
                    || claim.vertices().isEmpty()) {
                continue;
            }

            World world = Bukkit.getWorld(claim.worldId());
            if (world == null) continue;

            double[] center = center(claim.vertices());
            String parent = claims.find(claim.parentId())
                    .map(LandClaimRecord::name)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(null);
            String type = city ? "City" : "District";
            String detail = "<b>" + html(claim.name()) + "</b><br>" + type
                    + (parent == null ? "" : "<br>Within: " + html(parent));

            POIMarker marker = POIMarker.builder()
                    .label(claim.name())
                    .detail(detail)
                    .position(center[0], markerY, center[1])
                    .defaultIcon()
                    .build();

            var blueWorld = api.getWorld(world);
            if (blueWorld.isEmpty()) continue;
            for (BlueMapMap map : blueWorld.get().getMaps()) {
                String setId = city ? CITY_SET_ID : DISTRICT_SET_ID;
                MarkerSet template = city ? cities : districts;
                MarkerSet set = map.getMarkerSets().computeIfAbsent(setId, ignored -> template);
                set.getMarkers().put((city ? "city-" : "district-") + claim.id(), marker);
            }
        }
    }

    private void refreshProperties(BlueMapAPI api) {
        for (BlueMapMap map : api.getMaps()) {
            map.getMarkerSets().remove(PROPERTY_SET_ID);
        }

        MarkerSet markerSet = MarkerSet.builder()
                .label(plugin.getConfig().getString("bluemap.property-layer-label", "Properties for Sale"))
                .toggleable(true)
                .defaultHidden(true)
                .sorting(20)
                .build();

        double markerY = plugin.getConfig().getDouble("bluemap.property-marker-y", 70.0);
        int markers = 0;

        try {
            for (PropertyAddress property : properties.listedForSale()) {
                LandClaimRecord claim = claims.find(property.claimId()).orElse(null);
                if (claim == null || claim.vertices().isEmpty()) {
                    continue;
                }

                World world = Bukkit.getWorld(claim.worldId());
                if (world == null) {
                    continue;
                }

                double[] center = center(claim.vertices());
                POIMarker marker = POIMarker.builder()
                        .label(property.display())
                        .detail("<b>" + html(property.display()) + "</b><br>For sale: "
                                + html(currencySymbol) + " " + property.price())
                        .position(center[0], markerY, center[1])
                        .defaultIcon()
                        .build();

                boolean added = false;
                var blueWorld = api.getWorld(world);
                if (blueWorld.isPresent()) {
                    for (BlueMapMap map : blueWorld.get().getMaps()) {
                        MarkerSet set = map.getMarkerSets()
                                .computeIfAbsent(PROPERTY_SET_ID, ignored -> markerSet);
                        set.getMarkers().put("property-" + property.propertyId(), marker);
                        added = true;
                    }
                }
                if (added) markers++;
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not refresh BlueMap property markers: " + exception.getMessage());
            return;
        }

        plugin.getLogger().fine("Refreshed " + markers + " GardenLands property-for-sale marker(s) in BlueMap.");
    }

    private double[] center(List<LandClaimRecord.Point> vertices) {
        if (vertices.size() == 2) {
            LandClaimRecord.Point a = vertices.get(0);
            LandClaimRecord.Point b = vertices.get(1);
            return new double[]{(a.x() + b.x()) / 2.0, (a.z() + b.z()) / 2.0};
        }

        double x = 0.0;
        double z = 0.0;
        for (LandClaimRecord.Point point : vertices) {
            x += point.x();
            z += point.z();
        }
        return new double[]{x / vertices.size(), z / vertices.size()};
    }

    private Shape shape(List<LandClaimRecord.Point> vertices) {
        List<Vector2d> points = new ArrayList<>();
        if (vertices.size() == 2) {
            LandClaimRecord.Point a = vertices.get(0);
            LandClaimRecord.Point b = vertices.get(1);
            int minX = Math.min(a.x(), b.x());
            int maxX = Math.max(a.x(), b.x()) + 1;
            int minZ = Math.min(a.z(), b.z());
            int maxZ = Math.max(a.z(), b.z()) + 1;
            points.add(new Vector2d(minX, minZ));
            points.add(new Vector2d(maxX, minZ));
            points.add(new Vector2d(maxX, maxZ));
            points.add(new Vector2d(minX, maxZ));
        } else {
            for (LandClaimRecord.Point point : vertices) {
                points.add(new Vector2d(point.x(), point.z()));
            }
        }
        return new Shape(points);
    }

    private String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
