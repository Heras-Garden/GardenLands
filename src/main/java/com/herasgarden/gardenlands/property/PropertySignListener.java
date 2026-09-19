package com.herasgarden.gardenlands.property;

import com.herasgarden.gardencore.api.land.PropertyAddress;
import com.herasgarden.gardencore.api.land.PropertyBlockPosition;
import com.herasgarden.gardencore.api.land.PropertyManagementService;
import com.herasgarden.gardencore.api.land.PropertySignBinding;
import com.herasgarden.gardencore.api.land.PropertySignKind;
import com.herasgarden.gardencore.api.organization.OrganizationDirectory;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.container.ContainerKey;
import com.herasgarden.gardenlands.container.ContainerPermissionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;

/**
 * GardenLands-owned property sign and mailbox behavior.
 *
 * Durable sign/mailbox rows still use the transitional PropertyManagementService
 * while persistence is extracted from GardenCore.
 */
public final class PropertySignListener implements Listener {
    private static final TextColor TEXT = TextColor.color(0xE7E3E5);
    private static final TextColor MUTED = TextColor.color(0xB9B4B6);
    private static final TextColor POSITIVE = TextColor.color(0xA8B79F);
    private static final TextColor NEGATIVE = TextColor.color(0xC5A3A3);

    private final JavaPlugin plugin;
    private final PropertyManagementService properties;
    private final ClaimDirectory claims;
    private final OrganizationDirectory organizations;
    private final ContainerPermissionService containers;

