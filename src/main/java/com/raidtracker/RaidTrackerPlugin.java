package com.raidtracker;

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Inject;
import com.raidtracker.filereadwriter.FileReadWriter;
import com.raidtracker.ui.RaidTrackerPanel;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.game.ItemManager;
import net.runelite.api.widgets.WidgetUtil;
import com.raidtracker.toapointstracker.pointstracker.PointsTracker;
import com.raidtracker.toapointstracker.module.ComponentManager;
import com.raidtracker.toapointstracker.module.TombsOfAmascutModule;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Integer.parseInt;
import static java.lang.Float.parseFloat;
import net.runelite.http.api.item.ItemPrice;

@Slf4j
@PluginDescriptor(
	name = "Raid Data Tracker"
)
public class RaidTrackerPlugin extends Plugin
{
	private static final String LEVEL_COMPLETE_MESSAGE = "complete! Duration:";
	private static final String RAID_COMPLETE_MESSAGE_COX_TOB = "Congratulations - your raid is complete!";
	private static final String RAID_COMPLETE_MESSAGE_TOA = "Challenge complete: The Wardens.";
	private static final String DUST_RECIPIENTS = "Dust recipients: ";
	private static final String TWISTED_KIT_RECIPIENTS = "Twisted Kit recipients: ";

	private static final Pattern TOA_ROOM_COMPLETE_PATTERN = Pattern.compile("Challenge complete: ([A-Za-z- ]+).*Duration:.*?([0-9]+:[0-9]+\\.?[0-9]+)(?:\\. )?.*");
	private static final Pattern TOA_COMPLETION_PATTERN = Pattern.compile(".*Tombs of Amascut: (.*) Mode (total|challenge) completion time:.*?([0-9]+:[.0-9]+)\\..*");

	private static final String TOA_EVENT_NAMESPACE = "tombs-of-amascut";
	private static final String TOA_EVENT_NAME_POINTS = "raidCompletedPoints";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private RaidTrackerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private RaidTracker raidTracker;

	@Inject
	private PointsTracker pointsTracker;

	private ComponentManager componentManager = null;

	@Inject
	private FileReadWriter fw;
	private boolean writerStarted = false;
	private boolean raidStarted = false;

	private static final WorldPoint TEMP_LOCATION = new WorldPoint(3360, 5152, 2);

	@Setter
	private RaidTrackerPanel panel;
	private NavigationButton navButton;


