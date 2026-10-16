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
		description = "Items worth at least the minimum",
		position = 0
	)
	default boolean valuableDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectionLog",
		name = "Collection log",
		description = "New collection log items",
		position = 1
	)
	default boolean collectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "untradeableDrops",
		name = "Untradeable drops",
		description = "Untradeable items",
		position = 2
	)
	default boolean untradeableDrops()
	{
		return false;
	}

	@ConfigItem(
		keyName = "groundItemsHighlights",
		name = "Highlighted items",
		description = "Items highlighted in Ground Items",
		position = 3
	)
	default boolean groundItemsHighlights()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minimumValue",
		name = "Minimum value",
		description = "GE value of one item",
		position = 4
	)
	default int minimumValue()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "alwaysPlay",
		name = "Always play for",
		description = "Names, comma separated. * is a wildcard",
		position = 5
	)
	default String alwaysPlay()
	{
		return "";
	}

	@ConfigItem(
		keyName = "neverPlay",
		name = "Never play for",
		description = "Names, comma separated. * is a wildcard",
		position = 6
	)
	default String neverPlay()
	{
		return "";
	}
}
