package net.doodcraft.cozmyc.vacuum;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.util.TempBlock;

import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Vacuum extends AirAbility implements AddonAbility {

    private long cooldown;
    private int maxWindCharges;
    private long spawnWindow;
    private double acceleration;
    private int maxCropGusts;
    private long lavaRevertTime;
    private double maxRange;
    private Set<Material> harvestableBlocks;
    private long maxDuration;

    private Set<WindCharge> windCharges;
    private Map<WindCharge, Entity> passengers;
    private long startTime;
    private boolean canSpawnNew;
    private int cropGustsSpawned;

    public Vacuum(Player player) {
        super(player);

        if (!bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, Vacuum.class)) {
            return;
        }

        setFields();

        this.windCharges = ConcurrentHashMap.newKeySet();
        this.passengers = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
        this.canSpawnNew = true;
        this.cropGustsSpawned = 0;

        spawnWindCharge();
        start();
    }

    private void setFields() {
        this.cooldown = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.Cooldown");
        this.maxWindCharges = ConfigManager.getConfig().getInt("ExtraAbilities.Cozmyc.Vacuum.MaxWindCharges");
        this.spawnWindow = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.SpawnWindow");
        this.acceleration = ConfigManager.getConfig().getDouble("ExtraAbilities.Cozmyc.Vacuum.Acceleration");
        this.maxCropGusts = ConfigManager.getConfig().getInt("ExtraAbilities.Cozmyc.Vacuum.MaxCropGusts");
        this.lavaRevertTime = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.LavaRevertTime");
        this.maxRange = ConfigManager.getConfig().getDouble("ExtraAbilities.Cozmyc.Vacuum.MaxRange");
        this.maxDuration = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.MaxDuration");

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
        if (!canSpawnNew || windCharges.size() >= maxWindCharges) {
            return;
        }

        if (System.currentTimeMillis() - startTime > spawnWindow) {
            canSpawnNew = false;
            return;
        }

        spawnWindCharge();
    }

    public void sneak() {
        for (Map.Entry<WindCharge, Entity> entry : passengers.entrySet()) {
            WindCharge windCharge = entry.getKey();
            Entity passenger = entry.getValue();

            if (passenger != null && windCharge != null) {
                windCharge.removePassenger(passenger);
            }
        }

        for (WindCharge windCharge : windCharges) {
            playAirbendingParticles(windCharge.getLocation(), 6, 0.2, 0.2, 0.2);
            windCharge.remove();
        }

        remove();
    }

    private void spawnWindCharge() {
        Location eyeLocation = player.getEyeLocation();

        Vector direction = eyeLocation.getDirection();
        Location spawnLoc = eyeLocation.clone().add(direction.clone().multiply(1.5));

        WindCharge windCharge = player.getWorld().spawn(spawnLoc, WindCharge.class);
        windCharge.setShooter(player);

        windCharge.setVelocity(direction.multiply(acceleration * 0.5));

        windCharge.setGravity(false);

        windCharges.add(windCharge);

        startWindChargeTracking(windCharge);
    }

    private Location getCurrentTargetLocation() {
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();

        for (int i = 1; i <= maxRange; i++) {
            Location checkLoc = eyeLoc.clone().add(direction.clone().multiply(i));
            Block checkBlock = checkLoc.getBlock();

            if (!GeneralMethods.isTransparent(checkBlock) || i == maxRange) {
                return checkLoc.add(0, 1.5, 0);
            }
        }

        return eyeLoc.clone().add(direction.multiply(maxRange)).add(0, 1.5, 0);
    }

    private void startWindChargeTracking(WindCharge windCharge) {
        new BukkitRunnable() {
            private long ticks = 0;

            @Override
            public void run() {
                if (windCharge.isDead() || !windCharges.contains(windCharge)) {
                    windCharges.remove(windCharge);
                    passengers.remove(windCharge);
                    this.cancel();

                    if (windCharges.isEmpty()) {
                        Vacuum.this.remove();
                    }
                    return;
                }

                if (!player.isOnline() || player.isDead()) {
                    playAirbendingParticles(windCharge.getLocation(), 6, 0.2, 0.2, 0.2);
                    windCharge.remove();
                    this.cancel();
                    return;
                }

                Location target = getCurrentTargetLocation();
                Location currentLoc = windCharge.getLocation();

                double distanceFromPlayer = currentLoc.distance(player.getLocation());

                if (distanceFromPlayer > maxRange || ticks > maxDuration/50) {
                    playAirbendingParticles(windCharge.getLocation(), 6, 0.2, 0.2, 0.2);
                    windCharge.remove();
                    this.cancel();
                    return;
                }

                Vector toTarget = target.toVector().subtract(currentLoc.toVector());
                double distanceToTarget = toTarget.length();

                if (distanceToTarget > 1.5) {
                    toTarget.normalize();
                    Vector desiredVelocity = toTarget.multiply(acceleration);

                    Vector currentVel = windCharge.getVelocity();
                    Vector smoothed = currentVel.clone().multiply(0.7).add(desiredVelocity.clone().multiply(0.3));
                    windCharge.setVelocity(smoothed);
                } else {
                    Vector currentVel = windCharge.getVelocity();
                    windCharge.setVelocity(currentVel.multiply(0.8));
                }

                checkEntityCollisions(windCharge);
                checkFireLavaInteractions(windCharge);
                checkCropHarvesting(windCharge);

                ticks++;
            }
        }.runTaskTimer(ProjectKorra.plugin, 0, 1);
    }

    private void checkEntityCollisions(WindCharge windCharge) {
        if (passengers.containsKey(windCharge)) {
            return;
        }

        Collection<Entity> nearbyEntities = windCharge.getWorld().getNearbyEntities(
                windCharge.getLocation(), 1.5, 1.5, 1.5);

        for (Entity entity : nearbyEntities) {
            if (entity == windCharge || entity == player || entity instanceof WindCharge) {
                continue;
            }

            boolean isPassenger = false;
            for (WindCharge otherCharge : windCharges) {
                if (otherCharge.getPassengers().contains(entity)) {
                    isPassenger = true;
                    break;
                }
            }

            if (!isPassenger) {
                windCharge.addPassenger(entity);
                passengers.put(windCharge, entity);
                break;
            }
        }
    }

    private void checkFireLavaInteractions(WindCharge windCharge) {
        Location loc = windCharge.getLocation();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();

                    if (block.getType() == Material.FIRE) {
                        block.setType(Material.AIR);
                        block.getWorld().playEffect(block.getLocation(), Effect.EXTINGUISH, 0);
                    } else if (block.getType() == Material.LAVA) {
                        new TempBlock(block, Material.OBSIDIAN.createBlockData(), lavaRevertTime);
                        block.getWorld().playEffect(block.getLocation(), Effect.EXTINGUISH, 0);
                    }
                }
            }
        }
    }

    private void checkCropHarvesting(WindCharge windCharge) {
        Location loc = windCharge.getLocation();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();
                    if (isValidHarvestableBlock(block)) {
                        Collection<ItemStack> drops = harvestCrop(block);

                        if (!drops.isEmpty()) {
                            Iterator<ItemStack> dropIterator = drops.iterator();
                            ItemStack firstDrop = dropIterator.next();

                            Item firstItem = block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), firstDrop);

                            windCharge.addPassenger(firstItem);
                            passengers.put(windCharge, firstItem);

                            if (cropGustsSpawned >= maxCropGusts) {
                                continue;
                            }

                            while (dropIterator.hasNext() && cropGustsSpawned < maxCropGusts) {
                                ItemStack drop = dropIterator.next();
                                Item item = block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);

                                spawnCropGust(item.getLocation(), item);
                                cropGustsSpawned++;
                            }
                        }
                    }
                }
            }
        }
    }

    private void spawnCropGust(Location location, Item item) {
        WindCharge gustCharge = player.getWorld().spawn(location.add(0, 1, 0), WindCharge.class);
        gustCharge.setShooter(player);
        gustCharge.setVelocity(new Vector(0, 0.1, 0));

        windCharges.add(gustCharge);

        gustCharge.addPassenger(item);
        passengers.put(gustCharge, item);

        startWindChargeTracking(gustCharge);
    }

    private Collection<ItemStack> harvestCrop(Block crop) {
        if (!isValidHarvestableBlock(crop)) {
            return new ArrayList<>();
        }

        BlockData blockData = crop.getBlockData();
        Collection<ItemStack> drops = new ArrayList<>(crop.getDrops());

        crop.setType(Material.AIR);
        crop.getWorld().playEffect(crop.getLocation(), Effect.STEP_SOUND, crop.getType());

        if (blockData instanceof Ageable ageable) {
            ageable.setAge(0);
            crop.setBlockData(ageable);
        }

        return drops;
    }

    private boolean isValidHarvestableBlock(Block block) {
        if (!harvestableBlocks.contains(block.getType())) {
            return false;
        }

        BlockData blockData = block.getBlockData();
        if (blockData instanceof Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }

        return true;
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

        if (System.currentTimeMillis() - startTime > spawnWindow) {
            canSpawnNew = false;
        }

        windCharges.removeIf(windCharge -> {
            if (windCharge.isDead()) {
                passengers.remove(windCharge);
                return true;
            }
            return false;
        });

        if (windCharges.isEmpty()) {
            remove();
        }
    }

    @Override
    public void remove() {
        super.remove();

        for (Map.Entry<WindCharge, Entity> entry : passengers.entrySet()) {
            WindCharge windCharge = entry.getKey();
            Entity passenger = entry.getValue();

            if (passenger != null && windCharge != null && !windCharge.isDead()) {
                windCharge.removePassenger(passenger);
            }
        }

        for (WindCharge windCharge : windCharges) {
            if (!windCharge.isDead()) {
                playAirbendingParticles(windCharge.getLocation(), 6, 0.2, 0.2, 0.2);
                windCharge.remove();
            }
        }

        windCharges.clear();
        passengers.clear();

        bPlayer.addCooldown(this);
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
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
    public void load() {
        FileConfiguration defaultConfig = ConfigManager.defaultConfig.get();

        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.Cooldown", 5000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxWindCharges", 5);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.SpawnWindow", 3000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.Acceleration", 0.8);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxCropGusts", 3);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.LavaRevertTime", 10000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxRange", 50.0);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.MaxDuration", 30000);
        defaultConfig.addDefault("ExtraAbilities.Cozmyc.Vacuum.HarvestableBlocks", Arrays.asList(
                "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SWEET_BERRY_BUSH",
                "COCOA", "NETHER_WART", "BAMBOO"
        ));

        ConfigManager.defaultConfig.save();

        FileConfiguration langConfig = ConfigManager.languageConfig.get();

        langConfig.addDefault("Abilities.Vacuum.Description", "Vacuum allows Airbenders to create powerful currents of Air that can pull along entities caught in their path. They can even be used to harvest crops or extinguish flames.");
        langConfig.addDefault("Abilities.Vacuum.Instructions", "To use Vacuum, quickly swipe (left-click) to spawn one or multiple wind charges. Guide the wind charges around with your cursor. End the ability immediately by sneaking (shift.)");

        ConfigManager.languageConfig.save();

        ProjectKorra.plugin.getServer().getPluginManager().registerEvents(new VacuumListener(), ProjectKorra.plugin);

        ProjectKorra.log.info("Vacuum ability by Cozmyc loaded!");
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
        return "1.0.1";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}