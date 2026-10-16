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
import net.runelite.client.plugins.grounditems.GroundItemsConfig;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;
import net.runelite.http.api.loottracker.LootRecordType;

@PluginDescriptor(
	name = "Valuable Drop Sound",
	description = "Plays the game's own unique drop sound for valuable drops and new collection log items",
	tags = {"loot", "drop", "sound", "valuable", "unique", "dt2", "collection", "log", "clog", "chest", "untradeable",
		"ground", "items", "highlight"}
)
public class ValuableDropSoundPlugin extends Plugin
{
	/** The sound the Desert Treasure II bosses, Araxxor, Yama and others play for a unique. */
	static final int UNIQUE_DROP_SOUND = 6765;

	/** Monsters that play the sound themselves, going by the wiki. Their drops are left to the game. */
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

	/** Monsters that play it for some of their drops only, and which drops those are. */
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

	/** The Lunar Chest plays it for its uniques. */
	private static final String LUNAR_CHEST = "lunar chest";
	private static final int LUNAR_CHEST_REGION = 6037;

	private static final String COLLECTION_LOG_MESSAGE = "New item added to your collection log:";
	private static final String COLLECTION_LOG_TITLE = "Collection log";

	/** How many ticks the sound covers, so one drop cannot play it twice. */
	private static final int SOUND_TICKS = 2;

	/** How long the game playing it keeps this one quiet, in ticks, since loot is reported around it. */
	private static final int GRACE_TICKS = 5;

	private static final int NONE = -1;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ValuableDropSoundConfig config;

	@Inject
	private ConfigManager configManager;

	/** The Ground Items plugin's settings, for its highlighted and hidden lists. */
	private GroundItemsConfig groundItems;

	// Changed from the settings panel's thread and read on the client's
	private volatile List<Pattern> alwaysPlay = new ArrayList<>();
	private volatile List<Pattern> neverPlay = new ArrayList<>();
	private volatile List<Entry> highlighted = new ArrayList<>();
	private volatile List<Entry> hidden = new ArrayList<>();

	/** The last tick the game played it itself, or loot came from somewhere that does. */
	private int nativeTick = NONE;

	private int playedTick = NONE;

	/** New collection log items, held to the end of the frame, since loot can be reported after one. */
	private final List<String> collectionLogItems = new ArrayList<>();

	/** Items already announced, so a pop-up after the chat message for one cannot play it again. */
	private final Set<String> announcedItems = new HashSet<>();

	/** The pop-up's text is only filled in once it starts, so the start is noted and read later. */
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
		groundItems = configManager.getConfig(GroundItemsConfig.class);
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
		if (ValuableDropSoundConfig.GROUP.equals(event.getGroup())
			|| GroundItemsConfig.GROUP.equals(event.getGroup()))
		{
			loadItemLists();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Another account has its own collection log, and the tick count starts over
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			announcedItems.clear();
			nativeTick = NONE;
			playedTick = NONE;
		}
	}

	/** The server reports every monster's loot here. */
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

	/** Chests, caskets and raid rewards are only reported by the Loot Tracker plugin. */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() == LootRecordType.EVENT)
		{
			onLoot(event.getName(), event.getItems());
		}
	}

	/** A new collection log item comes as a chat message, a pop-up, or both. Either one counts. */
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

	/** The game can also play it from a tile, which arrives as an area sound. Same sound. */
	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		onGameSound(event.getSoundId());
	}

	/** A place missing from the lists above still cannot play it twice, if the game got there first. */
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
		boolean highlights = config.groundItemsHighlights();
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
				|| (highlights && isHighlighted(name, item.getQuantity()))
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

	/** Whether a kill handed over one of the drops its monster plays the sound for itself. */
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
	 * Whether the game played it recently, so anything announced now is most likely that drop. A tick
	 * ahead of the count is from before a login set the count back, and is stale.
	 */
	private boolean nativeRecently()
	{
		int tick = client.getTickCount();
		return nativeTick != NONE && nativeTick <= tick && nativeTick >= tick - GRACE_TICKS;
	}

	/** Whether a collection log entry most likely came from somewhere that already played it. */
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

	/** The lists are turned into patterns when they change, not for every item dropped. */
	private void loadItemLists()
	{
		alwaysPlay = toPatterns(config.alwaysPlay());
		neverPlay = toPatterns(config.neverPlay());
		highlighted = toEntries(groundItems == null ? "" : groundItems.getHighlightItems());
		hidden = toEntries(groundItems == null ? "" : groundItems.getHiddenItems());
	}

	/** Whether Ground Items highlights this item. A name beats a *, a highlight beats a hide. */
	private boolean isHighlighted(String itemName, int quantity)
	{
		return listed(highlighted, itemName, quantity, false)
			|| (!listed(hidden, itemName, quantity, false) && listed(highlighted, itemName, quantity, true));
	}

	private static boolean listed(List<Entry> entries, String itemName, int quantity, boolean wildcards)
	{
		for (Entry entry : entries)
		{
			if (entry.wildcard == wildcards && entry.matches(itemName, quantity))
			{
				return true;
			}
		}
		return false;
	}

	/** An entry from a Ground Items list: a name, maybe with a *, and a quantity like "coins > 10000". */
	private static final class Entry
	{
		private final String name;
		private final int quantity;
		private final boolean lessThan;
		private final boolean wildcard;

		private Entry(String name, int quantity, boolean lessThan, boolean wildcard)
		{
			this.name = name;
			this.quantity = quantity;
			this.lessThan = lessThan;
			this.wildcard = wildcard;
		}

		private boolean matches(String itemName, int itemQuantity)
		{
			boolean named = wildcard ? WildcardMatcher.matches(name, itemName) : name.equalsIgnoreCase(itemName);
			return named && (lessThan ? itemQuantity < quantity : itemQuantity > quantity);
		}

		/** The quantity follows a &lt; or &gt;, and goes with the sign when it is not a number. */
		private static Entry of(String text)
		{
			boolean wildcard = text.indexOf('*') >= 0;
			int quantity = 0;
			boolean lessThan = false;

			int i = text.length() - 1;
			while (i >= 0 && (Character.isDigit(text.charAt(i)) || Character.isWhitespace(text.charAt(i))))
			{
				i--;
			}

			char sign = i < 0 ? 0 : text.charAt(i);
			if ((sign == '<' || sign == '>') && i + 1 < text.length())
			{
				try
				{
					quantity = Integer.parseInt(text.substring(i + 1).trim());
					lessThan = sign == '<';
				}
				catch (NumberFormatException ignored)
				{
					quantity = 0;
				}
				text = text.substring(0, i);
			}

			return new Entry(text.trim(), quantity, lessThan, wildcard);
		}
	}

	private static List<Entry> toEntries(String list)
	{
		List<Entry> entries = new ArrayList<>();
		for (String name : Text.fromCSV(list))
		{
			if (!name.isEmpty())
			{
				entries.add(Entry.of(name));
			}
		}
		return entries;
	}

	/** Turns a comma separated list of item names into patterns, where * matches any text. */
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
