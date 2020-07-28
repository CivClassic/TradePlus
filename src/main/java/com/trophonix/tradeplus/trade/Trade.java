package com.trophonix.tradeplus.trade;

import com.trophonix.tradeplus.TradePlusPlugin;
import com.trophonix.tradeplus.events.TradeCompleteEvent;
import com.trophonix.tradeplus.extras.ExperienceExtra;
import com.trophonix.tradeplus.extras.Extra;
import com.trophonix.tradeplus.logging.TradeLog;
import com.trophonix.tradeplus.util.InvUtils;
import com.trophonix.tradeplus.util.ItemFactory;
import com.trophonix.tradeplus.util.Sounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import vg.civcraft.mc.civmodcore.util.Iteration;

public class Trade implements Listener {

    private static final List<Integer> extraSlots = Iteration.collect(LinkedList::new,
			45, 46, 47, 48, 39, 38, 37, 36, 27, 28, 29, 30);

    public final Player player1;
    public final Player player2;
    private final TradePlusPlugin pl = TradePlusPlugin.getPlugin(TradePlusPlugin.class);
    private final List<Extra> extras = new ArrayList<>();
    private final Map<Integer, Extra> placedExtras = new HashMap<>();
    private final long startTime = System.currentTimeMillis();
    private boolean cancelOnClose1 = true, cancelOnClose2 = true;
    @Getter
    private Inventory spectatorInv, inv1, inv2;
    private boolean accept1, accept2;
    private Location location1, location2;
    private ItemStack[] accepted1, accepted2;
    private boolean forced = false;
    private BukkitTask task;
    @Getter
    private boolean cancelled;

    public Trade(Player p1, Player p2) {
        player1 = p1;
        player2 = p2;
        location1 = p1.getLocation();
        location2 = p2.getLocation();
        pl.getTaskFactory().newChain().sync(() -> {
            inv1 = InvUtils.getTradeInventory(player1, player2);
            inv2 = InvUtils.getTradeInventory(player2, player1);
            spectatorInv = InvUtils.getSpectatorInventory(player1, player2);
        }).async(() -> {
          if (pl.getTradeConfig().isSpectateEnabled() && pl.getTradeConfig().isSpectateBroadcast()) {
            Bukkit.getOnlinePlayers().forEach(p -> {
              if (p.hasPermission("tradeplus.admin") && !p.hasPermission("tradeplus.admin.silent")) {
                pl.getTradeConfig()
                        .getSpectateMessage()
                        .setOnClick("/tradeplus spectate " + player1.getName() + " " + player2.getName())
                        .send(p, "%PLAYER1%", player1.getName(), "%PLAYER2%", player2.getName());
              }
            });
          }
            if (pl.getConfig().getBoolean("extras.experience.enabled", true)) {
                extras.add(new ExperienceExtra(player1, player2, pl, this));
            }
        }).sync(() -> {
            Bukkit.getServer().getPluginManager().registerEvents(this, pl);

            for (Extra extra : extras) {
                extra.init();
            }
            updateExtras();
        }).sync(() -> {
            pl.ongoingTrades.add(this);
            player1.openInventory(inv1);
            player2.openInventory(inv2);
        }).execute();
    }

