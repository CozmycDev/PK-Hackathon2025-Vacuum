package net.doodcraft.cozmyc.vacuum;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.util.TempBlock;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;

public class VacuumWindCharge extends AirAbility implements AddonAbility {

    private WindCharge windCharge;
    private final Vacuum vacuum;

    @Attribute("MaxItemPassengers")
    private int maxItemPassengers;
    @Attribute("Acceleration")
    private double acceleration;
    private long lavaRevertTime;
    @Attribute(Attribute.DURATION)
    private long maxDuration;

    private Entity nonItemPassenger;
    private final long startTime;

    public VacuumWindCharge(Player player, Vacuum parent, Location spawnLocation) {
        super(player);

        this.vacuum = parent;
        this.startTime = System.currentTimeMillis();

        setFields();
        spawnWindCharge(spawnLocation);
        start();
    }

    private void setFields() {
        this.acceleration = ConfigManager.getConfig().getDouble("ExtraAbilities.Cozmyc.Vacuum.Acceleration");
        this.lavaRevertTime = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.LavaRevertTime");
        this.maxDuration = ConfigManager.getConfig().getLong("ExtraAbilities.Cozmyc.Vacuum.MaxDuration");
        this.maxItemPassengers = ConfigManager.getConfig().getInt("ExtraAbilities.Cozmyc.Vacuum.MaxItemPassengers");
    }

    private void spawnWindCharge(Location spawnLocation) {
        this.windCharge = player.getWorld().spawn(spawnLocation, WindCharge.class);

        if (spawnLocation.distanceSquared(player.getLocation()) < 1.0) {
            Location eyeLocation = player.getEyeLocation();
            Vector direction = eyeLocation.getDirection();
            Vector initialVelocity = direction.multiply(acceleration * 0.5);
            windCharge.setVelocity(initialVelocity);
        }

        windCharge.setShooter(player);
        windCharge.setGravity(true);
    }

    @Override
    public void progress() {
        if (!isValid()) {
            remove();
            return;
        }

        if (windCharge.isDead()) {
            remove();
            return;
        }

        Location currentLoc = windCharge.getLocation();

        if (shouldRemoveWindCharge(currentLoc)) {
            playRemovalEffects(12, 0.15);
            remove();
            return;
        }

        playVacuumParticle(windCharge.getLocation(), 0.5);

        if (getCurrentTick() % 8 == 0) {
            windCharge.getWorld().playSound(
                    windCharge.getLocation(),
                    Sound.ENTITY_BREEZE_INHALE,
                    0.5f,
                    0.5f
            );
        }

        updateMovement();
        handleInteractions();
    }

    private boolean isValid() {
        return player.isOnline() && !player.isDead() &&
                bPlayer.canBendIgnoreBinds(this) &&
                vacuum != null && !vacuum.isRemoved();
    }

    private boolean shouldRemoveWindCharge(Location currentLoc) {
        if (currentLoc.getWorld() != player.getWorld()) return true;
        if (currentLoc.getBlock().getType().equals(Material.WATER)) return true;

        double distanceFromPlayer = currentLoc.distance(player.getLocation());
        if (distanceFromPlayer > vacuum.getMaxRange()) return true;

        return maxDuration < System.currentTimeMillis() - startTime;
    }

    private void updateMovement() {
        Location target = vacuum.getTargetLocation();
        Vector toTarget = target.toVector().subtract(windCharge.getLocation().toVector());

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
                smoothed = desiredVelocity;
            } else {
                smoothed = currentVel.multiply(0.7).add(desiredVelocity.multiply(0.3));
            }

