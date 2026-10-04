package com.valuabledropsound;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@PluginDescriptor(
	name = "Valuable Drop Sound",
	description = "Plays the game's own unique drop sound for valuable drops and new collection log items",
	tags = {"loot", "drop", "sound", "valuable", "unique", "dt2", "collection", "log", "clog", "chest", "untradeable"}
)
public class ValuableDropSoundPlugin extends Plugin
{
	/**
	 * The sound the Desert Treasure II bosses play for a unique. It was made for the Phantom Muspah and
	 * the game reuses it for Araxxor, Yama and others, so it is the game's own sound for a big drop.
	 */
	static final int UNIQUE_DROP_SOUND = 6765;

	/**
	 * Monsters whose uniques already come with the sound, going by the wiki. Anything they drop is left
	 * to the game, so the player never hears it twice.
	 */
	private static final Set<String> NATIVE_BOSSES = ImmutableSet.of(
		"vardorvis",
		"duke sucellus",
		"the leviathan",
		"the whisperer",
		"phantom muspah",
		"araxxor",
		"yama",
		"amoxliatl",
		"shellbane gryphon",
		"brutus"
	);

	/**
	 * Monsters that play the sound for some of what they drop rather than for all of it, and the drops
	 * they play it for. A kill that hands one of these over is left to the game, and everything else
	 * they drop still counts.
	 */
	private static final Map<String, Set<String>> NATIVE_UNIQUES = ImmutableMap.of(
		"frost dragon", ImmutableSet.of(
			"dragon metal sheet",
			"draconic visage"),
		"nex", ImmutableSet.of(
			"ancient hilt",
			"nihil horn",
			"torva full helm (damaged)",
			"torva platebody (damaged)",
			"torva platelegs (damaged)",
			"zaryte vambraces")
	);

	/**
	 * The Lunar Chest for the Moons of Peril plays it for its uniques.
	 */
	private static final String LUNAR_CHEST = "lunar chest";
	private static final int LUNAR_CHEST_REGION = 6037;

	private static final String COLLECTION_LOG_MESSAGE = "New item added to your collection log:";
	private static final String COLLECTION_LOG_TITLE = "Collection log";

	/**
	 * How many ticks the sound covers. Anything else that would play it within this window is the same
	 * moment, such as a drop that is also new to the collection log, or a pop-up that shows a little
	 * after the chat message for the same item.
	 */
	private static final int SOUND_TICKS = 2;

	private static final int NONE = -1;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ValuableDropSoundConfig config;

	// Changed from the settings panel's thread and read on the client's
	private volatile List<Pattern> alwaysPlay = new ArrayList<>();
	private volatile List<Pattern> neverPlay = new ArrayList<>();

	/**
	 * The last tick loot came from somewhere that plays the sound itself, so a collection log entry
	 * for it stays quiet too.
	 */
	private int nativeTick = NONE;

	private int playedTick = NONE;

	/**
	 * New collection log items, by name. One can be announced a moment before the loot it came from,
	 * within the same frame, so they are held until the end of that frame, far too short to hear.
	 */
	private final List<String> collectionLogItems = new ArrayList<>();

	/**
	 * An item is only ever new to the collection log once, so a second announcement of it, from the
	 * pop-up after the chat message, is the same one. Items from loot that was already announced with
	 * the sound, by this plugin or the game, go in here too, so their pop-up cannot play it again.
	 */
	private final Set<String> announcedItems = new HashSet<>();

	/**
	 * The pop-up's text is only filled in once it starts showing, so starting is noted and the text is
	 * read when it gets there.
	 */
	private boolean popupStarted;

	private boolean playing;

