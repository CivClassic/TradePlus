package com.trophonix.tradeplus.gui;

import java.util.Map;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

@Getter
public class TradeMenu {

	private Inventory inventory;
	private Player player;
	private TradeMenu partner;

	private Map<Integer, MenuButton> buttons;

}