	@Provides
	RaidTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RaidTrackerConfig.class);
	}

	@Override
	public void configure(Binder binder)
	{
		binder.install(new TombsOfAmascutModule());
	}

	@Override
	protected void startUp() {
		panel = new RaidTrackerPanel(itemManager, fw, config, clientThread, client);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel-icon.png");

		navButton = NavigationButton.builder()
				.tooltip("Raid Data Tracker")
				.priority(6)
				.icon(icon)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);

		if (componentManager == null)
		{
			componentManager = injector.getInstance(ComponentManager.class);
		}
		componentManager.onPluginStart();

		if (client.getGameState().equals(GameState.LOGGED_IN) || client.getGameState().equals(GameState.LOADING))
		{
			fw.updateUsername(client.getUsername());
			SwingUtilities.invokeLater(() -> panel.loadRTList());
		}
	}

	@Override
	protected void shutDown() {
		raidTracker.setInRaidChambers(false);
		clientToolbar.removeNavigation(navButton);
		reset();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!e.getGroup().equals(RaidTrackerConfig.CONFIG_GROUP))
		{
			return;
		}
		panel.loadRTList();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (WorldUtils.playerOnBetaWorld(client)) {
			return;
		}

		boolean tempInRaid = client.getVarbitValue(Varbits.IN_RAID) == 1;
		boolean tempInTob = client.getVarbitValue(Varbits.THEATRE_OF_BLOOD) > 1;
		boolean tempInToa = client.getVarbitValue(Varbits.TOA_RAID_LEVEL) > 0;

		// if the player's raid state has changed
		if (tempInRaid ^ raidTracker.isInRaidChambers()) {
			// if the player is inside of a raid then check the raid
			if (tempInRaid && raidTracker.isLoggedIn()) {
				checkRaidPresence();
			}
			else if (raidTracker.isRaidComplete() && !raidTracker.isChestOpened()) {
				//player just exited a raid, if the chest is not looted write the raid tracker anyway.
				//might deprecate the writing after widgetloaded in the future, not decided yet.
				if (writerStarted) {
					return;
				}

				fw.writeToFile(raidTracker);

				writerStarted = true;

				SwingUtilities.invokeLater(() -> {
					panel.addDrop(raidTracker);
					reset();
				});
			}
		}

		if (tempInTob ^ raidTracker.isInTheatreOfBlood()) {
			if (tempInTob && raidTracker.isLoggedIn()) {
				checkTobPresence();
			}
			else if (raidTracker.isRaidComplete()) {
				//not tested

				if (writerStarted) {
					return;
				}

				fw.writeToFile(raidTracker);

				writerStarted = true;

				SwingUtilities.invokeLater(() -> {
					panel.addDrop(raidTracker);
					reset();
				});
			}
			else {
				reset();
			}
		}

		if (tempInToa ^ raidTracker.isInTombsOfAmascut()) {
			if (tempInToa && raidTracker.isLoggedIn()) {
				checkToaPresence();
			}
			else if (raidTracker.isRaidComplete()) {
				//not tested

				if (writerStarted) {
					return;
				}

				fw.writeToFile(raidTracker);

				writerStarted = true;

				SwingUtilities.invokeLater(() -> {
					panel.addDrop(raidTracker);
					reset();
				});
			}
			else {
				reset();
			}
		}

		if (raidTracker.isInTombsOfAmascut()) {
			if (event.getVarbitId() >= Varbits.TOA_MEMBER_0_HEALTH && event.getVarbitId() <= Varbits.TOA_MEMBER_7_HEALTH && event.getValue() == 30) {
				int toa_member = event.getVarbitId() - Varbits.TOA_MEMBER_0_HEALTH;
				if (toa_member == 0) {
					raidTracker.personalDeathCount++;
				}

				raidTracker.totalTeamDeathCount++;
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (WorldUtils.playerOnBetaWorld(client)) {
			return;
		}

		if (event.getGameState() == GameState.LOGGING_IN)
		{
			fw.updateUsername(client.getUsername());
			SwingUtilities.invokeLater(() -> panel.loadRTList());

		}

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// skip event while the game decides if the player belongs in a raid or not
			if (client.getLocalPlayer() == null
					|| client.getLocalPlayer().getWorldLocation().equals(TEMP_LOCATION))
			{
				//noinspection UnnecessaryReturnStatement
				return;
			}
		}
		else if (client.getGameState() == GameState.LOGIN_SCREEN
				|| client.getGameState() == GameState.CONNECTION_LOST)
		{
			raidTracker.setLoggedIn(false);
		}
		else if (client.getGameState() == GameState.HOPPING)
		{
			reset();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		if (WorldUtils.playerOnBetaWorld(client)) {
			return;
		}

		checkChatMessage(event, raidTracker);
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		int WIDGET_TIMER = WidgetUtil.packComponentId(481, 46);
		if (raidTracker.isInTombsOfAmascut() && client.getWidget(WIDGET_TIMER) != null) {
			if (!Objects.equals(Objects.requireNonNull(client.getWidget(WIDGET_TIMER)).getText(), "00:00") && !Objects.equals(Objects.requireNonNull(client.getWidget(WIDGET_TIMER)).getText(), "0:00.00") && !raidStarted) {
				raidStarted = true;
				raidTracker.setTeamSize(pointsTracker.getTeamSize());
				raidTracker.setRaidLevel(client.getVarbitValue(Varbits.TOA_RAID_LEVEL));
			}
		}
	}

	// Thank Adam, LlemonDuck, and jocopa3 for creating PluginMessage event in core for us
	@Subscribe
	public void onPluginMessage(PluginMessage message)
	{
		if (message.getNamespace().equals(TOA_EVENT_NAMESPACE)
			&& message.getName().equals(TOA_EVENT_NAME_POINTS)
			&& (Integer) message.getData().get("version") == 1)
		{

			log.info("received PluginMessage from Tombs of Amascut plugin");
			int personalPoints = (Integer) message.getData().get("personalPoints");
			int totalPoints = (Integer) message.getData().get("totalPoints");

			raidTracker.setPersonalPoints(personalPoints);
			raidTracker.setTotalPoints(totalPoints);
			raidTracker.setPercentage(raidTracker.getPersonalPoints() / (raidTracker.getTotalPoints() / 100.0));
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (WorldUtils.playerOnBetaWorld(client)) {
			return;
		}

		ItemContainer rewardItemContainer;
		switch (event.getGroupId()) {
			case (InterfaceID.CHAMBERS_OF_XERIC_REWARD):
				if (raidTracker.isChestOpened() || !raidTracker.isRaidComplete()) {
					return;
				}

				raidTracker.setChestOpened(true);

				rewardItemContainer = client.getItemContainer(InventoryID.CHAMBERS_OF_XERIC_CHEST);

				if (rewardItemContainer == null) {
					return;
				}

				if (writerStarted) {
					//unnecessary return check?
					return;
				}

				raidTracker.setLootList(lootListFactory(rewardItemContainer.getItems()));

				fw.writeToFile(raidTracker);

				writerStarted = true;

				SwingUtilities.invokeLater(() -> {
					panel.addDrop(raidTracker);
					reset();
				});

				break;

			case (InterfaceID.TOB):
				if (raidTracker.isChestOpened() || !raidTracker.isRaidComplete()) {
					return;
				}


				raidTracker.setChestOpened(true);

				rewardItemContainer = client.getItemContainer(InventoryID.THEATRE_OF_BLOOD_CHEST);

				if (rewardItemContainer == null) {
					return;
				}

				raidTracker.setLootList(lootListFactory(rewardItemContainer.getItems()));

				break;
			//459 is the mvp screen of TOB
			case (459):
				AtomicReference<String> mvp = new AtomicReference<>("");
				AtomicReference<String> player1 = new AtomicReference<>("");
				AtomicReference<String> player2 = new AtomicReference<>("");
				AtomicReference<String> player3 = new AtomicReference<>("");
				AtomicReference<String> player4 = new AtomicReference<>("");
				AtomicReference<String> player5 = new AtomicReference<>("");
				AtomicInteger deathsPlayer1 = new AtomicInteger();
				AtomicInteger deathsPlayer2 = new AtomicInteger();
				AtomicInteger deathsPlayer3 = new AtomicInteger();
				AtomicInteger deathsPlayer4 = new AtomicInteger();
				AtomicInteger deathsPlayer5 = new AtomicInteger();

				clientThread.invokeLater(() -> {
					mvp.set(getWidgetText(client.getWidget(459, 14)));
					player1.set(getWidgetText(client.getWidget(459, 22)));
					player2.set(getWidgetText(client.getWidget(459, 24)));
					player3.set(getWidgetText(client.getWidget(459, 26)));
					player4.set(getWidgetText(client.getWidget(459, 28)));
					player5.set(getWidgetText(client.getWidget(459, 30)));
					deathsPlayer1.set(getWidgetNumber(client.getWidget(459, 23)));
					deathsPlayer2.set(getWidgetNumber(client.getWidget(459, 25)));
					deathsPlayer3.set(getWidgetNumber(client.getWidget(459, 27)));
					deathsPlayer4.set(getWidgetNumber(client.getWidget(459, 29)));
					deathsPlayer5.set(getWidgetNumber(client.getWidget(459, 31)));

					raidTracker.setMvp(mvp.get());
					raidTracker.setTobPlayer1(player1.get());
					raidTracker.setTobPlayer2(player2.get());
					raidTracker.setTobPlayer3(player3.get());
					raidTracker.setTobPlayer4(player4.get());
					raidTracker.setTobPlayer5(player5.get());

					raidTracker.setTobPlayer1DeathCount(deathsPlayer1.get());
					raidTracker.setTobPlayer2DeathCount(deathsPlayer2.get());
					raidTracker.setTobPlayer3DeathCount(deathsPlayer3.get());
					raidTracker.setTobPlayer4DeathCount(deathsPlayer4.get());
					raidTracker.setTobPlayer5DeathCount(deathsPlayer5.get());

					if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null) {
						raidTracker.setMvpInOwnName(mvp.get().equalsIgnoreCase(client.getLocalPlayer().getName()));
					}
				});
				break;

			case (InterfaceID.TOA_REWARD):
				if (raidTracker.isChestOpened() || !raidTracker.isRaidComplete()) {
					return;
				}

				raidTracker.setChestOpened(true);

				rewardItemContainer = client.getItemContainer(InventoryID.TOA_REWARD_CHEST);

				if (rewardItemContainer == null) {
					return;
				}

				raidTracker.setLootList(lootListFactory(rewardItemContainer.getItems()));

				fw.writeToFile(raidTracker);

				writerStarted = true;

				SwingUtilities.invokeLater(() -> {
					panel.addDrop(raidTracker);
					reset();
				});
				break;
		}
	}

	private String getWidgetText(Widget widget) {
		if (widget == null) {
			return "";
		}
		else if (widget.getText().equals("-")) {
			return "";
		}
		return widget.getText();
	}

	private int getWidgetNumber(Widget widget) {
		if (widget == null) {
			return 0;
		}
		else if (widget.getText().equals("-")) {
			return 0;
		}
		return Integer.parseInt(widget.getText());
	}

	public void checkChatMessage(ChatMessage event, RaidTracker raidTracker)
	{
		raidTracker.setLoggedIn(true);

		String playerName = "";

		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null) {
			playerName = client.getLocalPlayer().getName();
		}

		if ((raidTracker.isInRaidChambers() || raidTracker.isInTheatreOfBlood() || raidTracker.isInTombsOfAmascut()) &&
			(event.getType() == ChatMessageType.FRIENDSCHATNOTIFICATION || event.getType() == ChatMessageType.GAMEMESSAGE)) {
			//unescape java to avoid unicode
			String message = unescapeJavaString(Text.removeTags(event.getMessage()));

			// Fixes issue with inconsistent resets due to
			// Varbits.TOA_RAID_LEVEL not resetting when you leave
			if (message.contains("You enter the Tombs of Amascut")) {
				reset();
			}

			if (message.contains(LEVEL_COMPLETE_MESSAGE)) {
				String timeString = message.split("complete! Duration: ")[1];

				if (message.startsWith("Upper")) {
					raidTracker.setUpperTime(stringTimeToSeconds(timeString.split(" ")[timeString.split(" ").length - 1]));
				}
				if (message.startsWith("Middle")) {
					raidTracker.setMiddleTime(stringTimeToSeconds(timeString.split(" ")[timeString.split(" ").length - 1]));
				}
				if (message.startsWith("Lower")) {
					raidTracker.setLowerTime(stringTimeToSeconds(timeString.split(" ")[timeString.split(" ").length - 1]));
				}

				if (message.toLowerCase().contains("shamans")) {
					raidTracker.setShamansTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("vasa")) {
					raidTracker.setVasaTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("vanguards")) {
					raidTracker.setVanguardsTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("mystics")) {
					raidTracker.setMysticsTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("tekton")) {
					raidTracker.setTektonTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("muttadiles")) {
					raidTracker.setMuttadilesTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("vespula")) {
					raidTracker.setVespulaTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("ice demon")) {
					raidTracker.setIceDemonTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("thieving")) {
					raidTracker.setThievingTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("tightrope")) {
					raidTracker.setTightropeTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

				if (message.toLowerCase().contains("crabs")) {
					raidTracker.setCrabsTime(stringTimeToSeconds(timeString.split(" ")[0]));
				}

			}

			if (message.startsWith(RAID_COMPLETE_MESSAGE_COX_TOB) || message.startsWith(RAID_COMPLETE_MESSAGE_TOA)) {

				if (raidTracker.isInRaidChambers()) {
					raidTracker.setTotalPoints(client.getVarbitValue(Varbits.TOTAL_POINTS));
					raidTracker.setPersonalPoints(client.getVarbitValue(Varbits.PERSONAL_POINTS));
					raidTracker.setTeamSize(client.getVarbitValue(Varbits.RAID_PARTY_SIZE));
				} else if (raidTracker.isInTombsOfAmascut()) {
					raidTracker.setPersonalPoints(pointsTracker.getPersonalTotalPoints());
				}


				raidTracker.setPercentage(raidTracker.getPersonalPoints() / (raidTracker.getTotalPoints() / 100.0));

				// Points being 0 (for any reason) causes the % to be NaN and cause a log writing error
				if (Double.isNaN(raidTracker.getPercentage())) {
					raidTracker.setTotalPoints(-1);
					raidTracker.setPersonalPoints(-1);
					raidTracker.setPercentage(-1);
				}

				// Without TOA party point tracking, the points percentage can't be calculated properly
				if (raidTracker.getPercentage() < 0 && raidTracker.getPercentage() != -1) {
					raidTracker.setPercentage(-1);
				}

				raidTracker.setRaidComplete(true);

				raidTracker.setDate(System.currentTimeMillis());
			}

			//the message after every completed tob wave.
			if (message.toLowerCase().contains("wave '")) {
				String wave = message.toLowerCase().split("'")[1];

				switch (wave) {
					case("the maiden of sugadinti"):
						raidTracker.setMaidenTime(stringTimeToSeconds(message.toLowerCase().split("duration: ")[1].split(" total")[0]));
						break;
					case("the pestilent bloat"):
						raidTracker.setBloatTime(stringTimeToSeconds(message.toLowerCase().split("duration: ")[1].split(" total")[0]));
						break;
					case("the nylocas"):
						raidTracker.setNyloTime(stringTimeToSeconds(message.toLowerCase().split("duration: ")[1].split(" total")[0]));
						break;
					case("sotetseg"):
						raidTracker.setSotetsegTime(stringTimeToSeconds(message.toLowerCase().split("duration: ")[1].split(" total")[0]));
						break;
					case("xarpus"):
						raidTracker.setXarpusTime(stringTimeToSeconds(message.toLowerCase().split("duration: ")[1].split(" total")[0]));
						break;
					case("the final challenge"):
						raidTracker.setVerzikTime(stringTimeToSeconds(message.toLowerCase().split("duration: ")[1].split("theatre")[0]));
						break;
				}
			}

			if (message.contains("Challenge complete") || message.contains("total completion")) {

				Matcher m;
				if ((m = TOA_ROOM_COMPLETE_PATTERN.matcher(message)).matches())
				{
					String room = m.group(1).toLowerCase();
					int duration = stringTimeToSeconds(m.group(2));
					if (room == null || room.isEmpty())
					{
						log.warn("Failed to find room {} for completion string {}", m.group(1), event.getMessage());
						return;
					}

					switch (room)
					{
						case "path of crondis":
							raidTracker.setCrondisTime(duration);
							break;
						case "zebak":
							raidTracker.setZebakTime(duration);
							break;
						case "path of apmeken":
							raidTracker.setApmekenTime(duration);
							break;
						case "ba-ba":
							raidTracker.setBabaTime(duration);
							break;
						case "path of scabaras":
							raidTracker.setScabarasTime(duration);
							break;
						case "kephri":
							raidTracker.setKephriTime(duration);
							break;
						case "path of het":
							raidTracker.setHetTime(duration);
							break;
						case "akkha":
							raidTracker.setAkkhaTime(duration);
							break;
						case "the wardens":
							raidTracker.setWardensTime(duration);
							break;
					}
				}

				if ((m = TOA_COMPLETION_PATTERN.matcher(message)).matches())
				{
					int duration = stringTimeToSeconds(m.group(3));
					if (Objects.equals(m.group(2), "challenge"))
					{
						raidTracker.setToaCompTime(duration);
					}
					if (Objects.equals(m.group(2), "total"))
					{
						raidTracker.setRaidTime(duration);
					}
				}
			}

			if (message.toLowerCase().contains("theatre of blood wave completion")) {
				raidTracker.setRaidTime(stringTimeToSeconds(message.toLowerCase().split("time: ")[1].split("personal")[0]));
			}

			if (raidTracker.isRaidComplete() && message.contains("Team size:")) {
				raidTracker.setRaidTime(stringTimeToSeconds(message.split("Duration: ")[1].split(" ")[0]));
			}

			//works for tob
			if (message.contains("count is:")) {
				raidTracker.setChallengeMode(message.contains("Chambers of Xeric Challenge Mode"));
				raidTracker.setCompletionCount(parseInt(message.split("count is:")[1].trim().replace(".", "")));
				if (raidTracker.isInTheatreOfBlood()) {
					int teamSize = 0;

					for (int i = 6442; i  < 6447; i++) {
						if (client.getVarbitValue(i) != 0) {
							teamSize++;
						}
					}
					raidTracker.setTeamSize(teamSize);
					raidTracker.setRaidComplete(true);
				}
			}

			//only special loot contain the "-" (except for the raid complete message)
			if (raidTracker.isRaidComplete() && message.contains("-") && !message.startsWith(RAID_COMPLETE_MESSAGE_COX_TOB)) {
				//in case of multiple purples, a new purple is stored on a new line in the file, so a new raidtracker object will be used and written to the file
				if (!raidTracker.getSpecialLootReceiver().isEmpty()) {
					RaidTracker altRT = copyData();

					altRT.setSpecialLootReceiver(message.split(" - ")[0]);
					altRT.setSpecialLoot(message.split(" - ")[1]);

					altRT.setSpecialLootInOwnName(altRT.getSpecialLootReceiver().toLowerCase().trim().equals(playerName.toLowerCase().trim()));


					altRT.setSpecialLootValue(getItemPrice(itemManager.search(raidTracker.getSpecialLoot()).get(0)));

					setSplits(altRT);

					fw.writeToFile(altRT);

					SwingUtilities.invokeLater(() -> panel.addDrop(altRT, false));
				}
				else {
					raidTracker.setSpecialLootReceiver(message.split(" - ")[0]);
					raidTracker.setSpecialLoot(message.split(" - ")[1]);

					raidTracker.setSpecialLootValue(getItemPrice(itemManager.search(raidTracker.getSpecialLoot()).get(0)));

					raidTracker.setSpecialLootInOwnName(raidTracker.getSpecialLootReceiver().toLowerCase().trim().equals(playerName.toLowerCase().trim()));

					setSplits(raidTracker);
				}
			}

			//for tob & toa it works a bit different, not possible to get duplicates.
			//toa tested, tob untested in game
			if (raidTracker.isRaidComplete() && message.toLowerCase().contains("found something special") && !message.toLowerCase().contains("lil' zik") && !message.toLowerCase().contains("tumeken's guardian")) {
				raidTracker.setSpecialLootReceiver(message.split(" found something special: ")[0]);
				raidTracker.setSpecialLoot(message.split(" found something special: ")[1]);

				raidTracker.setSpecialLootValue(getItemPrice(itemManager.search(raidTracker.getSpecialLoot()).get(0)));

				raidTracker.setSpecialLootInOwnName(raidTracker.getSpecialLootReceiver().toLowerCase().trim().equals(playerName.toLowerCase().trim()));

				setSplits(raidTracker);
			}

			if (raidTracker.isRaidComplete() && message.startsWith(TWISTED_KIT_RECIPIENTS)) {
				String[] recipients = message.split(TWISTED_KIT_RECIPIENTS)[1].split(",");

				for (String recip : recipients) {
					if (raidTracker.getKitReceiver().isEmpty()) {
						raidTracker.setKitReceiver(recip.trim());
					}
					else {
						RaidTracker altRT = copyData();
						altRT.setKitReceiver(recip.trim());

						fw.writeToFile(altRT);

						SwingUtilities.invokeLater(() -> panel.addDrop(altRT, false));
					}
				}
			}

			if (raidTracker.isRaidComplete() && message.startsWith(DUST_RECIPIENTS)) {
				String[] recipients = message.split(DUST_RECIPIENTS)[1].split(",");

				for (String recip : recipients) {
					if (raidTracker.getDustReceiver().isEmpty()) {
						raidTracker.setDustReceiver(recip.trim());
					}
					else {
						RaidTracker altRT = copyData();
						altRT.setDustReceiver(recip.trim());

						fw.writeToFile(altRT);

						SwingUtilities.invokeLater(() -> panel.addDrop(altRT, false));
					}
				}
			}

			if (raidTracker.isRaidComplete() && (message.toLowerCase().contains("olmlet") || message.toLowerCase().contains("lil' zik")) || message.toLowerCase().contains("tumeken's guardian") || message.toLowerCase().contains("would have been followed")) {
				boolean inOwnName = false;
				boolean duplicate = message.toLowerCase().contains("would have been followed");

				if (playerName.equals(message.split(" ")[0]) || duplicate)	{
					inOwnName = true;
				}

				if (!raidTracker.getPetReceiver().isEmpty()) {
					RaidTracker altRT = copyData();

					if (duplicate) {
						altRT.setPetReceiver(playerName);
					}
					else {
						altRT.setPetReceiver(message.split(" ")[0]);
					}

					altRT.setPetInMyName(inOwnName);

					fw.writeToFile(altRT);

					SwingUtilities.invokeLater(() -> panel.addDrop(altRT, false));
				}
				else {
					if (duplicate) {
						raidTracker.setPetReceiver(playerName);
					}
					else {
						raidTracker.setPetReceiver(message.split(" ")[0]);
					}
					raidTracker.setPetInMyName(inOwnName);
				}
			}
		}
	}

	public void setSplits(RaidTracker raidTracker)
	{

		int lootSplit = raidTracker.getSpecialLootValue() / raidTracker.getTeamSize();

		int cutoff = config.FFACutoff();

		//
		if (raidTracker.getSpecialLoot().length() > 0) {
			if (config.defaultFFA() || lootSplit < cutoff) {
				raidTracker.setFreeForAll(true);
				if (raidTracker.isSpecialLootInOwnName()) {
					raidTracker.setLootSplitReceived(raidTracker.getSpecialLootValue());
				}
			} else if (raidTracker.isSpecialLootInOwnName()) {
				raidTracker.setLootSplitPaid(raidTracker.getSpecialLootValue() - lootSplit);
				raidTracker.setLootSplitReceived(lootSplit);
			} else {
				raidTracker.setLootSplitReceived(lootSplit);
			}
		}
	}

	public ArrayList<RaidTrackerItem> lootListFactory(Item[] items)
	{
		ArrayList<RaidTrackerItem> lootList = new ArrayList<>();
		Arrays.stream(items)
				.filter(item -> item.getId() > -1)
				.forEach(item -> {
					ItemComposition comp = itemManager.getItemComposition(item.getId());
					lootList.add(new RaidTrackerItem() {
						{
							name = comp.getName();
							id = comp.getId();
							quantity = item.getQuantity();
							price = getItemPrice(id) * quantity;
						}
					});
				});
		return lootList;
	}

	private void checkRaidPresence()
	{
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		raidTracker.setInRaidChambers(client.getVarbitValue(Varbits.IN_RAID) == 1);
	}

	private void checkTobPresence() {
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		//1 = in party outside, 2 = spectating, 3 = dead spectating
		raidTracker.setInTheatreOfBlood(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD) > 1);
	}

	private void checkToaPresence()
	{
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		raidTracker.setInTombsOfAmascut(client.getVarbitValue(Varbits.TOA_RAID_LEVEL) > 0);
	}

	private int stringTimeToSeconds(String s)
	{
		String[] split = s.split(":");
		return split.length == 3 ? parseInt(split[0]) * 3600 + parseInt(split[1]) * 60 + Math.round(parseFloat(split[2])) : parseInt(split[0]) * 60 + Math.round(parseFloat(split[1]));
	}

	public RaidTracker copyData() {
		RaidTracker RT = new RaidTracker();

		RT.setDate(raidTracker.getDate());
		RT.setTeamSize(raidTracker.getTeamSize());
		RT.setChallengeMode(raidTracker.isChallengeMode());
		RT.setInTheatreOfBlood(raidTracker.isInTheatreOfBlood());
		RT.setCompletionCount(raidTracker.getCompletionCount());
		RT.setKillCountID(raidTracker.getKillCountID());

		return RT;
	}

	private void reset()
	{
		raidTracker = new RaidTracker();
		writerStarted = false;
		raidStarted = false;
	}

	//from stackoverflow
	public String unescapeJavaString(String st) {

		if (st == null) {
			return null;
		}

		StringBuilder sb = new StringBuilder(st.length());

		for (int i = 0; i < st.length(); i++) {
			char ch = st.charAt(i);
			if (ch == '\\') {
				char nextChar = (i == st.length() - 1) ? '\\' : st
						.charAt(i + 1);
				// Octal escape?
				if (nextChar >= '0' && nextChar <= '7') {
					String code = "" + nextChar;
					i++;
					if ((i < st.length() - 1) && st.charAt(i + 1) >= '0'
							&& st.charAt(i + 1) <= '7') {
						code += st.charAt(i + 1);
						i++;
						if ((i < st.length() - 1) && st.charAt(i + 1) >= '0'
								&& st.charAt(i + 1) <= '7') {
							code += st.charAt(i + 1);
							i++;
						}
					}
					sb.append((char) Integer.parseInt(code, 8));
					continue;
				}
				switch (nextChar) {
					case '\\':
						break;
					case 'b':
						ch = '\b';
						break;
					case 'f':
						ch = '\f';
						break;
					case 'n':
						ch = '\n';
						break;
					case 'r':
						ch = '\r';
						break;
					case 't':
						ch = '\t';
						break;
					case '\"':
						ch = '\"';
						break;
					case '\'':
						ch = '\'';
						break;
					// Hex Unicode: u????
					case 'u':
						if (i >= st.length() - 5) {
							ch = 'u';
							break;
						}
						int code = Integer.parseInt(
								"" + st.charAt(i + 2) + st.charAt(i + 3)
										+ st.charAt(i + 4) + st.charAt(i + 5), 16);
						sb.append(Character.toChars(code));
						i += 5;
						continue;
				}
				i++;
			}
			sb.append(ch);
		}
		return sb.toString();
	}

	public int getItemPrice(ItemPrice itemPrice) {
		return itemPrice.getWikiPrice() > 0 ? itemPrice.getWikiPrice() : itemPrice.getPrice();
	}
	public int getItemPrice(int itemID) {
		return itemManager.getItemPrice(itemID) > 0 ? itemManager.getItemPrice(itemID) : itemManager.getItemComposition(itemID).getPrice();
	}

	public void setFw(FileReadWriter fw) {
		this.fw = fw;
	}
}
