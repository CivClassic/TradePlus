package com.trophonix.tradeplus.hooks.factions;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.FPlayer;
import com.massivecraft.factions.FPlayers;
import com.massivecraft.factions.Faction;
import org.bukkit.entity.Player;

public class MassiveCraftFactionsHook {

	public static boolean isPlayerInEnemyTerritory(Player player) {
		FPlayer me = FPlayers.getInstance().getByPlayer(player);
		Faction faction = Board.getInstance().getFactionAt(new FLocation(player.getLocation()));
		return me.getRelationTo(faction).isEnemy();
	}

}