	@Provides
	ValuableDropSoundConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ValuableDropSoundConfig.class);
	}

	@Override
	protected void startUp()
	{
		loadItemLists();
	}

	@Override
	protected void shutDown()
	{
		nativeTick = NONE;
		playedTick = NONE;
		collectionLogItems.clear();
		announcedItems.clear();
		popupStarted = false;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (ValuableDropSoundConfig.GROUP.equals(event.getGroup()))
		{
			loadItemLists();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Another account has its own collection log
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			announcedItems.clear();
		}
	}

	/**
	 * The server reports every monster's loot here, which is also what the Loot Tracker plugin uses.
	 */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		NPCComposition npc = event.getComposition();
		onLoot(npc == null ? null : npc.getName(), event.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		onLoot(null, event.getItems());
	}

	/**
	 * Chests, caskets and raid rewards are only reported by the Loot Tracker plugin. It passes monster
	 * loot on too, but that is already handled above.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() == LootRecordType.EVENT)
		{
			onLoot(event.getName(), event.getItems());
		}
	}

	/**
	 * The game can announce a new collection log item as a chat message, as a pop-up, or both,
	 * depending on the player's settings. Either one counts.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		if (message.startsWith(COLLECTION_LOG_MESSAGE))
		{
			collectionLogItems.add(message.substring(COLLECTION_LOG_MESSAGE.length()).trim());
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.NOTIFICATION_START)
		{
			popupStarted = true;
		}
		else if (event.getScriptId() == ScriptID.NOTIFICATION_DELAY && popupStarted)
		{
			popupStarted = false;

			String title = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
			if (title != null && COLLECTION_LOG_TITLE.equalsIgnoreCase(Text.removeTags(title)))
			{
				// The item's name is on its own line, below a line saying it is new
				String text = client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN);
				String[] lines = text == null ? new String[0] : text.split("<br>");
				collectionLogItems.add(lines.length == 0 ? "" : Text.removeTags(lines[lines.length - 1]).trim());
			}
		}
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		onGameSound(event.getSoundId());
	}

	/**
	 * The game can also play the sound from a spot in the world, such as the tile the loot landed on,
	 * and that arrives as an area sound rather than a plain one. Either way it is the same sound.
	 */
	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		onGameSound(event.getSoundId());
	}

	/**
	 * A place missing from the lists above still does not play it twice, as long as the game got there
	 * first.
	 */
	private void onGameSound(int soundId)
	{
		if (soundId == UNIQUE_DROP_SOUND && !playing)
		{
			nativeTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (collectionLogItems.isEmpty())
		{
			return;
		}

		boolean playIt = false;

		for (String item : collectionLogItems)
		{
			// An empty name is a pop-up that could not be read, which cannot be told apart, so it counts
			boolean isNew = item.isEmpty() || announcedItems.add(item);
			if (isNew && !matches(neverPlay, item) && (config.collectionLog() || matches(alwaysPlay, item)))
			{
				playIt = true;
			}
		}

		collectionLogItems.clear();

		if (playIt && !nativeNearby())
		{
			play();
		}
	}

	private void onLoot(String sourceName, Collection<ItemStack> items)
	{
		String source = sourceName == null ? "" : lowerCase(Text.removeTags(sourceName));

		if (NATIVE_BOSSES.contains(source)
			|| source.equals(LUNAR_CHEST)
			|| hasNativeUnique(source, items))
		{
			nativeTick = client.getTickCount();
			markAnnounced(items);
			return;
		}

		if (nativeRecently())
		{
			markAnnounced(items);
			return;
		}

		boolean valuable = config.valuableDrops();
		boolean untradeable = config.untradeableDrops();
		long minimum = config.minimumValue();

		for (ItemStack item : items)
		{
			ItemComposition composition = itemManager.getItemComposition(item.getId());
			String name = composition.getName();

			if (matches(neverPlay, name))
			{
				continue;
			}

			// A long, because a stack of something expensive can be worth more than an int holds
			if (matches(alwaysPlay, name)
				|| (untradeable && !composition.isTradeable())
				|| (valuable && (long) itemManager.getItemPrice(item.getId()) * item.getQuantity() >= minimum))
			{
				play();
				markAnnounced(items);
				return;
			}
		}
	}

	private void markAnnounced(Collection<ItemStack> items)
	{
		for (ItemStack item : items)
		{
			announcedItems.add(itemManager.getItemComposition(item.getId()).getName());
		}
	}

	private static String lowerCase(String text)
	{
		return text.toLowerCase(Locale.ROOT);
	}

	/**
	 * Whether a kill handed over one of the drops its monster plays the sound for itself.
	 */
	private boolean hasNativeUnique(String source, Collection<ItemStack> items)
	{
		Set<String> uniques = NATIVE_UNIQUES.get(source);
		if (uniques == null)
		{
			return false;
		}

		for (ItemStack item : items)
		{
			String name = itemManager.getItemComposition(item.getId()).getName();
			if (uniques.contains(lowerCase(name)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the game has just played the sound itself. The loot it played for can be reported a tick
	 * or two either side of it, so this is a window rather than the one tick it landed on, or the same
	 * drop gets announced a second time about a second later.
	 */
	private boolean nativeRecently()
	{
		return nativeTick != NONE && nativeTick >= client.getTickCount() - SOUND_TICKS;
	}

	/**
	 * Whether a collection log entry most likely came from somewhere that already played the sound.
	 */
	private boolean nativeNearby()
	{
		if (nativeRecently())
		{
			return true;
		}

		Player player = client.getLocalPlayer();
		if (player != null && player.getWorldLocation().getRegionID() == LUNAR_CHEST_REGION)
		{
			return true;
		}

		// Loot a boss hands out can be reported a little after its collection log entry
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			String name = npc.getName();
			if (name != null && NATIVE_BOSSES.contains(lowerCase(Text.removeTags(name))))
			{
				return true;
			}
		}

		return false;
	}

	private void play()
	{
		int tick = client.getTickCount();
		if (playedTick != NONE && playedTick >= tick - SOUND_TICKS)
		{
			return;
		}

		playedTick = tick;
		playing = true;
		try
		{
			client.playSoundEffect(UNIQUE_DROP_SOUND);
		}
		finally
		{
			playing = false;
		}
	}

	/**
	 * The lists are turned into patterns once, when they change, rather than for every item dropped.
	 */
	private void loadItemLists()
	{
		alwaysPlay = toPatterns(config.alwaysPlay());
		neverPlay = toPatterns(config.neverPlay());
	}

	/**
	 * Turns a comma separated list of item names into patterns, where * matches any text.
	 */
	private static List<Pattern> toPatterns(String list)
	{
		List<Pattern> patterns = new ArrayList<>();
		for (String name : Text.fromCSV(list))
		{
			StringBuilder regex = new StringBuilder();
			for (String part : name.split("\\*", -1))
			{
				if (regex.length() > 0)
				{
					regex.append(".*");
				}
				if (!part.isEmpty())
				{
					regex.append(Pattern.quote(part));
				}
			}
			patterns.add(Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE));
		}
		return patterns;
	}

	private static boolean matches(List<Pattern> patterns, String itemName)
	{
		for (Pattern pattern : patterns)
		{
			if (pattern.matcher(itemName).matches())
			{
				return true;
			}
		}
		return false;
	}
}
