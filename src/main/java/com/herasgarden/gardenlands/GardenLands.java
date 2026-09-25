package com.herasgarden.gardenlands;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.claim.ClaimDirectoryService;
import com.herasgarden.gardencore.api.claim.ClaimOwnershipBridge;
import com.herasgarden.gardencore.api.claim.ClaimTransferPolicy;
import com.herasgarden.gardencore.api.cosmetic.CosmeticProfileService;
import com.herasgarden.gardencore.api.membership.TerritoryMembershipProvider;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.LandAccessService;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyManagementService;
import com.herasgarden.gardencore.api.land.PropertyHabitabilityService;
import com.herasgarden.gardencore.api.organization.OrganizationDirectory;
import com.herasgarden.gardencore.api.permission.ClaimAccessPolicy;
import com.herasgarden.gardencore.api.permission.ContainerAccessPolicy;
import com.herasgarden.gardencore.api.permission.DoorAccessPolicy;
import com.herasgarden.gardenlands.acquisition.LandOfferCommand;
import com.herasgarden.gardenlands.api.LandsTerritoryDirectory;
import com.herasgarden.gardenlands.acquisition.LandPurchaseOfferService;
import com.herasgarden.gardenlands.bluemap.BlueMapIntegration;
import com.herasgarden.gardenlands.chat.GardenChatListener;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.ClaimOutlineCommand;
import com.herasgarden.gardenlands.claim.ClaimStickOutlineListener;
import com.herasgarden.gardenlands.claim.LandsAccessService;
import com.herasgarden.gardenlands.claim.LandsClaimDirectoryService;
import com.herasgarden.gardenlands.container.ContainerCleanupListener;
import com.herasgarden.gardenlands.container.ContainerCommand;
import com.herasgarden.gardenlands.container.ContainerMobAccessListener;
import com.herasgarden.gardenlands.container.ContainerPermissionService;
import com.herasgarden.gardenlands.door.DoorCleanupListener;
import com.herasgarden.gardenlands.door.DoorCommand;
import com.herasgarden.gardenlands.door.DoorPermissionService;
import com.herasgarden.gardenlands.interaction.AccessSettingsGui;
import com.herasgarden.gardenlands.interaction.AccessSettingsListener;
import com.herasgarden.gardenlands.home.HomeCommand;
import com.herasgarden.gardenlands.property.LandsPropertyDirectory;
import com.herasgarden.gardenlands.property.PropertyCommand;
import com.herasgarden.gardenlands.property.PropertySignListener;
import com.herasgarden.gardenlands.rental.RentalCommand;
import com.herasgarden.gardenlands.rental.RentalService;
import com.herasgarden.gardenlands.property.LandsPropertyHabitabilityService;
import com.herasgarden.gardenlands.storage.LandsSchema;
import com.herasgarden.gardenlands.territory.TerritoryCommand;
import com.herasgarden.gardenlands.territory.TerritoryDirectory;
import com.herasgarden.gardenlands.territory.TerritoryGlyphService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class GardenLands extends JavaPlugin {
    private GardenPlatform platform;
    private TerritoryDirectory territoryDirectory;
    private TerritoryGlyphService glyphService;
    private ClaimDirectory claimDirectory;
    private ContainerPermissionService containerPermissions;
    private DoorPermissionService doorPermissions;
    private LandPurchaseOfferService landOffers;
    private PropertyDirectory propertyDirectory;
    private PropertyManagementService propertyManagement;
    private OrganizationDirectory organizations;
    private GardenTerritoryDirectory territoryApi;
    private RentalService rentalService;
    private BlueMapIntegration blueMapIntegration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<GardenPlatform> registration =
                getServer().getServicesManager().getRegistration(GardenPlatform.class);
        if (registration == null || registration.getProvider() == null) {
            getLogger().severe("GardenCore platform service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        RegisteredServiceProvider<PropertyManagementService> propertyManagementRegistration =
                getServer().getServicesManager().getRegistration(PropertyManagementService.class);
        RegisteredServiceProvider<OrganizationDirectory> organizationRegistration =
                getServer().getServicesManager().getRegistration(OrganizationDirectory.class);
        RegisteredServiceProvider<ClaimOwnershipBridge> ownershipRegistration =
                getServer().getServicesManager().getRegistration(ClaimOwnershipBridge.class);
        RegisteredServiceProvider<CosmeticProfileService> cosmeticRegistration =
                getServer().getServicesManager().getRegistration(CosmeticProfileService.class);
        if (ownershipRegistration == null || ownershipRegistration.getProvider() == null) {
            getLogger().severe("GardenCore claim ownership bridge is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (propertyManagementRegistration == null || propertyManagementRegistration.getProvider() == null) {
            getLogger().severe("GardenCore transitional property management service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (organizationRegistration == null || organizationRegistration.getProvider() == null) {
            getLogger().severe("GardenCore organization service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        platform = registration.getProvider();
        propertyManagement = propertyManagementRegistration.getProvider();
        organizations = organizationRegistration.getProvider();
        try {
            LandsSchema.ensure(platform.storage());

            territoryDirectory = new TerritoryDirectory(platform.storage());
            territoryDirectory.refresh();
            glyphService = new TerritoryGlyphService(this, platform.storage(), territoryDirectory);
            glyphService.refresh();

            claimDirectory = new ClaimDirectory(platform.storage());
            claimDirectory.refresh();
            containerPermissions = new ContainerPermissionService(platform.storage(), claimDirectory);
            containerPermissions.refresh();
            doorPermissions = new DoorPermissionService(platform.storage(), claimDirectory);
            doorPermissions.refresh();
            landOffers = new LandPurchaseOfferService(platform, claimDirectory, ownershipRegistration.getProvider());
            propertyDirectory = new LandsPropertyDirectory(platform.storage());
            territoryApi = new LandsTerritoryDirectory(territoryDirectory, claimDirectory);
            rentalService = new RentalService(platform, claimDirectory, propertyDirectory);
            rentalService.refresh();
        } catch (SQLException | RuntimeException exception) {
            getLogger().severe("GardenLands could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getServicesManager().register(
                ContainerAccessPolicy.class, containerPermissions, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                DoorAccessPolicy.class, doorPermissions, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                LandAccessService.class, new LandsAccessService(claimDirectory), this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                ClaimDirectoryService.class, new LandsClaimDirectoryService(claimDirectory), this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                PropertyDirectory.class, propertyDirectory, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                GardenTerritoryDirectory.class, territoryApi, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                ClaimAccessPolicy.class, rentalService, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                ClaimTransferPolicy.class, rentalService, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                PropertyHabitabilityService.class,
                new LandsPropertyHabitabilityService(claimDirectory, rentalService),
                this,
                ServicePriority.Normal);

        TerritoryCommand territoryCommand = new TerritoryCommand(territoryDirectory, this::membershipProvider);
        PluginCommand territory = getCommand("territory");
        if (territory != null) {
            territory.setExecutor(territoryCommand);
            territory.setTabCompleter(territoryCommand);
        }

        ContainerCommand containerCommand = new ContainerCommand(containerPermissions, claimDirectory);
        PluginCommand chest = getCommand("chest");
        if (chest != null) {
            chest.setExecutor(containerCommand);
            chest.setTabCompleter(containerCommand);
        }

        DoorCommand doorCommand = new DoorCommand(doorPermissions, claimDirectory);
        PluginCommand door = getCommand("door");
        if (door != null) {
            door.setExecutor(doorCommand);
            door.setTabCompleter(doorCommand);
        }

        LandOfferCommand landCommand = new LandOfferCommand(landOffers, claimDirectory, platform.currency().symbol());
        PluginCommand land = getCommand("land");
        if (land != null) {
            land.setExecutor(landCommand);
            land.setTabCompleter(landCommand);
        }

        PropertyCommand propertyCommand = new PropertyCommand(
                propertyDirectory, propertyManagement, claimDirectory, organizations, containerPermissions);
        getServer().getPluginManager().registerEvents(
                new PropertySignListener(
                        this, propertyManagement, claimDirectory, organizations, containerPermissions),
                this);
        PluginCommand property = getCommand("property");
        if (property != null) {
            property.setExecutor(propertyCommand);
            property.setTabCompleter(propertyCommand);
        }

        HomeCommand homeCommand = new HomeCommand(propertyDirectory, propertyManagement, claimDirectory);
        PluginCommand home = getCommand("home");
        if (home != null) {
            home.setExecutor(homeCommand);
            home.setTabCompleter(homeCommand);
        }

        RentalCommand rentalCommand = new RentalCommand(
                rentalService, claimDirectory, platform.currency().symbol());
        PluginCommand rent = getCommand("rent");
        if (rent != null) {
            rent.setExecutor(rentalCommand);
            rent.setTabCompleter(rentalCommand);
        }

        ClaimOutlineCommand outlineCommand = new ClaimOutlineCommand(this, claimDirectory, territoryDirectory);
        PluginCommand outline = getCommand("outline");
        if (outline != null) {
            outline.setExecutor(outlineCommand);
            outline.setTabCompleter(outlineCommand);
        }
        getServer().getPluginManager().registerEvents(new ClaimStickOutlineListener(outlineCommand), this);

        if (getConfig().getBoolean("chat.enabled", true)) {
            CosmeticProfileService cosmeticProfiles = cosmeticRegistration == null
                    ? null : cosmeticRegistration.getProvider();
            getServer().getPluginManager().registerEvents(
                    new GardenChatListener(
                            this::membershipProvider,
                            glyphService,
                            cosmeticProfiles,
                            getConfig().getString("chat.separator", ">>"),
                            getConfig().getString("chat.default-name-color", "#E7E3E5"),
                            getConfig().getString("chat.admin-name-color", "#A78BFA"),
                            getConfig().getString("chat.owner-name", "heraclidmc"),
                            getConfig().getString("chat.owner-name-color", "#F472B6"),
                            getConfig().getString("chat.donor-tag-color", "#D4AF37")
                    ),
                    this);
        }
        getServer().getPluginManager().registerEvents(new ContainerCleanupListener(containerPermissions), this);
        getServer().getPluginManager().registerEvents(new ContainerMobAccessListener(containerPermissions), this);
        getServer().getPluginManager().registerEvents(new DoorCleanupListener(doorPermissions), this);
        AccessSettingsGui accessSettingsGui =
                new AccessSettingsGui(claimDirectory, containerPermissions, doorPermissions);
        getServer().getPluginManager().registerEvents(accessSettingsGui, this);
        getServer().getPluginManager().registerEvents(
                new AccessSettingsListener(claimDirectory, accessSettingsGui), this);

        if (getConfig().getBoolean("bluemap.enabled", true)
                && getServer().getPluginManager().isPluginEnabled("BlueMap")) {
            blueMapIntegration = new BlueMapIntegration(
                    this, claimDirectory, territoryDirectory, propertyDirectory, platform.currency().symbol());
            blueMapIntegration.start();
            long blueMapRefreshTicks = Math.max(200L, getConfig().getLong("bluemap.refresh-ticks", 1200L));
            getServer().getScheduler().runTaskTimer(
                    this, blueMapIntegration::requestRefresh, blueMapRefreshTicks, blueMapRefreshTicks);
        }

        long refreshTicks = Math.max(200L, getConfig().getLong("territories.cache-refresh-ticks", 1200L));
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                territoryDirectory.refresh();
                glyphService.refresh();
                claimDirectory.refresh();
                containerPermissions.refresh();
                doorPermissions.refresh();
                rentalService.refresh();
            } catch (SQLException | RuntimeException exception) {
                getLogger().warning("Could not refresh GardenLands caches: " + exception.getMessage());
            }
        }, refreshTicks, refreshTicks);

        getLogger().info("GardenLands enabled. Land, property commands, rentals, acquisitions, BlueMap, and access settings are active.");
    }

    @Override
    public void onDisable() {
        if (blueMapIntegration != null) {
            blueMapIntegration.stop();
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    public GardenPlatform platform() {
        return platform;
    }

    public TerritoryDirectory territories() {
        return territoryDirectory;
    }

    public TerritoryMembershipProvider memberships() {
        return membershipProvider();
    }

    private TerritoryMembershipProvider membershipProvider() {
        RegisteredServiceProvider<TerritoryMembershipProvider> registration =
                getServer().getServicesManager().getRegistration(TerritoryMembershipProvider.class);
        return registration == null ? null : registration.getProvider();
    }

    public TerritoryGlyphService glyphs() {
        return glyphService;
    }

    public ClaimDirectory claims() {
        return claimDirectory;
    }

    public PropertyDirectory properties() {
        return propertyDirectory;
    }

    public RentalService rentals() {
        return rentalService;
    }
}
