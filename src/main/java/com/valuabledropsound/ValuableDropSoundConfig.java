package com.valuabledropsound;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ValuableDropSoundConfig.GROUP)
public interface ValuableDropSoundConfig extends Config
{
	String GROUP = "valuabledropsound";

	@ConfigItem(
		keyName = "valuableDrops",
		name = "Valuable drops",
		description = "Play for items worth at least the minimum value",
		position = 0
	)
	default boolean valuableDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectionLog",
		name = "Collection log",
		description = "Play for new collection log items",
		position = 1
	)
	default boolean collectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "untradeableDrops",
		name = "Untradeable drops",
		description = "Play for untradeable items",
		position = 2
	)
	default boolean untradeableDrops()
	{
		return false;
	}

	@ConfigItem(
		keyName = "minimumValue",
		name = "Minimum drop value",
		description = "Minimum Grand Exchange value of one item",
		position = 3
	)
	default int minimumValue()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "alwaysPlay",
		name = "Always play for",
		description = "Items that always play it, comma separated. * is a wildcard",
		position = 4
	)
	default String alwaysPlay()
	{
		return "";
	}

	@ConfigItem(
		keyName = "neverPlay",
		name = "Never play for",
		description = "Items that never play it, comma separated. * is a wildcard",
		position = 5
	)
	default String neverPlay()
	{
		return "";
	}
}
