package net.doodcraft.cozmyc.vacuum;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;

import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public class VacuumListener implements Listener {

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        if (bPlayer == null) {
            return;
        }

        if (!bPlayer.canBend(CoreAbility.getAbility("Vacuum"))) {
            return;
        }

        if (!CoreAbility.getAbility("Vacuum").equals(bPlayer.getBoundAbility())) {
            return;
        }

        Vacuum vacuum = CoreAbility.getAbility(player, Vacuum.class);
        if (vacuum == null) {
            new Vacuum(player);
        } else {
            vacuum.click();
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }

        Player player = event.getPlayer();
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        if (bPlayer == null) {
            return;
        }

        Vacuum vacuum = CoreAbility.getAbility(player, Vacuum.class);
        if (vacuum == null) {
            return;
        }

        if (!CoreAbility.getAbility("Vacuum").equals(bPlayer.getBoundAbility())) {
            return;
        }

        vacuum.sneak();
    }

    @EventHandler
    public void onWindChargeCollide(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof WindCharge windCharge)) return;

        if (Vacuum.ownsWindCharge(windCharge)) {
            event.setCancelled(true);
        }
    }
}