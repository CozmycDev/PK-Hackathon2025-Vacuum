package net.doodcraft.cozmyc.vacuum;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.util.TempBlock;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Vacuum extends AirAbility implements AddonAbility {

    private long cooldown;
    private int maxWindCharges;
    private long spawnWindow;
    private double acceleration;
    private long lavaRevertTime;
    private double maxRange;
    private Set<Material> harvestableBlocks;
    private long maxDuration;

    // Static map of entity->ability instance for instant lookups.
    private static final Map<UUID, Vacuum> publicWindChargeMap = new ConcurrentHashMap<>();
    private Set<WindCharge> windCharges; // Wind charge tracking
    private Map<WindCharge, Entity> passengers; // Carrier->Passenger tracking

    private long startTime;
    private boolean canSpawnNew;

    public Vacuum(Player player) {
        super(player);

        // Check hook
        if (!bPlayer.canBend(this)) {
            return;
        }

        // Check for active instance
        if (hasAbility(player, Vacuum.class)) {
            return;
        }

        // Load config
        setFields();

        this.windCharges = ConcurrentHashMap.newKeySet();
        this.passengers = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
        this.canSpawnNew = true;

        spawnInitialWindCharge(); // Initial wind charge
        start();
    }

    private void setFields() {
        this.cooldown = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.Cooldown");
        this.maxWindCharges = ConfigManager.getConfig().getInt("ExtraAbilities.Cozmyc.Vacuum.MaxWindCharges");
        this.spawnWindow = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.SpawnWindow");
        this.acceleration = ConfigManager.getConfig().getDouble("ExtraAbilities.Cozmyc.Vacuum.Acceleration");
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

        spawnInitialWindCharge(); // Spawn additional gusts for each click within the spawn window.
    }

    public void sneak() {
        for (Map.Entry<WindCharge, Entity> entry : passengers.entrySet()) {
            WindCharge windCharge = entry.getKey();
            Entity passenger = entry.getValue();

            if (passenger != null && windCharge != null) {
                windCharge.removePassenger(passenger);
            }
        }

        remove();
    }


    private void spawnInitialWindCharge() {
        Location eyeLocation = player.getEyeLocation();

        // Get initial spawn loc.
        Vector direction = eyeLocation.getDirection();
        Location spawnLoc = eyeLocation.clone().add(direction.clone().multiply(1.5));

        WindCharge windCharge = player.getWorld().spawn(spawnLoc, WindCharge.class);
        windCharge.setShooter(player);

        windCharge.setVelocity(direction.multiply(acceleration * 0.5));
        windCharge.setGravity(true);

        publicWindChargeMap.put(windCharge.getUniqueId(), this);
        windCharges.add(windCharge);
    }

    // Raytrace where the player is looking to set the gusts target location.
    private Location getCurrentTargetLocation() {
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();

        for (int i = 1; i <= maxRange; i++) {
            Location checkLoc = eyeLoc.clone().add(direction.clone().multiply(i));
            Block checkBlock = checkLoc.getBlock();

            if (!GeneralMethods.isTransparent(checkBlock) || i == maxRange) {
                double upwardBias = direction.getY() > 0.6 ? 0.5 : 1.5;
                return checkLoc.add(0, upwardBias, 0);
            }
        }

        return eyeLoc.clone().add(direction.multiply(maxRange)).add(0, 1.5, 0);
    }

    // Check nearby for entities to collect.
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

    // Check adjacent blocks for eligible extinguishables.
    private void checkFireLavaInteractions(WindCharge windCharge) {
        Location loc = windCharge.getLocation();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();

                    if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE ) {
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

    // Check adjacent blocks for eligible harvestables.
    private void checkCropHarvesting(WindCharge windCharge) {
        Location loc = windCharge.getLocation();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();

                    if (isValidHarvestableBlock(block)) {
                        Collection<ItemStack> drops = harvestCrop(block);

                        for (ItemStack drop : drops) {
                            Item item = block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
                            windCharge.addPassenger(item);
                            passengers.put(windCharge, item);
                        }
                    }
                }
            }
        }
    }

    // Attempt to gather the target blocks item drops and return them as an ArrayList.
    private Collection<ItemStack> harvestCrop(Block crop) {
        Collection<ItemStack> drops = new ArrayList<>();
        Material type = crop.getType();

        BlockData data = crop.getBlockData();

        if (data instanceof Ageable ageable) {
            if (ageable.getAge() == ageable.getMaximumAge()) {
                drops.addAll(crop.getDrops());
                ageable.setAge(0);
                crop.setBlockData(ageable);
                playCropBreakSound(crop);
            }
            return drops;
        } else if (isVerticalPlant(crop)) {
            Block current = crop.getRelative(BlockFace.UP);
            while (current.getType() == type) {
                drops.addAll(current.getDrops());
                current.setType(Material.AIR);
                current = current.getRelative(BlockFace.UP);
            }
            playCropBreakSound(crop);
            return drops;
        }

        return drops;
    }

    // Check if this is a configured block eligible for harvesting.
    private boolean isValidHarvestableBlock(Block block) {
        Material type = block.getType();
        if (!harvestableBlocks.contains(type)) return false;

        BlockData data = block.getBlockData();

        if (data instanceof Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }

        return true;
    }

    // Check if this block is an upper part of a structural plant, like sugar cane or cactus.
    private boolean isVerticalPlant(Block block) {
        Material type = block.getType();

        Block above = block.getRelative(BlockFace.UP);
        Block below = block.getRelative(BlockFace.DOWN);

        return above.getType() == type && below.getType() != type;
    }

    private void playCropBreakSound(Block block) {
        block.getWorld().playSound(block.getLocation(),
                Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.7f, 1.3f);
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

        Iterator<WindCharge> iterator = windCharges.iterator();

        while (iterator.hasNext()) {
            WindCharge windCharge = iterator.next();

            if (windCharge.isDead()) {
                passengers.remove(windCharge); // Remove carrier tracking for this gust.
                publicWindChargeMap.remove(windCharge.getUniqueId());
                iterator.remove(); // Remove tracking for this gust.
                continue;
            }

            Location currentLoc = windCharge.getLocation();

            if (currentLoc.getWorld() != player.getWorld()) {
                playAirbendingParticles(currentLoc, 12, 0.2, 0.2, 0.2);
                windCharge.getWorld().playSound(
                        windCharge.getLocation(),
                        Sound.ENTITY_BREEZE_SHOOT,
                        0.5f,
                        2.0f
                );
                publicWindChargeMap.remove(windCharge.getUniqueId());
                windCharge.remove();
                iterator.remove();
                continue;
            }

            if (currentLoc.getBlock().getType().equals(Material.WATER)) {
                playAirbendingParticles(currentLoc, 12, 0.2, 0.2, 0.2);
                windCharge.getWorld().playSound(
                        windCharge.getLocation(),
                        Sound.ENTITY_BREEZE_SHOOT,
                        0.5f,
                        2.0f
                );
                publicWindChargeMap.remove(windCharge.getUniqueId());
                windCharge.remove();
                iterator.remove();
                continue;
            }

            double distanceFromPlayer = currentLoc.distance(player.getLocation());

            if (distanceFromPlayer > maxRange || maxDuration < System.currentTimeMillis() - startTime) {
                playAirbendingParticles(currentLoc, 12, 0.2, 0.2, 0.2);
                windCharge.getWorld().playSound(
                        windCharge.getLocation(),
                        Sound.ENTITY_BREEZE_SHOOT,
                        0.5f,
                        2.0f
                );
                publicWindChargeMap.remove(windCharge.getUniqueId());
                windCharge.remove();
                iterator.remove();
                continue;
            }

            playVacuumParticle(windCharge.getLocation(), 0.7);

            if (getCurrentTick() % 8 == 0) {
                windCharge.getWorld().playSound(
                        windCharge.getLocation(),
                        Sound.ENTITY_BREEZE_IDLE_GROUND,
                        0.5f,
                        1.5f
                );
            }

            Location target = getCurrentTargetLocation();
            Vector toTarget = target.toVector().subtract(currentLoc.toVector());

            double distance = toTarget.length();
            if (distance > 1.5) {
                Vector direction = toTarget.clone().normalize();

                if (Math.abs(direction.getY()) > 0.95) {
                    direction.setX(direction.getX() + 0.01);
                    direction.setZ(direction.getZ() + 0.01);
                    direction.normalize();
                }

                Vector desiredVelocity = direction.multiply(acceleration);

                Vector currentVel = windCharge.getVelocity();
                Vector smoothed;

                if (currentVel.length() < 0.1 && distance > 3.0) {
                    smoothed = desiredVelocity; // Skip smoothing if too slow
                } else {
                    smoothed = currentVel.multiply(0.7).add(desiredVelocity.multiply(0.3));
                }

                windCharge.setVelocity(smoothed);
            } else {
                windCharge.setVelocity(windCharge.getVelocity().multiply(0.8));
            }

            if (windCharge.getPassengers().isEmpty()) {
                passengers.remove(windCharge);
            }

            checkEntityCollisions(windCharge);
            checkFireLavaInteractions(windCharge);
            checkCropHarvesting(windCharge);
        }

        if (windCharges.isEmpty() && !canSpawnNew) {
            remove(); // If all the gusts have died end the ability.
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
            publicWindChargeMap.remove(windCharge.getUniqueId());

            if (!windCharge.isDead()) {
                playAirbendingParticles(windCharge.getLocation(), 6, 0.2, 0.2, 0.2);
                windCharge.getWorld().playSound(
                        windCharge.getLocation(),
                        Sound.ENTITY_BREEZE_SHOOT,
                        0.5f,
                        2.0f
                );
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
    public double getCollisionRadius() {
        return 1.25;
    }

    @Override
    public List<Location> getLocations() {
        List<Location> locations = new ArrayList<>();

        for (WindCharge charge : windCharges) {
            if (charge != null && !charge.isDead()) {
                locations.add(charge.getLocation());
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
        return "1.0.3";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public void playVacuumParticle(Location center, double radius) {
        World world = center.getWorld();

        double theta = Math.random() * 2 * Math.PI;
        double phi = Math.acos(2 * Math.random() - 1);

        double x = radius * Math.sin(phi) * Math.cos(theta);
        double y = radius * Math.sin(phi) * Math.sin(theta);
        double z = radius * Math.cos(phi);

        Location spawnLoc = center.clone().add(x, y, z);
        Vector direction = center.toVector().subtract(spawnLoc.toVector()).normalize().multiply(0.1);

        Particle.DustOptions dustOptions = new Particle.DustOptions(Color.GRAY, 0.7F);
        world.spawnParticle(
                Particle.DUST,
                spawnLoc,
                0,
                direction.getX(),
                direction.getY(),
                direction.getZ(),
                0.1,
                dustOptions,
                false
        );
    }

    public static boolean ownsWindCharge(WindCharge windCharge) {
        return publicWindChargeMap.get(windCharge.getUniqueId()) != null;
    }
}