            windCharge.setVelocity(smoothed);
        } else {
            windCharge.setVelocity(windCharge.getVelocity().multiply(0.8));
        }
    }

    private void handleInteractions() {
        updatePassengerTracking();

        Location windLoc = windCharge.getLocation();
        World world = windLoc.getWorld();

        for (int i = 0; i <= 2; i++) {
            Location checkLoc = windLoc.clone().subtract(0, i, 0);
            Block block = world.getBlockAt(checkLoc);

            if (block.getType() == Material.WATER) {
                playWaterEffects(checkLoc, windLoc);
                break;
            }
        }

        checkEntityCollisions();
        checkFireLavaInteractions();
        checkCropHarvesting();
    }

    private void updatePassengerTracking() {
        if (nonItemPassenger != null && !windCharge.getPassengers().contains(nonItemPassenger)) {
            nonItemPassenger = null;
        }

        if (nonItemPassenger == null) {
            for (Entity passenger : windCharge.getPassengers()) {
                if (!(passenger instanceof Item)) {
                    nonItemPassenger = passenger;
                    break;
                }
            }
        }
    }

    private boolean hasAnyItemPassengers() {
        return windCharge.getPassengers().stream()
                .anyMatch(passenger -> passenger instanceof Item);
    }

    private void checkEntityCollisions() {
        Collection<Entity> nearbyEntities = windCharge.getWorld().getNearbyEntities(
                windCharge.getLocation(), 1.5, 1.5, 1.5);

        for (Entity entity : nearbyEntities) {
            if (entity == windCharge || entity == player || entity instanceof WindCharge) {
                continue;
            }

            if (isEntityAlreadyPassenger(entity)) {
                continue;
            }

            if (entity instanceof Item item) {
                if (nonItemPassenger == null) {
                    handleItemCollision(item);
                }
            } else {
                if (nonItemPassenger == null && !hasAnyItemPassengers()) {
                    windCharge.addPassenger(entity);
                    nonItemPassenger = entity;
                    break;
                }
            }
        }
    }

    private void handleItemCollision(Item item) {
        if (item.isDead() || !item.isValid()) {
            return;
        }

        ItemStack newStack = item.getItemStack();

        for (Entity passenger : windCharge.getPassengers()) {
            if (passenger instanceof Item existingItem) {
                ItemStack existingStack = existingItem.getItemStack();

                if (canMergeStacks(existingStack, newStack)) {
                    int merged = mergeStacks(existingStack, newStack);
                    if (merged > 0) {
                        existingItem.setItemStack(existingStack);

                        if (newStack.getAmount() <= 0) {
                            item.remove();
                            return;
                        }

                        item.setItemStack(newStack);
                    }
                }
            }
        }

        if (!item.isDead() && canPickupNewItem()) {
            windCharge.addPassenger(item);
        }
    }

    private boolean canMergeStacks(ItemStack existing, ItemStack newStack) {
        return existing.isSimilar(newStack) && existing.getAmount() < existing.getMaxStackSize();
    }

    private int mergeStacks(ItemStack existing, ItemStack newStack) {
        int maxSize = existing.getMaxStackSize();
        int availableSpace = maxSize - existing.getAmount();
        int toMerge = Math.min(availableSpace, newStack.getAmount());

        existing.setAmount(existing.getAmount() + toMerge);
        newStack.setAmount(newStack.getAmount() - toMerge);

        return toMerge;
    }

    private boolean canPickupAnyItems(Collection<ItemStack> items) {
        if (canPickupNewItem()) {
            return true;
        }

        for (ItemStack newItem : items) {
            for (Entity passenger : windCharge.getPassengers()) {
                if (passenger instanceof Item existingItem) {
                    ItemStack existingStack = existingItem.getItemStack();
                    if (canMergeStacks(existingStack, newItem)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean canPickupNewItem() {
        long currentItemCount = windCharge.getPassengers().stream()
                .filter(e -> e instanceof Item)
                .count();

        return currentItemCount < maxItemPassengers;
    }

    private boolean isEntityAlreadyPassenger(Entity entity) {
        return vacuum.getWindChargeAbilities().stream()
                .anyMatch(charge -> charge.getPassengers().contains(entity));
    }

    private void checkFireLavaInteractions() {
        Location loc = windCharge.getLocation();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();

                    if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
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

    private void checkCropHarvesting() {
        if (nonItemPassenger != null) {
            return;
        }

        Location loc = windCharge.getLocation();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();

                    if (isValidHarvestableBlock(block)) {
                        Collection<ItemStack> potentialDrops = getBlockDrops(block);
                        if (canPickupAnyItems(potentialDrops)) {
                            harvestCropBlock(block);
                        }
                    }
                }
            }
        }
    }

    private void harvestCropBlock(Block crop) {
        Collection<ItemStack> drops = crop.getDrops();

        if (drops.isEmpty()) {
            return;
        }

        breakCropBlock(crop);

        for (ItemStack drop : drops) {
            crop.getWorld().dropItemNaturally(
                    crop.getLocation().add(0.5, 0.5, 0.5), drop);
        }

        playCropBreakSound(crop);
    }

    private Collection<ItemStack> getBlockDrops(Block crop) {
        Collection<ItemStack> drops = new ArrayList<>();
        Material type = crop.getType();
        BlockData data = crop.getBlockData();

        if (data instanceof Ageable ageable) {
            if (ageable.getAge() == ageable.getMaximumAge()) {
                drops.addAll(crop.getDrops());
            }
        } else if (isVerticalPlant(crop)) {
            Block current = crop.getRelative(BlockFace.UP);
            while (current.getType() == type) {
                drops.addAll(current.getDrops());
                current = current.getRelative(BlockFace.UP);
            }
        }

        return drops;
    }

    private void breakCropBlock(Block crop) {
        Material type = crop.getType();
        BlockData data = crop.getBlockData();

        if (data instanceof Ageable ageable) {
            if (ageable.getAge() == ageable.getMaximumAge()) {
                ageable.setAge(0);
                crop.setBlockData(ageable);
            }
        } else if (isVerticalPlant(crop)) {
            Block current = crop.getRelative(BlockFace.UP);
            while (current.getType() == type) {
                current.setType(Material.AIR);
                current = current.getRelative(BlockFace.UP);
            }
        }
    }

    private boolean isValidHarvestableBlock(Block block) {
        Material type = block.getType();
        if (!vacuum.getHarvestableBlocks().contains(type)) {
            return false;
        }

        BlockData data = block.getBlockData();
        if (data instanceof Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }

        return true;
    }

    private boolean isVerticalPlant(Block block) {
        Material type = block.getType();
        Block above = block.getRelative(BlockFace.UP);
        Block below = block.getRelative(BlockFace.DOWN);

        return above.getType() == type && below.getType() != type;
    }

    public void dropAllPassengers() {
        if (windCharge != null && !windCharge.isDead()) {
            windCharge.getPassengers().forEach(windCharge::removePassenger);
            nonItemPassenger = null;
        }
    }

    public WindCharge getWindCharge() {
        return windCharge;
    }

    @Override
    public void remove() {
        super.remove();

        if (windCharge != null && !windCharge.isDead()) {
            windCharge.getPassengers().forEach(windCharge::removePassenger);

            playRemovalEffects(16, 0.15);
            windCharge.remove();
        }
    }

    @Override
    public Location getLocation() {
        return windCharge != null ? windCharge.getLocation() : null;
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public double getCollisionRadius() {
        return 1.5;
    }

    @Override
    public void handleCollision(Collision collision) {
        playRemovalEffects(20, 0.15);
        remove();
    }

    @Override
    public boolean isHiddenAbility() {
        return true;
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
        return 0;
    }

    @Override
    public String getName() {
        return "VacuumWindCharge";
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return vacuum.getAuthor();
    }

    @Override
    public String getVersion() {
        return vacuum.getVersion();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private void playCropBreakSound(Block block) {
        block.getWorld().playSound(block.getLocation(),
                Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.7f, 1.3f);
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

        world.spawnParticle(
                Particle.CLOUD,
                spawnLoc,
                0,
                direction.getX(),
                direction.getY(),
                direction.getZ(),
                0.45,
                null,
                false
        );
    }

    public void playRemovalEffects(int count, double speed) {
        World world = windCharge.getWorld();

        windCharge.getWorld().playSound(
                windCharge.getLocation(),
                Sound.ENTITY_BREEZE_SHOOT,
                0.5f,
                0.2f
        );

        for (int i = 0; i < count; i++) {
            double theta = Math.random() * 2 * Math.PI;
            double phi = Math.acos(2 * Math.random() - 1);

            double x = Math.sin(phi) * Math.cos(theta);
            double y = Math.sin(phi) * Math.sin(theta);
            double z = Math.cos(phi);

            Vector direction = new Vector(x, y, z).normalize().multiply(speed);

            world.spawnParticle(
                    Particle.CLOUD,
                    windCharge.getLocation(),
                    0,
                    direction.getX(),
                    direction.getY(),
                    direction.getZ(),
                    0.5,
                    null,
                    false
            );
        }
    }

    private void playWaterEffects(Location from, Location to) {
        World world = from.getWorld();
        Vector path = to.toVector().subtract(from.toVector());

        for (int i = 0; i < 10; i++) {
            double t = Math.random();
            Vector point = from.toVector().add(path.clone().multiply(t));
            Location particleLoc = point.toLocation(world);

            world.spawnParticle(
                    Particle.BUBBLE_POP,
                    particleLoc,
                    1,
                    Math.random() - 0.5, Math.random(), Math.random() - 0.5,
                    0.01,
                    null,
                    false
            );
        }
    }
}