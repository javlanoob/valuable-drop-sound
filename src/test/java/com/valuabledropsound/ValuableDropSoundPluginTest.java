package com.valuabledropsound;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ValuableDropSoundPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ValuableDropSoundPlugin.class);
		RuneLite.main(args);
	}
}
