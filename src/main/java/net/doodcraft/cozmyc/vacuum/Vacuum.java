package net.doodcraft.cozmyc.vacuum;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.ConfigManager;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Vacuum extends AirAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute("MaxWindCharges")
    private int maxWindCharges;
    @Attribute("SpawnWindow")
    private long spawnWindow;
    @Attribute(Attribute.RANGE)
    private double maxRange;

    private Location targetLocation;
    private Set<Material> harvestableBlocks;
    private Set<VacuumWindCharge> windChargeAbilities;
    private long startTime;
    private boolean canSpawnNew;

    public Vacuum(Player player) {
        super(player);

        if (!bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, Vacuum.class)) {
            return;
        }

        setFields();
        updateTargetLocation();
        spawnWindChargeAbility();
        start();
    }

    private void setFields() {
        this.cooldown = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.Cooldown");
        this.maxWindCharges = ConfigManager.getConfig().getInt("ExtraAbilities.Cozmyc.Vacuum.MaxWindCharges");
        this.spawnWindow = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.SpawnWindow");
        this.maxRange = ConfigManager.getConfig().getDouble("ExtraAbilities.Cozmyc.Vacuum.MaxRange");

        this.windChargeAbilities = ConcurrentHashMap.newKeySet();
        this.startTime = System.currentTimeMillis();
        this.canSpawnNew = true;

        List<String> configBlocks = ConfigManager.getConfig().getStringList("ExtraAbilities.Cozmyc.Vacuum.HarvestableBlocks");
        if (!configBlocks.isEmpty()) {
            this.harvestableBlocks = EnumSet.noneOf(Material.class);
            for (String blockName : configBlocks) {
                try {
                    Material material = Material.valueOf(blockName.toUpperCase());
                    this.harvestableBlocks.add(material);
                } catch (IllegalArgumentException e) {
                    ProjectKorra.log.warning("Invalid material in Vacuum harvestable blocks: " + blockName);
                }
            }
        }
    }

    public void click() {
        if (!canSpawnNew || windChargeAbilities.size() >= maxWindCharges) {
            return;
        }

        if (System.currentTimeMillis() - startTime > spawnWindow) {
            canSpawnNew = false;
            return;
        }

        spawnWindChargeAbility();
    }

    public void sneak() {
        for (VacuumWindCharge windChargeAbility : windChargeAbilities) {
            windChargeAbility.dropAllPassengers();
        }
        remove();
    }

    private void spawnWindChargeAbility() {
        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection();
        Location spawnLoc = eyeLocation.clone().add(direction.clone().multiply(1.5));

        VacuumWindCharge windChargeAbility = new VacuumWindCharge(player, this, spawnLoc);
        windChargeAbilities.add(windChargeAbility);
    }

    @Override
    public void progress() {
        if (!player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreBinds(this)) {
            remove();
            return;
        }

        if (!bPlayer.getBoundAbilityName().equals("Vacuum")) {
            remove();
            return;
        }

        if (System.currentTimeMillis() - startTime > spawnWindow) {
            canSpawnNew = false;
        }

        updateTargetLocation();

        windChargeAbilities.removeIf(CoreAbility::isRemoved);

        if (windChargeAbilities.isEmpty() && !canSpawnNew) {
            remove();
        }
    }

    @Override
    public void remove() {
        super.remove();

        for (VacuumWindCharge windChargeAbility : windChargeAbilities) {
            windChargeAbility.remove();
        }

        windChargeAbilities.clear();
        bPlayer.addCooldown(this);
    }

    public Set<WindCharge> getWindChargeAbilities() {
        Set<WindCharge> windCharges = new HashSet<>();
        for (VacuumWindCharge windChargeAbility : this.windChargeAbilities) {
            WindCharge windCharge = windChargeAbility.getWindCharge();
            if (windCharge != null) {
                windCharges.add(windCharge);
            }
        }
        return windCharges;
    }

    public Set<Material> getHarvestableBlocks() {
        return harvestableBlocks;
    }

    private void updateTargetLocation() {
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();

        for (int i = 1; i <= maxRange; i++) {
            Location checkLoc = eyeLoc.clone().add(direction.clone().multiply(i));
            Block checkBlock = checkLoc.getBlock();
            Material type = checkBlock.getType();

            boolean isWater = type == Material.WATER;

            if (isWater || !GeneralMethods.isTransparent(checkBlock) || i == maxRange) {
                double upwardBias = direction.getY() > 1.2 ? 0.5 : 1.5;
                this.targetLocation = checkLoc.add(0, upwardBias, 0);
                return;
            }
        }

        this.targetLocation = eyeLoc.clone().add(direction.multiply(maxRange)).add(0, 1.5, 0);
    }

    public Location getTargetLocation() {
        return this.targetLocation;
    }

    public double getMaxRange() {
        return this.maxRange;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Vacuum";
    }

    @Override
    public Location getLocation() {
        return player != null ? player.getLocation() : null;
    }

    @Override
    public List<Location> getLocations() {
        List<Location> locations = new ArrayList<>();

        for (VacuumWindCharge windChargeAbility : windChargeAbilities) {
            Location location = windChargeAbility.getLocation();
            if (location != null) {
                locations.add(location);
            }
        }

        return locations;
    }

    @Override
    public void load() {
        FileConfiguration defaultConfig = ConfigManager.defaultConfig.get();

        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.Cooldown", 5000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxWindCharges", 5);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.SpawnWindow", 3000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.Acceleration", 0.8);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.LavaRevertTime", 10000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxRange", 50.0);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxDuration", 30000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxItemPassengers", 10);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.HarvestableBlocks", Arrays.asList(
                "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SWEET_BERRY_BUSH",
                "COCOA", "NETHER_WART"
        ));

        ConfigManager.defaultConfig.save();

        FileConfiguration langConfig = ConfigManager.languageConfig.get();

        langConfig.addDefault("Abilities.Air.Vacuum.Description", "Vacuum allows Airbenders to create powerful currents of Air that can pull along entities caught in their path. They can even be used to harvest crops by spawning additional gusts or extinguish flames.");
        langConfig.addDefault("Abilities.Air.Vacuum.Instructions", "To use Vacuum, quickly swipe (left-click) to spawn one or multiple wind charges. Guide the wind charges around with your cursor. End the ability immediately by sneaking (shift.)");

        ConfigManager.languageConfig.save();

        ProjectKorra.plugin.getServer().getPluginManager().registerEvents(new VacuumListener(), ProjectKorra.plugin);

        ProjectKorra.log.info("Vacuum [v" + getVersion() + "] by Cozmyc loaded!");
    }

    @Override
    public void stop() {
        ProjectKorra.log.info("Thanks for using Vacuum!");
        super.remove();
    }

    @Override
    public String getAuthor() {
        return "Cozmyc";
    }

    @Override
    public String getVersion() {
        return "1.1.1";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}