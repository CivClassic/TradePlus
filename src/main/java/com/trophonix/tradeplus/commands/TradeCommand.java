package com.trophonix.tradeplus.commands;

import com.trophonix.tradeplus.TradePlusPlugin;
import com.trophonix.tradeplus.events.TradeAcceptEvent;
import com.trophonix.tradeplus.events.TradeRequestEvent;
import com.trophonix.tradeplus.trade.Trade;
import com.trophonix.tradeplus.trade.TradeRequest;
import com.trophonix.tradeplus.util.MsgUtils;
import com.trophonix.tradeplus.util.PlayerUtil;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TradeCommand extends Command {

	private static final DecimalFormat format = new DecimalFormat("0.##");

	private final ConcurrentLinkedQueue<TradeRequest> requests = new ConcurrentLinkedQueue<>();

	private final TradePlusPlugin plugin;

	public TradeCommand(TradePlusPlugin plugin) {
		super(new ArrayList<String>() {
			{
				add("trade");
				if (plugin.getTradeConfig().getAliases() != null) {
					addAll(plugin.getTradeConfig().getAliases());
				}
			}
		});
		this.plugin = plugin;
	}

	@Override
	public void onCommand(CommandSender sender, String[] args) {
		if (!(sender instanceof Player)) {
			MsgUtils.send(sender, "&cThis command can only be executed by players!");
			return;
		}
		final Player player = (Player) sender;

		boolean permissionRequired = plugin.getConfig().getBoolean("permissions.required", false);

		if (args.length == 1) {
			final Player receiver = Bukkit.getPlayer(args[0]);
			if (receiver == null || PlayerUtil.isVanished(receiver)) {
				if (args[0].equalsIgnoreCase("deny")) {
					requests.forEach(req -> {
						if (req.receiver == player) {
							requests.remove(req);
							if (req.sender.isOnline()) {
								plugin.getTradeConfig().getTheyDenied().send(req.sender, "%PLAYER%", player.getName());
							}
						}
					});
					plugin.getTradeConfig().getYouDenied().send(player);
					return;
				}
				plugin.getTradeConfig().getErrorsPlayerNotFound().send(player);
				return;
			}

			if (player == receiver) {
				plugin.getTradeConfig().getErrorsSelfTrade().send(player);
				return;
			}

			if (!plugin.getTradeConfig().isAllowSameIpTrade()) {
				InetSocketAddress address = player.getAddress();
				InetSocketAddress receiverAddress = receiver.getAddress();
				if (address != null && receiverAddress != null && address.getHostName()
						.equals(receiverAddress.getHostName())) {
					plugin.getTradeConfig().getErrorsSameIp().send(player);
					return;
				}
			}

			if (!plugin.getTradeConfig().isAllowTradeInCreative()) {
				if (player.getGameMode().equals(GameMode.CREATIVE)) {
					plugin.getTradeConfig().getErrorsCreative().send(player);
					return;
				}
				else if (receiver.getGameMode().equals(GameMode.CREATIVE)) {
					plugin.getTradeConfig().getErrorsCreativeThem().send(player, "%PLAYER%", receiver.getName());
					return;
				}
			}

			if (player.getWorld().equals(receiver.getWorld())) {
				double amount = plugin.getTradeConfig().getSameWorldRange();
				if (amount != 0.0 && player.getLocation()
						.distanceSquared(receiver.getLocation()) > Math.pow(amount, 2)) {
					plugin.getTradeConfig()
							.getErrorsSameWorldRange()
							.send(player, "%PLAYER%", receiver.getName(), "%AMOUNT%", format.format(amount));
					return;
				}
			}
			else {
				if (plugin.getTradeConfig().isAllowCrossWorld()) {
					double amount = Math.pow(plugin.getTradeConfig().getCrossWorldRange(), 2);
					Location test = receiver.getLocation().clone();
					test.setWorld(player.getWorld());
					if (amount != 0.0 && player.getLocation().distanceSquared(test) > amount) {
						plugin.getTradeConfig()
								.getErrorsCrossWorldRange()
								.send(player, "%PLAYER%", receiver.getName(), "%AMOUNT%", format.format(amount));
						return;
					}
				}
				else {
					plugin.getTradeConfig().getErrorsNoCrossWorld().send(player, "%PLAYER%", receiver.getName());
					return;
				}
			}

			for (TradeRequest req : requests) {
				if (req.sender == player) {
					plugin.getTradeConfig().getErrorsWaitForExpire().send(player, "%PLAYER%", receiver.getName());
					return;
				}
			}

			boolean accept = false;
			for (TradeRequest req : requests) {
				if (req.contains(player) && req.contains(receiver)) {
					accept = true;
				}
			}
			if (accept) {
				TradeAcceptEvent tradeAcceptEvent = new TradeAcceptEvent(receiver, player);
				Bukkit.getPluginManager().callEvent(tradeAcceptEvent);
				if (tradeAcceptEvent.isCancelled()) {
					return;
				}
				plugin.getTradeConfig().getAcceptSender().send(receiver, "%PLAYER%", player.getName());
				plugin.getTradeConfig().getAcceptReceiver().send(player, "%PLAYER%", receiver.getName());
				new Trade(receiver, player);
				requests.removeIf(req -> req.contains(player) && req.contains(receiver));
			}
			else {
				String sendPermission = plugin.getTradeConfig().getSendPermission();
				if (permissionRequired) {
					if (!sender.hasPermission(sendPermission)) {
						plugin.getTradeConfig().getErrorsNoPermsAccept().send(player);
						return;
					}
				}

				String acceptPermission = plugin.getTradeConfig().getAcceptPermission();
				if (permissionRequired && !receiver.hasPermission(acceptPermission)) {
					plugin.getTradeConfig().getErrorsNoPermsReceive().send(player, "%PLAYER%", receiver.getName());
					return;
				}

				TradeRequestEvent event = new TradeRequestEvent(player, receiver);
				Bukkit.getPluginManager().callEvent(event);
				if (event.isCancelled()) {
					return;
				}
				final TradeRequest request = new TradeRequest(player, receiver);
				requests.add(request);
				plugin.getTradeConfig().getRequestSent().send(player, "%PLAYER%", receiver.getName());
				plugin.getTradeConfig()
						.getRequestReceived()
						.setOnClick("/trade " + player.getName())
						.send(receiver, "%PLAYER%", player.getName());
				Bukkit.getScheduler().runTaskLater(plugin, () -> {
					boolean was = requests.remove(request);
					if (player.isOnline() && was) {
						plugin.getTradeConfig().getExpired().send(player, "%PLAYER%", receiver.getName());
					}
				}, 20 * plugin.getTradeConfig().getRequestCooldownSeconds());
			}
			return;
		}
		plugin.getTradeConfig().getErrorsInvalidUsage().send(player);
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, String[] args, String full) {
		List<String> args0 = new ArrayList<>();
		args0.add("deny");
		args0.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
		if (args.length == 0) {
			return args0;
		}
		else if (args.length == 1) {
			return args0.stream()
					.filter(name -> !name.equalsIgnoreCase(args[0]) && name.toLowerCase()
							.startsWith(args[0].toLowerCase()))
					.collect(Collectors.toList());
		}
		return super.onTabComplete(sender, args, full);
	}

}
