package com.trophonix.tradeplus.commands;

import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.bukkit.command.CommandSender;

public abstract class Command {

	@Getter
	private List<String> aliases;

	Command(List<String> aliases) {
		this.aliases = aliases;
	}

	public boolean isAlias(String command) {
		return aliases.contains(command.toLowerCase());
	}

	public abstract void onCommand(CommandSender sender, String[] args);

	public List<String> onTabComplete(CommandSender sender, String[] args, String full) {
		return Collections.emptyList();
	}

}