    public PropertySignListener(
            JavaPlugin plugin,
            PropertyManagementService properties,
            ClaimDirectory claims,
            OrganizationDirectory organizations,
            ContainerPermissionService containers
    ) {
        this.plugin = plugin;
        this.properties = properties;
        this.claims = claims;
        this.organizations = organizations;
        this.containers = containers;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Block signBlock = event.getBlock();
        PropertyBlockPosition position = PropertyBlockPosition.from(signBlock.getLocation());
        PropertySignBinding existingLink = properties.signAt(position).orElse(null);

        if (existingLink != null) {
            event.setCancelled(true);
            PropertyAddress existing = properties.find(existingLink.propertyId()).orElse(null);
            if (existing != null) {
                LandClaimRecord claim = claims.find(existing.claimId()).orElse(null);
                if (claim != null && canManage(player, claim)) {
                    GardenMessages.send(player,
                            "This is a linked property sign. Sneak-right-click it to edit the property.");
                } else {
                    GardenMessages.send(player, "You do not manage this property sign.");
                }
                Bukkit.getScheduler().runTask(plugin, () -> properties.refreshSigns(existing.propertyId()));
            }
            return;
        }

        try {
            if (tryHandleSimpleApartmentSign(event, player, signBlock)) {
                return;
            }
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
            return;
        } catch (SQLException exception) {
            plugin.getLogger().severe(
                    "Could not create/bind apartment sign at "
                            + signBlock.getLocation() + ": " + exception.getMessage());
            GardenMessages.send(player, "That apartment sign could not be saved.");
            return;
        }

        String road = safe(event.getLine(0));
        String lineTwo = safe(event.getLine(1));
        String priceText = safe(event.getLine(2));
        if (road.isBlank() || lineTwo.isBlank()) {
            return;
        }

        Block support = attachedSupport(signBlock);
        boolean onChest = support != null && ContainerKey.supported(support);
        PropertyAddress property = properties.findByDisplayAddress(road, lineTwo).orElse(null);
        LandClaimRecord claim = property == null ? null : claims.find(property.claimId()).orElse(null);
        boolean apartmentMailboxCandidate = property != null && claim != null
                && isType(claim, "APARTMENT") && onChest;

        Long price = apartmentMailboxCandidate ? property.price() : parsePrice(priceText);
        if (!apartmentMailboxCandidate && price == null) {
            GardenMessages.send(player,
                    "Put the Obol price as a whole number on line 3, for example 500.");
            return;
        }

        if (property != null && (claim == null || !canManage(player, claim))) {
            GardenMessages.send(player, "You do not manage this property.");
            return;
        }

        PropertySignKind kind;
        boolean created = false;
        try {
            if (property == null) {
                LandClaimRecord anchorClaim = onChest
                        ? claims.findAt(support).orElse(null)
                        : claimTouchingSign(signBlock);
                if (anchorClaim == null || !canManage(player, anchorClaim)) {
                    GardenMessages.send(player,
                            "The sign must be attached to this property's wall or mailbox container.");
                    return;
                }
                if (isType(anchorClaim, "TERRITORY")) {
                    GardenMessages.send(player,
                            "Create a property claim inside the territory before placing its property sign.");
                    return;
                }

                if (isType(anchorClaim, "APARTMENT")) {
                    if (onChest) {
                        GardenMessages.send(player,
                                "Place the apartment room sign first. Then place the matching sign on the mailbox container.");
                        return;
                    }
                    if (support == null || !isWallSign(signBlock)) {
                        GardenMessages.send(player,
                                "The apartment room sign must be attached to a wall touching the apartment claim.");
                        return;
                    }
                    AddressParts parts = parseApartmentLineTwo(lineTwo);
                    if (parts == null) {
                        GardenMessages.send(player,
                                "For an apartment, line 2 must contain the street number and unit, for example: 119 5A.");
                        return;
                    }
                    property = properties.register(anchorClaim.id(), road, parts.number(), parts.unit());
                    claim = anchorClaim;
                    kind = PropertySignKind.APARTMENT_UNIT;
                    created = true;
                } else {
                    if (!onChest) {
                        GardenMessages.send(player,
                                "This property's sign must be placed on the front of its mailbox container.");
                        return;
                    }
                    if (!isFrontOfChest(signBlock, support)) {
                        GardenMessages.send(player, "Place the sign on the front face of the mailbox container.");
                        return;
                    }
                    if (!anchorClaim.contains(support.getX(), support.getY(), support.getZ())) {
                        GardenMessages.send(player, "The mailbox container must be inside the property claim.");
                        return;
                    }
                    property = properties.register(anchorClaim.id(), road, lineTwo, null);
                    claim = anchorClaim;
                    kind = PropertySignKind.PROPERTY_MAILBOX;
                    created = true;
                }
            } else if (isType(claim, "APARTMENT")) {
                if (onChest) {
                    if (!isFrontOfChest(signBlock, support)) {
                        GardenMessages.send(player, "Place the mailbox sign on the front face of the mailbox container.");
                        return;
                    }
                    if (!mailboxAllowedForApartment(support, claim)) {
                        GardenMessages.send(player,
                                "The apartment mailbox container must be inside the apartment or one of its parent building claims.");
                        return;
                    }
                    kind = PropertySignKind.APARTMENT_MAILBOX;
                } else {
                    if (support == null || !isWallSign(signBlock) || !signTouchesClaim(signBlock, claim)) {
                        GardenMessages.send(player,
                                "The apartment room sign must touch the apartment wall, even if the sign itself is outside the claim.");
                        return;
                    }
                    kind = PropertySignKind.APARTMENT_UNIT;
                }
            } else {
                if (!onChest || !isFrontOfChest(signBlock, support)) {
                    GardenMessages.send(player,
                            "This property's sign must be on the front of its mailbox container.");
                    return;
                }
                if (!claim.contains(support.getX(), support.getY(), support.getZ())) {
                    GardenMessages.send(player, "The mailbox container must be inside the property claim.");
                    return;
                }
                kind = PropertySignKind.PROPERTY_MAILBOX;
            }

            if (kind == PropertySignKind.PROPERTY_MAILBOX
                    || kind == PropertySignKind.APARTMENT_MAILBOX) {
                if (!ContainerKey.single(support)) {
                    GardenMessages.send(player,
                            "A registered mailbox must be a single container and cannot be a double chest.");
                    return;
                }
                PropertyAddress mailboxOwner = properties
                        .propertyForMailbox(PropertyBlockPosition.from(support.getLocation()))
                        .orElse(null);
                if (mailboxOwner != null && !mailboxOwner.propertyId().equals(property.propertyId())) {
                    GardenMessages.send(player,
                            "That chest is already the mailbox for " + mailboxOwner.display() + ".");
                    return;
                }
            }

            if (kind != PropertySignKind.APARTMENT_MAILBOX) {
                property = properties.setForSale(property.propertyId(), price);
            }
            properties.bindSign(property.propertyId(), position, player.getUniqueId(), kind);

            try {
                if (kind == PropertySignKind.PROPERTY_MAILBOX
                        || kind == PropertySignKind.APARTMENT_MAILBOX) {
                    properties.setMailbox(
                            property.propertyId(),
                            PropertyBlockPosition.from(support.getLocation())
                    );
                    containers.noteMailbox(support, claim.id());
                }
            } catch (SQLException mailboxFailure) {
                try {
                    properties.removeSign(position);
                } catch (SQLException ignored) {
                }
                throw mailboxFailure;
            }

            applyEventText(event, property, claim, kind);

            PropertyAddress finalProperty = property;
            PropertySignKind finalKind = kind;
            boolean finalCreated = created;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalKind == PropertySignKind.APARTMENT_UNIT) {
                    GardenMessages.send(player,
                            (finalCreated ? "Apartment room sign created: " : "Apartment room sign linked: ")
                                    + finalProperty.display() + " for ⟡ " + finalProperty.price() + ".");
                    if (!properties.apartmentSetupComplete(finalProperty.propertyId())) {
                        GardenMessages.send(player,
                                "Now place the matching sign on the front of the apartment's mailbox container.");
                    }
                } else if (finalKind == PropertySignKind.APARTMENT_MAILBOX) {
                    GardenMessages.send(player,
                            "Apartment mailbox linked for " + finalProperty.display() + ".");
                } else {
                    GardenMessages.send(player,
                            (finalCreated ? "Property created and mailbox linked: "
                                    : "Property mailbox sign linked: ")
                                    + finalProperty.display() + " for ⟡ " + finalProperty.price() + ".");
                }
            });
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().severe(
                    "Could not create/bind property sign at "
                            + signBlock.getLocation() + ": " + exception.getMessage());
            GardenMessages.send(player, "That property sign could not be saved.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMailboxExtension(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (!ContainerKey.supported(placed)) {
            return;
        }

        boolean wouldJoinMailbox = containers.isMailbox(placed)
                && ContainerKey.members(placed).size() > 1;

        org.bukkit.block.BlockFace[] horizontal = {
                org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST
        };
        for (org.bukkit.block.BlockFace face : horizontal) {
            Block neighbor = placed.getRelative(face);
            if (!ContainerKey.supported(neighbor) || !containers.isMailbox(neighbor)) {
                continue;
            }
            if (ContainerKey.members(placed).size() > 1
                    || ContainerKey.members(neighbor).size() > 1) {
                wouldJoinMailbox = true;
                break;
            }
        }

        if (!wouldJoinMailbox) {
            return;
        }

        event.setCancelled(true);
        GardenMessages.send(event.getPlayer(),
                "Mailboxes must stay single containers and cannot become double chests.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (!(event.getClickedBlock().getState() instanceof Sign)) {
            return;
        }

        PropertySignBinding link = properties
                .signAt(PropertyBlockPosition.from(event.getClickedBlock().getLocation()))
                .orElse(null);
        if (link == null) {
            return;
        }
        PropertyAddress property = properties.find(link.propertyId()).orElse(null);
        if (property == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        LandClaimRecord claim = claims.find(property.claimId()).orElse(null);

        if (claim != null && canManage(player, claim) && player.isSneaking()) {
            player.performCommand("property editid " + property.propertyId());
            return;
        }

        if (link.kind() == PropertySignKind.APARTMENT_MAILBOX) {
            GardenMessages.send(player,
                    "Mailbox for " + property.display() + " | " + ownerDisplay(claim) + ".");
            return;
        }

        if (!property.forSale()) {
            GardenMessages.send(player,
                    property.display() + " | " + ownerDisplay(claim) + ".");
            return;
        }

        if (claim != null
                && "PLAYER".equalsIgnoreCase(claim.ownerType())
                && claim.ownerId().equals(player.getUniqueId())) {
            GardenMessages.send(player, "You already own " + property.display() + ".");
            return;
        }

        if (claim != null
                && isType(claim, "APARTMENT")
                && !properties.apartmentSetupComplete(property.propertyId())) {
            GardenMessages.send(player,
                    "This apartment is not ready for purchase until its mailbox sign is linked.");
            return;
        }

        sendPurchasePrompt(player, property);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getState() instanceof Sign) {
            PropertySignBinding link = properties
                    .signAt(PropertyBlockPosition.from(block.getLocation()))
                    .orElse(null);
            if (link != null) {
                PropertyAddress property = properties.find(link.propertyId()).orElse(null);
                if (property != null) {
                    event.setCancelled(true);
                    GardenMessages.send(event.getPlayer(),
                            "This sign belongs to " + property.display()
                                    + ". Sneak-right-click it to edit the property, or delete the property to remove its signs.");
                }
                return;
            }
        }

        PropertyAddress mailboxProperty = properties
                .propertyForMailbox(PropertyBlockPosition.from(block.getLocation()))
                .orElse(null);
        if (mailboxProperty != null) {
            event.setCancelled(true);
            GardenMessages.send(event.getPlayer(),
                    "This chest is the registered mailbox for " + mailboxProperty.display()
                            + ". Delete the property before removing its mailbox container.");
            return;
        }

        PropertyAddress supported = propertyWhoseLinkedSignUses(block);
        if (supported != null) {
            event.setCancelled(true);
            GardenMessages.send(event.getPlayer(),
                    "That block supports the linked sign for " + supported.display()
                            + ". Delete the property before removing the sign or its support block.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectFromDestruction);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectFromDestruction);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (properties.signAt(PropertyBlockPosition.from(event.getBlock().getLocation())).isPresent()) {
            event.setCancelled(true);
        }
    }

    private boolean tryHandleSimpleApartmentSign(
            SignChangeEvent event,
            Player player,
            Block signBlock
    ) throws SQLException {
        String unit = safe(event.getLine(0));
        if (unit.isBlank()
                || !safe(event.getLine(1)).isBlank()
                || !safe(event.getLine(2)).isBlank()
                || !safe(event.getLine(3)).isBlank()) {
            return false;
        }

        Block support = attachedSupport(signBlock);
        boolean mailboxContainer = support != null && ContainerKey.supported(support);

        if (mailboxContainer) {
            LandClaimRecord locationClaim = claims.findAt(support).orElse(null);
            if (locationClaim == null) {
                return false;
            }
            PropertyAddress building = buildingAddressNear(locationClaim, support);
            if (building == null) {
                GardenMessages.send(player,
                        "This mailbox is not inside a registered building/property address.");
                return true;
            }
            PropertyAddress apartment = properties
                    .findByDisplayAddress(building.road(), building.number() + " " + unit)
                    .orElse(null);
            if (apartment == null) {
                return false;
            }
            LandClaimRecord apartmentClaim = claims.find(apartment.claimId()).orElse(null);
            if (apartmentClaim == null || !isType(apartmentClaim, "APARTMENT")) {
                return false;
            }
            if (!canManage(player, apartmentClaim)) {
                GardenMessages.send(player, "You do not manage this apartment.");
                return true;
            }
            if (!ContainerKey.single(support)) {
                GardenMessages.send(player,
                        "Apartment mailboxes must be a single chest, copper chest, or barrel.");
                return true;
            }
            if (!mailboxAllowedForApartment(support, apartmentClaim)) {
                GardenMessages.send(player,
                        "The apartment mailbox must be inside the apartment or one of its parent building claims.");
                return true;
            }

            PropertyAddress mailboxOwner = properties
                    .propertyForMailbox(PropertyBlockPosition.from(support.getLocation()))
                    .orElse(null);
            if (mailboxOwner != null && !mailboxOwner.propertyId().equals(apartment.propertyId())) {
                GardenMessages.send(player,
                        "That container is already the mailbox for " + mailboxOwner.display() + ".");
                return true;
            }

            properties.bindSign(
                    apartment.propertyId(),
                    PropertyBlockPosition.from(signBlock.getLocation()),
                    player.getUniqueId(),
                    PropertySignKind.APARTMENT_MAILBOX
            );
            properties.setMailbox(
                    apartment.propertyId(),
                    PropertyBlockPosition.from(support.getLocation())
            );
            containers.noteMailbox(support, apartmentClaim.id());
            applyEventText(event, apartment, apartmentClaim, PropertySignKind.APARTMENT_MAILBOX);

            Bukkit.getScheduler().runTask(plugin, () ->
                    GardenMessages.send(player,
                            "Apartment mailbox linked: " + apartment.display() + "."));
            return true;
        }

        LandClaimRecord apartmentClaim = apartmentClaimNear(signBlock);
        if (apartmentClaim == null) {
            return false;
        }
        if (!canManage(player, apartmentClaim)) {
            GardenMessages.send(player, "You do not manage this apartment.");
            return true;
        }

        PropertyAddress apartment = properties.findByClaim(apartmentClaim.id()).orElse(null);
        boolean created = false;
        if (apartment == null) {
            PropertyAddress building = buildingAddressNear(apartmentClaim, signBlock);
            if (building == null) {
                GardenMessages.send(player,
                        "Register the building/property address before setting up apartment " + unit + ".");
                return true;
            }
            apartment = properties.register(
                    apartmentClaim.id(),
                    building.road(),
                    building.number(),
                    unit
            );
            created = true;
        } else if (apartment.unitLabel() == null
                || !apartment.unitLabel().equalsIgnoreCase(unit)) {
            GardenMessages.send(player,
                    "This apartment is already registered as " + apartment.display() + ".");
            return true;
        }

        properties.bindSign(
                apartment.propertyId(),
                PropertyBlockPosition.from(signBlock.getLocation()),
                player.getUniqueId(),
                PropertySignKind.APARTMENT_UNIT
        );
        applyEventText(event, apartment, apartmentClaim, PropertySignKind.APARTMENT_UNIT);

        PropertyAddress finalApartment = apartment;
        boolean finalCreated = created;
        Bukkit.getScheduler().runTask(plugin, () -> {
            GardenMessages.send(player,
                    (finalCreated ? "Apartment registered: " : "Apartment room sign linked: ")
                            + finalApartment.display() + ".");
            if (!properties.apartmentSetupComplete(finalApartment.propertyId())) {
                GardenMessages.send(player,
                        "Now place a sign with only " + finalApartment.unitLabel()
                                + " on a single chest, copper chest, or barrel in the building mail area.");
            }
        });
        return true;
    }

    private PropertyAddress ancestorAddress(LandClaimRecord claim) {
        LandClaimRecord current = claim;
        java.util.HashSet<java.util.UUID> seen = new java.util.HashSet<>();
        while (current != null && seen.add(current.id())) {
            if (!isType(current, "APARTMENT")) {
                PropertyAddress address = properties.findByClaim(current.id()).orElse(null);
                if (address != null) {
                    return address;
                }
            }
            current = current.parentId() == null ? null : claims.find(current.parentId()).orElse(null);
        }
        return null;
    }

    private boolean protectFromDestruction(Block block) {
        if (properties.signAt(PropertyBlockPosition.from(block.getLocation())).isPresent()) {
            return true;
        }
        if (properties.propertyForMailbox(PropertyBlockPosition.from(block.getLocation())).isPresent()) {
            return true;
        }
        return propertyWhoseLinkedSignUses(block) != null;
    }

    private PropertyAddress propertyWhoseLinkedSignUses(Block support) {
        org.bukkit.block.BlockFace[] faces = {
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST,
                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN
        };
        for (org.bukkit.block.BlockFace face : faces) {
            Block nearby = support.getRelative(face);
            PropertySignBinding link = properties
                    .signAt(PropertyBlockPosition.from(nearby.getLocation()))
                    .orElse(null);
            if (link == null || !(nearby.getState() instanceof Sign)) {
                continue;
            }
            Block attached = attachedSupport(nearby);
            if (attached != null
                    && attached.getWorld().equals(support.getWorld())
                    && attached.getX() == support.getX()
                    && attached.getY() == support.getY()
                    && attached.getZ() == support.getZ()) {
                return properties.find(link.propertyId()).orElse(null);
            }
        }
        return null;
    }

    private LandClaimRecord claimTouchingSign(Block signBlock) {
        List<LandClaimRecord> nearby = nearbyClaims(signBlock);
        return nearby.stream()
                .min(java.util.Comparator.comparingDouble(LandClaimRecord::area))
                .orElse(null);
    }

    private LandClaimRecord apartmentClaimNear(Block signBlock) {
        return nearbyClaims(signBlock).stream()
                .filter(claim -> isType(claim, "APARTMENT"))
                .min(java.util.Comparator.comparingDouble(LandClaimRecord::area))
                .orElse(null);
    }

    private List<LandClaimRecord> nearbyClaims(Block block) {
        java.util.LinkedHashMap<java.util.UUID, LandClaimRecord> unique = new java.util.LinkedHashMap<>();
        claims.findAllAt(block).forEach(claim -> unique.put(claim.id(), claim));

        org.bukkit.block.BlockFace[] faces = {
                org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST,
                org.bukkit.block.BlockFace.UP,
                org.bukkit.block.BlockFace.DOWN
        };
        for (org.bukkit.block.BlockFace face : faces) {
            claims.findAllAt(block.getRelative(face))
                    .forEach(claim -> unique.put(claim.id(), claim));
        }
        return List.copyOf(unique.values());
    }

    private PropertyAddress buildingAddressNear(LandClaimRecord claim, Block reference) {
        PropertyAddress parentAddress = ancestorAddress(claim);
        if (parentAddress != null) {
            return parentAddress;
        }

        return nearbyClaims(reference).stream()
                .filter(candidate -> !isType(candidate, "APARTMENT"))
                .sorted(java.util.Comparator.comparingDouble(LandClaimRecord::area))
                .map(candidate -> properties.findByClaim(candidate.id()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean signTouchesClaim(Block signBlock, LandClaimRecord claim) {
        if (claim.contains(signBlock.getX(), signBlock.getY(), signBlock.getZ())) {
            return true;
        }
        Block support = attachedSupport(signBlock);
        return support != null && claim.contains(support.getX(), support.getY(), support.getZ());
    }

    private boolean mailboxAllowedForApartment(Block chest, LandClaimRecord apartmentClaim) {
        List<LandClaimRecord> atChest = claims.findAllAt(chest);
        if (atChest.stream().anyMatch(candidate -> claims.isSameOrAncestor(candidate, apartmentClaim))) {
            return true;
        }

        PropertyAddress building = buildingAddressNear(apartmentClaim, chest);
        if (building == null) {
            return false;
        }
        return atChest.stream().anyMatch(candidate -> candidate.id().equals(building.claimId()));
    }

    private Block attachedSupport(Block signBlock) {
        if (!isWallSign(signBlock) || !(signBlock.getBlockData() instanceof Directional directional)) {
            return null;
        }
        return signBlock.getRelative(directional.getFacing().getOppositeFace());
    }

    private boolean isWallSign(Block block) {
        String material = block.getType().name();
        return material.contains("_WALL_") && material.endsWith("_SIGN");
    }

    private boolean isFrontOfChest(Block signBlock, Block chestBlock) {
        if (!(signBlock.getBlockData() instanceof Directional signDirection)) {
            return false;
        }
        if (!(chestBlock.getBlockData() instanceof Directional chestDirection)) {
            return true;
        }
        return signDirection.getFacing() == chestDirection.getFacing();
    }

    private void applyEventText(
            SignChangeEvent event,
            PropertyAddress property,
            LandClaimRecord claim,
            PropertySignKind kind
    ) {
        String owner = ownerDisplay(claim);
        if (kind == PropertySignKind.APARTMENT_UNIT) {
            event.setLine(0, property.unitLabel() == null ? property.number() : property.unitLabel());
            event.setLine(1, "");
            event.setLine(2, "");
            event.setLine(3, "");
            return;
        }
        if (kind == PropertySignKind.APARTMENT_MAILBOX) {
            event.setLine(0, property.road());
            event.setLine(1, displayLineTwo(property));
            event.setLine(2, owner);
            event.setLine(3, "");
            return;
        }
        event.setLine(0, property.road());
        event.setLine(1, displayLineTwo(property));
        event.setLine(2, property.forSale() ? "⟡ " + property.price() : owner);
        event.setLine(3, "");
    }

    private void sendPurchasePrompt(Player player, PropertyAddress property) {
        player.sendActionBar(Component.text(
                "Property purchase | " + property.display() + " | ⟡ " + property.price(), MUTED));

        Component prompt = GardenMessages.prefix()
                .append(Component.text(
                        "Would you like to purchase " + property.display()
                                + " for ⟡ " + property.price() + "?",
                        TEXT))
                .append(Component.newline())
                .append(button(
                        "Yes",
                        "/property buy " + property.propertyId(),
                        POSITIVE,
                        "Purchase this property for ⟡ " + property.price() + "."))
                .append(Component.space())
                .append(button("No", "/property cancel", NEGATIVE, "Do not purchase this property."));
        player.sendMessage(prompt);
    }

    private Component button(String label, String command, TextColor color, String hover) {
        return Component.text("[" + label + "]", color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, MUTED)));
    }

    private boolean canManage(Player player, LandClaimRecord claim) {
        return player.hasPermission("gardenlands.property.admin") || claims.canManage(player, claim);
    }

    private String ownerDisplay(LandClaimRecord claim) {
        if (claim == null) {
            return "Unavailable";
        }
        if ("PLAYER".equalsIgnoreCase(claim.ownerType())) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(claim.ownerId());
            String name = player.getName();
            return name == null || name.isBlank()
                    ? claim.ownerId().toString().substring(0, 8)
                    : name;
        }
        return organizations.find(claim.ownerId())
                .map(value -> value.name())
                .orElse("COMPANY".equalsIgnoreCase(claim.ownerType()) ? "Company" : "Government");
    }

    private boolean isType(LandClaimRecord claim, String type) {
        if (claim == null || type == null) return false;
        if ("APARTMENT".equalsIgnoreCase(type)) {
            return claim.is("UNIT") && claim.tagged("APARTMENT");
        }
        if ("HOTEL_ROOM".equalsIgnoreCase(type)) {
            return claim.is("UNIT") && claim.tagged("HOTEL_ROOM");
        }
        if ("BUILDING".equalsIgnoreCase(type)) {
            return claim.is("PROPERTY") && (claim.tagged("BUILDING") || claim.tagged("APARTMENT_BUILDING")
                    || claim.tagged("HOTEL"));
        }
        return type.equalsIgnoreCase(claim.type());
    }

    private String displayLineTwo(PropertyAddress property) {
        return property.unitLabel() == null || property.unitLabel().isBlank()
                ? property.number()
                : property.number() + " " + property.unitLabel();
    }

    private AddressParts parseApartmentLineTwo(String value) {
        String[] parts = value.trim().split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new AddressParts(parts[0], parts[1]);
    }

    private Long parsePrice(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.replace("⟡", "").replace(",", "").trim();
        try {
            long parsed = Long.parseLong(cleaned);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record AddressParts(String number, String unit) {
    }
}