    private static List<ItemStack> combine(ItemStack[] items) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
          if (item == null) {
            continue;
          }
            item = item.clone();
            for (int j = i + 1; j < items.length; j++) {
                ItemStack dupe = items[j];
                if (item.isSimilar(dupe)) {
                    item.setAmount(item.getAmount() + dupe.getAmount());
                    items[j] = null;
                }
            }
            result.add(item);
        }
        return result;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Inventory inv = event.getInventory();
        if (inv1.getViewers().contains(player) || inv2.getViewers().contains(player)) {
            if (accept1 && accept2) {
                event.setCancelled(true);
                return;
            }

            for (int slot : event.getInventorySlots()) {
                if (!InvUtils.leftSlots.contains(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (event.getInventorySlots().size() > 0) {
                if (pl.getTradeConfig()
                        .isPreventChangeOnAccept() && ((player.equals(player1) && accept1) || (player.equals(player2) && accept2))) {
                    event.setCancelled(true);
                    return;
                }
                Bukkit.getScheduler().runTaskLater(pl, this::updateInventories, 1L);
                click();
            }
        }
        else if (inv.equals(spectatorInv)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        Inventory inv = event.getClickedInventory();
      if (inv == null) {
        return;
      }

        ClickType click = event.getClick();

        if (spectatorInv.equals(inv)) {
            event.setCancelled(true);
            return;
        }

        int slot = event.getSlot();
        // if the event didn't occur
        // within a trade inventory,
        // don't bother processing
        if (event.getRawSlot() < event.getView().getTopInventory().getSize() && (inv1.getViewers()
                .contains(player) || inv2.getViewers().contains(player))) {
            // don't let players interact
            // with a cancelled trade window
            if (cancelled) {
                event.setCancelled(true);
                return;
            }
            if (click.equals(ClickType.DOUBLE_CLICK)) {
                event.setCancelled(true);
                return;
            }
            // if it's in the left side,
            // the event will affect the
            // player's trade
            if (InvUtils.leftSlots.contains(slot) && getExtra(slot) == null) {
                if (accept1 && accept2) {
                    event.setCancelled(true);
                    return;
                }

                if (pl.getTradeConfig()
                        .isPreventChangeOnAccept() && ((player.equals(player1) && accept1) || (player.equals(player2) && accept2))) {
                    event.setCancelled(true);
                    event.setResult(InventoryClickEvent.Result.DENY);
                    return;
                }
                if (pl.getTradeConfig().isAntiscamCancelOnChange()) {
                    accept1 = false;
                    accept2 = false;
                    updateAcceptance();
                }
                Bukkit.getScheduler().runTaskLater(pl, this::updateInventories, 1L);
                click();
            }
            else {
                event.setCancelled(true);
                event.setResult(InventoryClickEvent.Result.DENY);
                ItemStack item = inv.getItem(slot);
                if (item != null) {
                    // toggle button
                    if (item.isSimilar(pl.getTradeConfig().getAccept().build()) || item.isSimilar(pl.getTradeConfig()
                            .getCancel()
                            .build())) {
                        if (!forced) {
                            if (player.equals(player1)) {
                                accept1 = !accept1;
                            }
                            else {
                                accept2 = !accept2;
                            }
                            updateAcceptance();
                            checkAcceptance();
                        }
                        // force trade button
                    }
                    else if (slot == 49 && player.hasPermission("tradeplus.admin")) {
                        if (forced) {
                            forced = false;
                            accept1 = false;
                            accept2 = false;
                            updateAcceptance();
                            checkAcceptance();
                        }
                        else {
                            forced = true;
                            accept1 = true;
                            accept2 = true;
                            updateAcceptance();
                            checkAcceptance();
                        }
                        // extras, or cancel
                    }
                    else {
                        Extra extra = getExtra(slot);
                        if (extra != null) {
                            if (pl.getTradeConfig()
                                    .isPreventChangeOnAccept() && ((player.equals(player1) && accept1) || (player.equals(player2) && accept2))) {
                                return;
                            }
                            if (task != null) {
                                return;
                            }
                            extra.onClick(player, event.getClick());
                            updateExtras();
                            click();
                        }
                    }
                }
            }
            // if they click in the bottom
        }
        else if (player.getInventory().equals(inv)) {
            Inventory open = player.getOpenInventory().getTopInventory();
            if (inv1.getViewers().contains(player) || inv2.getViewers().contains(player)) {
                // Using my own double click
                // code so items only collect
                // from the left side
                if (click.equals(ClickType.DOUBLE_CLICK)) {
                    event.setCancelled(true);
                    if (accept1 && accept2) {
                        return;
                    }
                    ItemStack item = event.getCurrentItem();
                    ItemStack cursor = player.getItemOnCursor();
                    if ((item == null || item.getType().equals(Material.AIR)) && !cursor.getType()
                            .equals(Material.AIR)) {
                        for (int j : InvUtils.leftSlots) {
                          if (getExtra(j) != null) {
                            continue;
                          }
                            ItemStack i = open.getItem(j);
                            if (i != null && cursor.isSimilar(i)) {
                                int amount = cursor.getAmount() + i.getAmount();
                                if (amount <= cursor.getMaxStackSize()) {
                                    open.setItem(j, null);
                                    cursor.setAmount(amount);
                                }
                                else {
                                    int remaining = amount - cursor.getMaxStackSize();
                                    i.setAmount(remaining);
                                    cursor.setAmount(cursor.getMaxStackSize());
                                    break;
                                }
                            }
                        }
                    }
                    // Using my own shift click
                    // code so items only go in
                    // the left side
                }
                else if (click.name().contains("SHIFT")) {
                    event.setCancelled(true);
                    if (isBlocked(event.getCurrentItem())) {
                        Sounds.villagerHit(player, 1);
                        return;
                    }

                    if (accept1 && accept2) {
                        return;
                    }
                    if (pl.getTradeConfig().isAntiscamCancelOnChange()) {
                        accept1 = false;
                        accept2 = false;
                        updateAcceptance();
                    }
                    ItemStack current = event.getCurrentItem();
                    if (current != null) {
                        int amount = click.name().contains("LEFT") ? current.getMaxStackSize() : 1;
                        player.getInventory()
                                .setItem(event.getSlot(), putOnLeft(player.equals(player1) ? inv1 : inv2, current, amount));
                        click();
                    }
                }
                // don't allow changing
                // after cancellation
                if (pl.getTradeConfig()
                        .isPreventChangeOnAccept() && ((player.equals(player1) && accept1) || (player.equals(player2) && accept2))) {
                    event.setCancelled(true);
                    return;
                }
                // cancel on change
                if (pl.getTradeConfig().isAntiscamCancelOnChange()) {
                    accept1 = false;
                    accept2 = false;
                    updateAcceptance();
                }
                Bukkit.getScheduler().runTaskLater(pl, this::updateInventories, 1L);
            }
        }
    }

    // plays a click sound effect to all viewers
    private void click() {
        if (pl.getTradeConfig().isSoundEffectsEnabled() && pl.getTradeConfig().isSoundOnChange()) {
            Sounds.click(player1, 2);
            Sounds.click(player2, 2);
            spectatorInv.getViewers()
                    .stream()
                    .filter(Player.class::isInstance)
                    .forEach(p -> Sounds.click((Player) p, 2));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory closed = event.getInventory();
        if (closed == null || closed.getSize() < 54) {
            return;
        }

        // I keep having issues with
        // identifying inventories so
        // trying to make sure it catches
        // all events
        if (closed.equals(inv1) || closed.equals(inv2) || inv1.getViewers()
                .contains(event.getPlayer()) || inv2.getViewers().contains(event.getPlayer())) {
            if ((event.getPlayer().equals(player1) && !cancelOnClose1) || (event.getPlayer()
                    .equals(player2) && !cancelOnClose2)) {
                return;
            }

            giveOnCursor((Player) event.getPlayer());

            // Return items to them
            giveItemsOnLeft(closed, (Player) event.getPlayer());

            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (inv1.getViewers().isEmpty() && inv2.getViewers().isEmpty() && spectatorInv.getViewers().isEmpty()) {
                    HandlerList.unregisterAll(this);
                }
            }, 1L);

            if (cancelled) {
                return;
            }

            cancel();

            pl.ongoingTrades.remove(this);
            if (task != null) {
                task.cancel();
                task = null;
            }

            pl.getTradeConfig().getCancelled().send(player1, "%PLAYER%", player2.getName());
            pl.getTradeConfig().getCancelled().send(player2, "%PLAYER%", player1.getName());
        }
        else if (closed.equals(spectatorInv) || spectatorInv.getViewers().contains(event.getPlayer())) {
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (inv1.getViewers().isEmpty() && inv2.getViewers().isEmpty() && spectatorInv.getViewers().isEmpty()) {
                    HandlerList.unregisterAll(this);
                }
            }, 1L);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
      if (cancelled || event.getTo() == null) {
        return;
      }
        Player player = event.getPlayer();
        if (player.equals(player1) || player.equals(player2)) {
          if (event.getFrom().distanceSquared(event.getTo()) < 0.01) {
            return;
          }
            if (System.currentTimeMillis() < startTime + 1000) {
                return;
            }
            if (player.equals(player1)) {
                player.teleport(location1);
            }
            else {
                player.teleport(location2);
            }
        }
    }

    @EventHandler
    public void onInventoryPickupEvent(InventoryPickupItemEvent event) {
      if (cancelled) {
        return;
      }
        if (accept1 && accept2 && (event.getInventory() == inv1 || event.getInventory() == inv2)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryInteract(InventoryInteractEvent event) {
        if (accept1 && accept2 && (event.getInventory() == inv1 || event.getInventory() == inv2)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
      if (cancelled) {
        return;
      }
        if (player1.equals(event.getPlayer()) || player2.equals(event.getPlayer())) {
            event.setCancelled(true);
            if (accept1 && accept2) {
                giveOnCursor(event.getPlayer());
            }
        }
    }

    private void giveOnCursor(Player player) {
        if (player.getItemOnCursor().getType() != Material.AIR) {
            player.getInventory()
                    .addItem(player.getItemOnCursor())
                    .forEach((i, j) -> player.getWorld().dropItemNaturally(player.getLocation(), j));
            player.setItemOnCursor(null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (p.equals(player1) || p.equals(player2)) {
            p.closeInventory();
        }
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("TradePlus")) {
            player1.closeInventory();
        }
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
      if (cancelled) {
        return;
      }
        Player player = event.getPlayer();
        if (player.equals(player1) || player.equals(player2)) {
            event.setCancelled(true);
        }
    }

    private void giveItemsOnLeft(Inventory inv, Player player) {
        List<ItemStack> dropoff = new ArrayList<>();
        getItemsOnLeft(inv).forEach(item -> player.getInventory().addItem(item).values().forEach(dropoff::add));
        if (!dropoff.isEmpty()) {
            int size = dropoff.size() / 9;
            if (dropoff.size() % 9 > 0) {
                size++;
            }
            size *= 9;
            Inventory excessChest = Bukkit.createInventory(null, size, pl.getTradeConfig().getExcessTitle());
            dropoff.forEach(excessChest::addItem);
            pl.getExcessChests().add(excessChest);
            Bukkit.getScheduler().runTaskLater(pl, () -> player.openInventory(excessChest), 1L);
        }
    }

    private List<ItemStack> getItemsOnLeft(Inventory inv) {
        List<ItemStack> items = new ArrayList<>();
        InvUtils.leftSlots.forEach(slot -> {
            if (getExtra(slot) == null) {
                ItemStack item = inv.getItem(slot);
                if (item != null) {
                    items.add(item);
                }
            }
        });
        return items;
    }

    private void updateInventories() {
        InvUtils.leftSlots.forEach(slot -> {
            if (getExtra(slot) == null) {
                ItemStack item1 = inv1.getItem(slot);
                if (isBlocked(item1)) {
                    Sounds.villagerHit(player1, 1);
                    inv1.setItem(slot, null);
                    player1.getInventory()
                            .addItem(item1)
                            .values()
                            .stream()
                            .findFirst()
                            .ifPresent(i -> player1.getWorld().dropItemNaturally(player1.getLocation(), i));
                }
                else {
                    inv2.setItem(getRight(slot), item1);
                    spectatorInv.setItem(slot, item1);
                }
                ItemStack item2 = inv2.getItem(slot);
                if (isBlocked(item2)) {
                    Sounds.villagerHit(player2, 1);
                    inv2.setItem(slot, null);
                    player2.getInventory()
                            .addItem(item2)
                            .values()
                            .stream()
                            .findFirst()
                            .ifPresent(i -> player2.getWorld().dropItemNaturally(player2.getLocation(), i));
                }
                else {
                    inv1.setItem(getRight(slot), item2);
                    spectatorInv.setItem(getRight(slot), item2);
                }
            }
        });
        player1.updateInventory();
        player2.updateInventory();
    }

    public void updateExtras() {
        int slot1 = 0, slot2a = 0, slot2b = 0;
        ItemStack separator = pl.getTradeConfig().getSeparator().build();
        for (int i = 0; i < extraSlots.size(); i++) {
            if (i >= extras.size()) {
                break;
            }
            int slot = extraSlots.get(i);
            inv1.setItem(slot, separator);
            inv1.setItem(getRight(slot), separator);
            inv2.setItem(slot, separator);
            inv2.setItem(getRight(slot), separator);
        }
        placedExtras.clear();
        for (Extra extra : extras) {
            inv1.setItem(extraSlots.get(slot1), extra.getIcon(player1));
            inv2.setItem(extraSlots.get(slot1), extra.getIcon(player2));
            placedExtras.put(extraSlots.get(slot1), extra);
            slot1++;
            if (extra.value1 > 0) {
                inv2.setItem(getRight(extraSlots.get(slot2a)), extra.getTheirIcon(player1));
                spectatorInv.setItem(extraSlots.get(slot2a), extra.getTheirIcon(player1));
                slot2a++;
            }
            if (extra.value2 > 0) {
                inv1.setItem(getRight(extraSlots.get(slot2b)), extra.getTheirIcon(player2));
                spectatorInv.setItem(getRight(extraSlots.get(slot2b)), extra.getTheirIcon(player2));
                slot2b++;
            }
        }
        player1.updateInventory();
        player2.updateInventory();
    }

    private void updateAcceptance() {
        if (pl.getTradeConfig().isAcceptEnabled()) {
            inv1.setItem(0, accept1 ? pl.getTradeConfig().getCancel().build() : pl.getTradeConfig()
                    .getAccept()
                    .build());
            inv1.setItem(8, accept2 ? pl.getTradeConfig().getTheirAccept().build() : pl.getTradeConfig()
                    .getTheirCancel()
                    .build());
            inv2.setItem(0, accept2 ? pl.getTradeConfig().getCancel().build() : pl.getTradeConfig()
                    .getAccept()
                    .build());
            inv2.setItem(8, accept1 ? pl.getTradeConfig().getTheirAccept().build() : pl.getTradeConfig()
                    .getTheirCancel()
                    .build());

            inv1.getItem(0).setAmount(pl.getTradeConfig().getAntiscamCountdown());
            inv1.getItem(8).setAmount(pl.getTradeConfig().getAntiscamCountdown());
            inv2.getItem(0).setAmount(pl.getTradeConfig().getAntiscamCountdown());
            inv2.getItem(8).setAmount(pl.getTradeConfig().getAntiscamCountdown());

            spectatorInv.setItem(4, accept1 && accept2 ? pl.getTradeConfig()
                    .getTheirAccept()
                    .build() : pl.getTradeConfig().getTheirCancel().build());
        }
    }

    private void checkAcceptance() {
        if (pl.getTradeConfig().isAcceptEnabled()) {
            if (accept1 && accept2) {
                if (task != null) {
                    return;
                }

                giveOnCursor(player1);
                giveOnCursor(player2);

                accepted1 = getItemsOnLeft(inv1).toArray(new ItemStack[0]);

                accepted2 = getItemsOnLeft(inv2).toArray(new ItemStack[0]);

                if (pl.getTradeConfig().isSoundEffectsEnabled() && pl.getTradeConfig().isSoundOnAccept()) {
                    Sounds.pling(player1, 1);
                    Sounds.pling(player2, 1);
                    spectatorInv.getViewers()
                            .stream()
                            .filter(Player.class::isInstance)
                            .forEach(p -> Sounds.pling((Player) p, 1));
                }

                task = Bukkit.getScheduler().runTaskTimer(pl, () -> {
                    int current = inv1.getItem(0).getAmount();
                    if (current > 1) {
                        inv1.getItem(0).setAmount(current - 1);
                        inv1.getItem(8).setAmount(current - 1);
                        inv2.getItem(0).setAmount(current - 1);
                        inv2.getItem(8).setAmount(current - 1);
                        spectatorInv.getItem(4).setAmount(current - 1);
                        if (pl.getTradeConfig().isSoundEffectsEnabled() && pl.getTradeConfig().isSoundOnCountdown()) {
                            Sounds.click(player1, 2);
                            Sounds.click(player2, 2);
                            spectatorInv.getViewers()
                                    .stream()
                                    .filter(Player.class::isInstance)
                                    .forEach(p -> Sounds.click((Player) p, 2));
                        }
                    }
                    else {
                        if (task != null) {
                            pl.ongoingTrades.remove(this);
                            task.cancel();
                            task = null;

                            if (pl.getTradeConfig().isDiscrepancyDetection()) {
                                boolean discrepancy = false;
                                int i = 0;
                                for (ItemStack item : getItemsOnLeft(inv1)) {
                                  if (item == null) {
                                    continue;
                                  }
                                    if (accepted1.length <= i || !item.isSimilar(accepted1[i++])) {
                                        discrepancy = true;
                                        break;
                                    }
                                }

                                if (!discrepancy) {
                                    i = 0;
                                    for (ItemStack item : getItemsOnLeft(inv2)) {
                                      if (item == null) {
                                        continue;
                                      }
                                        if (accepted2.length <= i || !item.isSimilar(accepted2[i++])) {
                                            discrepancy = true;
                                        }
                                    }
                                }

                                if (discrepancy) {
                                    cancelled = true;
                                    pl.log("Found discrepancy in trade between " + player1.getName() + " and " + player2
                                            .getName());
                                    pl.getTradeConfig()
                                            .getDiscrepancyDetected()
                                            .send(player1, "%PLAYER%", player2.getName());
                                    pl.getTradeConfig()
                                            .getDiscrepancyDetected()
                                            .send(player2, "%PLAYER%", player1.getName());
                                    giveItemsOnLeft(inv1, player1);
                                    giveItemsOnLeft(inv2, player2);
                                    player1.closeInventory();
                                    player2.closeInventory();
                                    return;
                                }
                            }

                            for (int leftSlot : InvUtils.leftSlots) {
                                int rightSlot = getRight(leftSlot);
                                inv1.setItem(leftSlot, inv1.getItem(rightSlot));
                                inv2.setItem(leftSlot, inv2.getItem(rightSlot));
                            }

                            cancel();

                            for (Extra extra : extras) {
                                extra.onTradeEnd();
                            }

                            if (pl.getTradeConfig().isSoundEffectsEnabled() && pl.getTradeConfig()
                                    .isSoundOnComplete()) {
                                Sounds.levelUp(player1, 1);
                                Sounds.levelUp(player2, 1);
                                spectatorInv.getViewers()
                                        .stream()
                                        .filter(Player.class::isInstance)
                                        .map(Player.class::cast)
                                        .forEach(p -> Sounds.levelUp(p, 1));
                            }

                            pl.getTradeConfig().getTradeComplete().send(player1, "%PLAYER%", player2.getName());
                            pl.getTradeConfig().getTradeComplete().send(player2, "%PLAYER%", player1.getName());

                            if (pl.getLogs() != null) {
                                try {
                                    TradeLog trade = new TradeLog(player1, player2, combine(accepted1).stream()
                                            .map(ItemFactory::new)
                                            .collect(Collectors.toList()), combine(accepted2).stream()
                                            .map(ItemFactory::new)
                                            .collect(Collectors.toList()), extras.stream()
                                            .filter(e -> e.value1 > 0)
                                            .map(e -> new TradeLog.ExtraOffer(e.name, e.value1))
                                            .collect(Collectors.toList()), extras.stream()
                                            .filter(e -> e.value2 > 0)
                                            .map(e -> new TradeLog.ExtraOffer(e.name, e.value2))
                                            .collect(Collectors.toList()));

                                    TradeCompleteEvent completeEvent = new TradeCompleteEvent(trade, player1, player2);
                                    Bukkit.getPluginManager().callEvent(completeEvent);

                                    pl.getLogs().log(trade);
                                    pl.getLogs().save();
                                }
                                catch (Exception ex) {
                                    pl.log("Failed to save trade log. " + ex.getMessage());
                                }
                            }
                        }
                        else {
                            updateAcceptance();
                        }
                    }
                }, 20L, 20L);
            }
            else {
                if (task != null) {
                    task.cancel();
                    accept1 = false;
                    accept2 = false;
                    task = null;
                    click();
                }
            }
        }
    }

    private Extra getExtra(int slot) {
        return placedExtras.get(slot);
    }

    private int getRight(int slot) {
        return roundUpToNine(slot) - 1 - Integer.remainderUnsigned(slot, 9);
    }

    private int roundUpToNine(int slot) {
        if (slot < 9) {
            return 9;
        }
        if (slot < 18) {
            return 18;
        }
        if (slot < 27) {
            return 27;
        }
        if (slot < 36) {
            return 36;
        }
        if (slot < 45) {
            return 45;
        }
        return 54;
    }

    private ItemStack putOnLeft(Inventory inventory, ItemStack toMove, int amountToMove) {
        int moved = 0;
        for (int slot : InvUtils.leftSlots) {
            ItemStack inInventory = inventory.getItem(slot);
            if (inInventory != null && inInventory.isSimilar(toMove) && inInventory.getAmount() < inInventory.getType()
                    .getMaxStackSize()) {
                while (inInventory.getAmount() < inInventory.getType()
                        .getMaxStackSize() && toMove.getAmount() > 0 && moved++ < amountToMove) {
                    inInventory.setAmount(inInventory.getAmount() + 1);
                    toMove.setAmount(toMove.getAmount() - 1);
                }
                if (toMove.getAmount() <= 0 || moved == amountToMove) {
                    return null;
                }
            }
        }
        for (int slot : InvUtils.leftSlots) {
            ItemStack i = inventory.getItem(slot);
            if (!(i == null || i.getType().equals(Material.AIR))) {
                continue;
            }
            inventory.setItem(slot, toMove);
            toMove = null;
        }
        return toMove;
    }

    private boolean isBlocked(ItemStack item) {
        if (item == null || item.getType().equals(Material.AIR)) {
            return false;
        }
        if (pl.getTradeConfig().isDenyNamedItems() && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return true;
        }
        if (item.hasItemMeta()) {
            String regex = pl.getConfig().getString("blocked.regex", "");
            if (!regex.isEmpty()) {
                try {
                    Pattern pattern = Pattern.compile(regex);
                    if (item.getItemMeta().hasDisplayName()) {
                        String displayName = item.getItemMeta().getDisplayName();
                        if (pattern.matcher(displayName).find()) {
                            return true;
                        }
                    }
                    if (item.getItemMeta().hasLore()) {
                        List<String> lore = item.getItemMeta().getLore();
                        if (lore.stream().anyMatch(s -> pattern.matcher(s).find())) {
                            return true;
                        }
                    }
                }
                catch (PatternSyntaxException ex) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Your blocked.regex is invalid!");
                }
            }
            List<String> blockedLore = pl.getTradeConfig().getLoreBlacklist();
            if (!blockedLore.isEmpty()) {
                for (int i = 0; i < blockedLore.size(); i++) {
                    String line = ChatColor.translateAlternateColorCodes('&', blockedLore.get(i));
                    if (line.length() > 2) {
                        line = line.substring(1, line.length() - 1);
                    }
                    blockedLore.set(i, line);
                }
                if (item.getItemMeta().hasDisplayName()) {
                    String displayName = item.getItemMeta().getDisplayName();
                    if (blockedLore.stream().anyMatch(displayName::contains)) {
                        return true;
                    }
                }
                if (item.getItemMeta().hasLore()) {
                    List<String> lore = item.getItemMeta().getLore();
                    for (String blocked : blockedLore) {
                        for (String line : lore) {
                            if (line.contains(blocked)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        List<String> blocked = pl.getTradeConfig().getItemBlacklist();
        if (blocked.isEmpty()) {
            return false;
        }
        List<String> checks = new ArrayList<>();
        String type = item.getType().toString();
        byte data = item.getData().getData();
        checks.add(type + ":" + data);
        checks.add(type.replace("_", "") + ":" + data);
        checks.add(type.replace("_", " ") + ":" + data);
        try { // Throws exception for materials added after the flattening
            checks.add(item.getType().getId() + ":" + data);
            checks.add(Integer.toString(item.getType().getId()));
        }
        catch (IllegalArgumentException ignored) {
        }
        checks.add(type);
        checks.add(type.replace("_", ""));
        checks.add(type.replace("_", " "));
        for (String block : blocked) {
            for (String check : checks) {
                if (block.equalsIgnoreCase(check)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean similar(ItemStack item1, ItemStack item2) {
        return item1 == item2 || (item1 != null && item1.isSimilar(item2));
    }

    public void open(Player player) {
        if (cancelled) {
            player.closeInventory();
        }
        else {
            if (player1.equals(player)) {
                player.openInventory(inv1);
            }
            else if (player2.equals(player)) {
                player.openInventory(inv2);
            }
        }
    }

    public void setCancelOnClose(Player player, boolean cancelOnClose) {
        if (player1.equals(player)) {
            cancelOnClose1 = cancelOnClose;
        }
        else if (player2.equals(player)) {
            cancelOnClose2 = cancelOnClose;
        }
    }

    private void cancel() {
        for (int leftSlot : InvUtils.leftSlots) {
            int rightSlot = getRight(leftSlot);
            inv1.setItem(rightSlot, pl.getTradeConfig().getSeparator().build());
            inv2.setItem(rightSlot, pl.getTradeConfig().getSeparator().build());
        }
        cancelled = true;
    }

}
