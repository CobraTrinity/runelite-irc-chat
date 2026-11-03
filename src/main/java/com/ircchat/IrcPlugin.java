package com.ircchat;

import com.google.inject.Provides;
import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "IRC Chat",
	description = "Chat on an IRC channel from the RuneLite chatbox or side panel. Type ::irc <message> to talk.",
	tags = {"irc", "chat", "social", "network"}
)
public class IrcPlugin extends Plugin
{
	/** Config keys that require a reconnect when changed. */
	private static final Set<String> CONNECTION_KEYS = Set.of(
		"server", "port", "tls", "channel", "channelKey", "nick", "saslAccount", "saslPassword",
		"nickservPassword", "serverPassword");

	private static final long MIN_RECONNECT_SECONDS = 5;
	private static final long MAX_RECONNECT_SECONDS = 300;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private IrcConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	private IrcPanel panel;
	private NavigationButton navButton;
	/** Read from the IRC thread in emit(); written under this. */
	private volatile boolean navButtonAdded;

	/** We set an away message because the player logged out (so we may clear it on login). */
	private volatile boolean autoAwaySet;
	/** The player set an away message with ::iaway; auto-away leaves it alone. */
	private volatile boolean manualAway;

	/** Current connection, or null. Writes guarded by this. */
	private volatile IrcClient irc;
	private ScheduledFuture<?> reconnectTask;
	private volatile long reconnectDelaySeconds = MIN_RECONNECT_SECONDS;
	/** False after ::ircdisconnect or plugin shutdown, so we don't auto-reconnect. */
	private volatile boolean wantConnected;
	/** Nick to use when the config nick is blank; picked once per session. */
	private String fallbackNick;
	/** IRC lines held while logged out; shown on the first game tick after logging in. Guarded by itself. */
	private static final int MAX_HELD_MESSAGES = 50;
	private final ArrayDeque<String> heldMessages = new ArrayDeque<>();
	private int droppedMessages;
	/** True once a game tick has run with a local player. Written under heldMessages. */
	private volatile boolean canShow;

	/** Channel picked with ::iswitch / ::ijoin / the panel; null = the first channel we're in. */
	private volatile String activeChannel;
	/** Channels joined with ::ijoin this session, rejoined after a reconnect. */
	private final Set<String> sessionChannels = ConcurrentHashMap.newKeySet();
	/** Channel from the last invite, for a bare ::ijoin. */
	private volatile String lastInviteChannel;
	/** Watch-list state from MONITOR: casefolded nick -> online. Unknown until the server says. */
	private final Map<String, Boolean> watchOnline = new ConcurrentHashMap<>();

	/** Who last sent us a private message, for ::ir. */
	private volatile String lastPmFrom;
	/** STS policies learned this session: lowercase host -> TLS port. */
	private final Map<String, Integer> stsPolicies = new ConcurrentHashMap<>();

	@Provides
	IrcConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IrcConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("IRC Chat plugin started");
		clearHeldMessages(); // the next game tick re-enables output if we're already logged in
		panel = new IrcPanel(this::onPanelInput, this::onPanelSwitch, this::fold);
		navButton = NavigationButton.builder()
			.tooltip("IRC Chat")
			.icon(IrcPanel.icon())
			.priority(10)
			.panel(panel)
			.build();
		updateNavButton();
		panel.setStatus("Not connected");
		wantConnected = true;
		reconnectDelaySeconds = MIN_RECONNECT_SECONDS;
		if (!IrcClient.isBlank(config.channel()))
		{
			scheduleConnect(0);
		}
		else
		{
			log.info("No IRC channel configured; not connecting");
		}
	}

	@Override
	protected void shutDown()
	{
		IrcClient old = detachForShutdown();
		if (old != null)
		{
			log.info("Disconnecting from IRC on plugin shutdown");
			old.disconnect("Plugin disabled");
		}
		clearHeldMessages();
		if (navButtonAdded)
		{
			clientToolbar.removeNavigation(navButton);
			navButtonAdded = false;
		}
		panel = null;
		navButton = null;
		log.info("IRC Chat plugin stopped");
	}

	/** Adds or removes the sidebar button to match the 'Show side panel' setting. */
	private synchronized void updateNavButton()
	{
		if (navButton == null)
		{
			return;
		}
		if (config.sidePanel() && !navButtonAdded)
		{
			clientToolbar.addNavigation(navButton);
			navButtonAdded = true;
		}
		else if (!config.sidePanel() && navButtonAdded)
		{
			clientToolbar.removeNavigation(navButton);
			navButtonAdded = false;
		}
	}

	/**
	 * RuneLite doesn't call shutDown() when the client exits, so send QUIT here.
	 * The disconnect runs on the executor and is registered with waitFor so the client
	 * holds its exit until the QUIT has been written and the socket closed.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		IrcClient old = detachForShutdown();
		if (old != null)
		{
			log.info("Disconnecting from IRC on client exit");
			event.waitFor(executor.submit(() -> old.disconnect("Client closed")));
		}
	}

	/** Stops reconnecting and detaches the current connection, returning it (or null). */
	private synchronized IrcClient detachForShutdown()
	{
		wantConnected = false;
		cancelReconnect();
		IrcClient old = irc;
		irc = null;
		return old;
	}

	// ------------------------------------------------------------------
	// Connection management
	// ------------------------------------------------------------------

	private synchronized void scheduleConnect(long delaySeconds)
	{
		cancelReconnect();
		// Resolve the nick on the client thread (needed to read the local player's name),
		// then open the socket on a background thread.
		reconnectTask = executor.schedule(
			() -> clientThread.invoke(() ->
			{
				String nick = resolveNick();
				executor.execute(() -> connect(nick));
			}),
			delaySeconds, TimeUnit.SECONDS);
	}

	private synchronized void cancelReconnect()
	{
		if (reconnectTask != null)
		{
			reconnectTask.cancel(false);
			reconnectTask = null;
		}
	}

	private String resolveNick()
	{
		String configured = IrcClient.toValidNick(config.nick().trim());
		if (!configured.isEmpty())
		{
			return configured;
		}
		Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			String fromRs = IrcClient.toValidNick(Text.removeTags(local.getName()));
			if (!fromRs.isEmpty())
			{
				return fromRs;
			}
		}
		if (fallbackNick == null)
		{
			fallbackNick = IrcClient.randomNick();
		}
		return fallbackNick;
	}

	private void connect(String nick)
	{
		String channel = normaliseChannel(config.channel());
		if (!wantConnected || channel.isEmpty() || IrcClient.isBlank(config.server()))
		{
			return;
		}

		IrcClient.Settings s = new IrcClient.Settings();
		s.host = config.server().trim();
		s.port = config.port();
		s.tls = config.tls();
		Integer stsPort = stsPolicies.get(s.host.toLowerCase());
		if (stsPort != null && !s.tls)
		{
			s.tls = true;
			s.port = stsPort;
		}
		s.nick = nick;
		s.channel = channel;
		s.channelKey = config.channelKey().trim();
		s.extraChannels = new ArrayList<>(configChannels(config.extraChannels()));
		for (String ch : sessionChannels)
		{
			if (!s.extraChannels.contains(ch))
			{
				s.extraChannels.add(ch);
			}
		}
		s.saslAccount = config.saslAccount().trim();
		s.saslPassword = config.saslPassword();
		s.nickservPassword = config.nickservPassword();
		s.serverPassword = config.serverPassword();

		log.info("Connecting to {}:{} (tls={}) as {}", s.host, s.port, s.tls, nick);
		info("Connecting to " + s.host + ":" + s.port + (s.tls ? " (TLS)" : "") + " as " + nick + "...");
		synchronized (this)
		{
			if (irc != null)
			{
				IrcClient old = irc;
				irc = null; // detach first so its disconnect callback is ignored
				log.info("Replacing existing IRC connection");
				old.disconnect("Reconnecting");
			}
			// A new connection starts with no away state on the server.
			autoAwaySet = false;
			manualAway = false;
			irc = new IrcClient(s, new ConnectionListener());
			irc.connect();
		}
	}

	/** Comma/space separated list, blanks dropped. Null-safe (ConfigChanged values can be null). */
	static List<String> splitList(String raw)
	{
		List<String> out = new ArrayList<>();
		if (raw != null)
		{
			for (String s : raw.split("[,\\s]+"))
			{
				if (!s.isEmpty())
				{
					out.add(s);
				}
			}
		}
		return out;
	}

	/** A configured channel list, each with a # added if missing. */
	static List<String> configChannels(String raw)
	{
		List<String> out = new ArrayList<>();
		for (String s : splitList(raw))
		{
			out.add(normaliseChannel(s));
		}
		return out;
	}

	/**
	 * Where ::i and the channel commands go: the channel picked with ::iswitch (if we're still in it),
	 * otherwise the first channel we're in, otherwise the configured one.
	 */
	private String activeChannel(IrcClient c)
	{
		String a = activeChannel;
		if (a != null)
		{
			String found = c.findChannel(a);
			if (found != null)
			{
				return found;
			}
		}
		List<String> joined = c.getChannels();
		return joined.isEmpty() ? normaliseChannel(config.channel()) : joined.get(0);
	}

	/** Pushes the channel list, active channel and its nick list to the side panel. */
	private void refreshPanelChannels()
	{
		IrcPanel p = panel;
		IrcClient c = irc;
		if (p == null)
		{
			return;
		}
		if (c == null)
		{
			p.setChannels(new ArrayList<>(), null);
			p.clearUsers();
			return;
		}
		String active = activeChannel(c);
		p.setChannels(c.getChannels(), active);
		p.requestUsers(() -> c.getUsers(activeChannel(c)), c.getPrefixSymbols());
		updatePanelStatus();
	}

	/** Picked in the panel's channel box. */
	private void onPanelSwitch(String channel)
	{
		IrcClient c = irc;
		if (c != null && c.findChannel(channel) != null)
		{
			activeChannel = channel;
			refreshPanelChannels();
		}
	}

	static String normaliseChannel(String raw)
	{
		String c = raw == null ? "" : raw.trim();
		if (c.isEmpty())
		{
			return "";
		}
		char first = c.charAt(0);
		if (first != '#' && first != '&' && first != '+' && first != '!')
		{
			c = "#" + c;
		}
		return c;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!IrcConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (IrcConfig.SIDE_PANEL_KEY.equals(event.getKey()))
		{
			updateNavButton();
			return;
		}
		if ("autoAway".equals(event.getKey()) && !config.autoAway())
		{
			setAutoAway(false, true); // turning it off brings you back if it had marked you away
			return;
		}
		if (IrcConfig.EXTRA_CHANNELS_KEY.equals(event.getKey()))
		{
			// Join/part just the difference instead of reconnecting.
			IrcClient c = irc;
			if (c != null && c.isRegistered())
			{
				List<String> before = configChannels(event.getOldValue());
				List<String> after = configChannels(event.getNewValue());
				for (String ch : after)
				{
					if (!before.contains(ch) && c.findChannel(ch) == null)
					{
						c.join(ch, null);
					}
				}
				for (String ch : before)
				{
					if (!after.contains(ch) && !sessionChannels.contains(ch) && c.findChannel(ch) != null
						&& !ch.equalsIgnoreCase(normaliseChannel(config.channel())))
					{
						c.part(ch, null);
					}
				}
			}
			return;
		}
		if (IrcConfig.WATCH_LIST_KEY.equals(event.getKey()))
		{
			IrcClient c = irc;
			if (c != null && c.isRegistered() && c.supportsMonitor())
			{
				List<String> before = splitList(event.getOldValue());
				List<String> after = splitList(event.getNewValue());
				List<String> added = new ArrayList<>(after);
				added.removeAll(before);
				List<String> removed = new ArrayList<>(before);
				removed.removeAll(after);
				if (!removed.isEmpty())
				{
					c.monitorRemove(removed);
					removed.forEach(n -> watchOnline.remove(fold(n)));
				}
				if (!added.isEmpty())
				{
					c.monitorAdd(added);
				}
			}
			return;
		}
		if (!CONNECTION_KEYS.contains(event.getKey()) || !wantConnected)
		{
			return;
		}
		reconnectDelaySeconds = MIN_RECONNECT_SECONDS;
		synchronized (this)
		{
			if (irc != null)
			{
				IrcClient old = irc;
				irc = null; // detach first so its disconnect callback doesn't trigger a reconnect
				log.info("IRC setting '{}' changed; disconnecting to reconnect", event.getKey());
				old.disconnect("Changing settings");
			}
		}
		// Small delay so typing in a text field doesn't reconnect once per keystroke.
		scheduleConnect(2);
	}

	// ------------------------------------------------------------------
	// Chatbox commands
	// ------------------------------------------------------------------

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		handleCommand(event.getCommand().toLowerCase(), event.getArguments());
	}

	/**
	 * A line typed into the side panel. Plain text goes to the channel; "/cmd args" runs the
	 * matching ::irccmd (so /msg, /me, /whois, /kick ... all work), and "//text" sends "/text".
	 */
	private void onPanelInput(String line)
	{
		if (line.startsWith("//"))
		{
			sayToChannel(line.substring(1), false);
			return;
		}
		if (!line.startsWith("/"))
		{
			sayToChannel(line, false);
			return;
		}
		String[] parts = line.substring(1).trim().split("\\s+");
		String cmd = parts[0].toLowerCase();
		String[] args = Arrays.copyOfRange(parts, 1, parts.length);
		if (cmd.isEmpty() || !(handleCommand("irc" + cmd, args) || handleCommand(cmd, args)))
		{
			info("Unknown command /" + cmd + ". Type /help for the list.");
		}
	}

	/** Runs a command by name (without the "::"). Returns false if it isn't one of ours. */
	private boolean handleCommand(String cmd, String[] args)
	{
		String text = String.join(" ", args).trim();

		switch (cmd)
		{
			case "irc":
			case "i":
				if (text.isEmpty())
				{
					showStatus();
				}
				else
				{
					sayToChannel(text, false);
				}
				break;
			case "ircme":
			case "ime":
				if (!text.isEmpty())
				{
					sayToChannel(text, true);
				}
				break;
			case "ircmsg":
			case "im":
				if (args.length < 2)
				{
					info("Usage: ::ircmsg (or ::im) <nick> <message>");
				}
				else
				{
					privateMessage(args[0], String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
				}
				break;
			case "ircreply":
			case "ir":
			{
				String to = lastPmFrom;
				if (to == null)
				{
					info("Nobody has sent you a private message yet.");
				}
				else if (text.isEmpty())
				{
					info("Usage: ::ircreply (or ::ir) <message> (replies to " + to + ")");
				}
				else
				{
					privateMessage(to, text);
				}
				break;
			}
			case "ircignore":
			case "iignore":
				if (args.length == 0)
				{
					List<String> ignored = ignoredNicks();
					info(ignored.isEmpty() ? "You're not ignoring anyone." : "Ignoring: " + String.join(", ", ignored));
				}
				else
				{
					setIgnored(args[0], true);
				}
				break;
			case "ircunignore":
			case "iunignore":
				if (args.length != 1)
				{
					info("Usage: ::ircunignore (or ::iunignore) <nick>");
				}
				else
				{
					setIgnored(args[0], false);
				}
				break;
			case "ircwhois":
			case "iwhois":
				if (args.length != 1)
				{
					info("Usage: ::ircwhois (or ::iwhois) <nick>");
				}
				else
				{
					IrcClient c = connected();
					if (c != null)
					{
						c.whois(args[0]);
					}
				}
				break;
			case "irctopic":
			case "itopic":
			{
				IrcClient c = connected();
				if (c != null)
				{
					if (text.isEmpty())
					{
						c.topic(activeChannel(c));
					}
					else
					{
						c.setTopic(activeChannel(c), text);
					}
				}
				break;
			}
			case "ircnick":
			case "inick":
				if (args.length != 1)
				{
					info("Usage: ::ircnick (or ::inick) <newnick> (temporary; set Nickname in the config to keep it)");
				}
				else
				{
					IrcClient c = connected();
					if (c != null)
					{
						c.changeNick(IrcClient.toValidNick(args[0], c.getNickLength()));
					}
				}
				break;
			case "ircaway":
			case "iaway":
			{
				IrcClient c = connected();
				if (c != null)
				{
					c.setAway(text);
					// A manual away (or coming back) takes over from auto-away until the next one.
					manualAway = !text.isEmpty();
					autoAwaySet = false;
					info(text.isEmpty() ? "You are no longer marked away." : "You are marked away: " + text);
				}
				break;
			}
			case "ircnames":
			case "inames":
			{
				IrcClient c = connected();
				if (c != null)
				{
					c.names(activeChannel(c));
				}
				break;
			}
			case "irccaps":
			case "icaps":
			{
				IrcClient c = connected();
				if (c != null)
				{
					Set<String> caps = c.getEnabledCaps();
					info("IRCv3 capabilities: " + (caps.isEmpty() ? "none" : String.join(", ", caps)));
				}
				break;
			}
			case "ircconnect":
			case "ic":
				wantConnected = true;
				reconnectDelaySeconds = MIN_RECONNECT_SECONDS;
				if (IrcClient.isBlank(config.channel()))
				{
					info("Set a channel in the IRC Chat plugin settings first.");
				}
				else
				{
					scheduleConnect(0);
				}
				break;
			case "ircdisconnect":
			case "id":
				wantConnected = false;
				synchronized (this)
				{
					cancelReconnect();
					if (irc != null)
					{
						log.info("Disconnecting from IRC (::ircdisconnect)");
						irc.disconnect("Leaving");
					}
				}
				break;
			case "ircjoin":
			case "ijoin":
			{
				String ch = args.length > 0 ? normaliseChannel(args[0]) : lastInviteChannel;
				if (ch == null)
				{
					info("Usage: ::ircjoin (or ::ijoin) <#channel> [key]. With no channel, joins the last one you were invited to.");
					break;
				}
				IrcClient c = connected();
				if (c == null)
				{
					break;
				}
				activeChannel = ch; // talk there once we're in
				if (c.findChannel(ch) != null)
				{
					info("Now talking in " + c.findChannel(ch) + ".");
					refreshPanelChannels();
				}
				else
				{
					sessionChannels.add(ch);
					c.join(ch, args.length > 1 ? args[1] : null);
				}
				break;
			}
			case "ircpart":
			case "ipart":
			{
				IrcClient c = connected();
				if (c == null)
				{
					break;
				}
				// "::ipart #chan reason" or "::ipart reason" (leaves the active channel).
				boolean named = args.length > 0 && c.isChannel(args[0]);
				String ch = named ? args[0] : activeChannel(c);
				String reason = String.join(" ", Arrays.copyOfRange(args, named ? 1 : 0, args.length));
				if (c.findChannel(ch) == null)
				{
					info("You're not in " + ch + ".");
					break;
				}
				sessionChannels.removeIf(s -> s.equalsIgnoreCase(ch));
				c.part(ch, reason.isEmpty() ? "Leaving" : reason);
				break;
			}
			case "ircswitch":
			case "iswitch":
			{
				IrcClient c = connected();
				if (c == null)
				{
					break;
				}
				if (args.length == 0)
				{
					String active = activeChannel(c);
					List<String> list = new ArrayList<>();
					for (String ch : c.getChannels())
					{
						list.add(ch.equalsIgnoreCase(active) ? ch + " (talking here)" : ch);
					}
					info(list.isEmpty() ? "You're not in any channels." : "Channels: " + String.join(", ", list)
						+ ". ::iswitch <#channel> to change.");
					break;
				}
				String found = c.findChannel(normaliseChannel(args[0]));
				if (found == null)
				{
					info("You're not in " + normaliseChannel(args[0]) + ". Use ::ijoin to join it.");
					break;
				}
				activeChannel = found;
				info("Now talking in " + found + ".");
				refreshPanelChannels();
				break;
			}
			case "ircwatch":
			case "iwatch":
				if (args.length == 0)
				{
					List<String> watched = splitList(config.watchList());
					if (watched.isEmpty())
					{
						info("Your watch list is empty. ::iwatch <nick> to add someone.");
						break;
					}
					List<String> shown = new ArrayList<>();
					for (String n : watched)
					{
						Boolean on = watchOnline.get(fold(n));
						shown.add(n + (on == null ? "" : on ? " (online)" : " (offline)"));
					}
					info("Watching: " + String.join(", ", shown));
				}
				else
				{
					setWatched(args[0], true);
				}
				break;
			case "ircunwatch":
			case "iunwatch":
				if (args.length != 1)
				{
					info("Usage: ::ircunwatch (or ::iunwatch) <nick>");
				}
				else
				{
					setWatched(args[0], false);
				}
				break;
			case "irckick":
			case "ikick":
				if (args.length < 1)
				{
					info("Usage: ::irckick (or ::ikick) <nick> [reason]");
				}
				else
				{
					IrcClient c = connected();
					if (c != null)
					{
						c.kick(activeChannel(c), args[0], String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
					}
				}
				break;
			case "ircban":
			case "iban":
			case "ircunban":
			case "iunban":
				if (args.length != 1)
				{
					info("Usage: ::ircban / ::ircunban (or ::iban / ::iunban) <nick or nick!user@host mask>");
				}
				else
				{
					IrcClient c = connected();
					if (c != null)
					{
						boolean ban = cmd.endsWith("ban") && !cmd.endsWith("unban");
						c.mode(activeChannel(c), (ban ? "+b " : "-b ") + banMask(args[0]));
					}
				}
				break;
			case "ircmode":
			case "imode":
				if (text.isEmpty())
				{
					info("Usage: ::ircmode (or ::imode) <modes> [args], e.g. ::imode +o SomeNick");
				}
				else
				{
					IrcClient c = connected();
					if (c != null)
					{
						c.mode(activeChannel(c), text);
					}
				}
				break;
			case "irchelp":
			case "ih":
				info("::i <msg> - talk | ::ime <action> | ::im <nick> <msg> | ::ir <msg> - reply to last PM | "
					+ "::inames | ::iwhois <nick> | ::itopic [new topic] | ::iignore [nick] | ::iunignore <nick> | "
					+ "::iaway [msg] | ::inick <nick> | ::icaps | ::ic - connect | ::id - disconnect | ::i - status");
				info("Channels: ::ijoin <#chan> [key] | ::ipart [#chan] [reason] | ::iswitch [#chan] - where ::i talks. "
					+ "Watch list: ::iwatch [nick] | ::iunwatch <nick>");
				info("Channel ops: ::ikick <nick> [reason] | ::iban <nick|mask> | ::iunban <nick|mask> | ::imode <modes>. "
					+ "Long forms start with ::irc (::ircmsg, ::ircwhois, ...). "
					+ "In the side panel, type /msg, /me, /whois, /kick ... instead.");
				break;
			default:
				return false;
		}
		return true;
	}

	/**
	 * Adds/removes a nick on the saved watch list. The config change handler then sends the
	 * MONITOR +/- (so editing the setting by hand works the same way).
	 */
	private void setWatched(String nick, boolean watch)
	{
		List<String> list = splitList(config.watchList());
		String key = fold(nick);
		boolean present = list.removeIf(n -> fold(n).equals(key));
		if (watch)
		{
			list.add(nick);
		}
		configManager.setConfiguration(IrcConfig.GROUP, IrcConfig.WATCH_LIST_KEY, String.join(", ", list));
		IrcClient c = irc;
		if (watch)
		{
			info(present ? "Already watching " + nick + "." : "Watching " + nick + ". You'll be told when they come online.");
			if (c != null && c.isRegistered() && !c.supportsMonitor())
			{
				info("This server doesn't support MONITOR, so the watch list won't work here.");
			}
		}
		else
		{
			info(present ? "Stopped watching " + nick + "." : nick + " wasn't on your watch list.");
		}
	}

	/** A bare nick becomes nick!*@*; anything that already looks like a mask is used as-is. */
	static String banMask(String target)
	{
		return target.indexOf('!') >= 0 || target.indexOf('@') >= 0 || target.indexOf('$') >= 0
			? target : target + "!*@*";
	}

	// ------------------------------------------------------------------
	// Ignore list (stored in the config so it survives restarts)
	// ------------------------------------------------------------------

	private List<String> ignoredNicks()
	{
		List<String> out = new ArrayList<>();
		for (String n : config.ignoredNicks().split("[,\\s]+"))
		{
			if (!n.isEmpty())
			{
				out.add(n);
			}
		}
		return out;
	}

	private boolean isIgnored(String nick)
	{
		if (nick == null || nick.isEmpty())
		{
			return false;
		}
		String key = fold(nick);
		for (String n : ignoredNicks())
		{
			if (fold(n).equals(key))
			{
				return true;
			}
		}
		return false;
	}

	private void setIgnored(String nick, boolean ignore)
	{
		List<String> list = ignoredNicks();
		String key = fold(nick);
		boolean present = list.removeIf(n -> fold(n).equals(key));
		if (ignore)
		{
			list.add(nick);
		}
		configManager.setConfiguration(IrcConfig.GROUP, IrcConfig.IGNORED_NICKS_KEY, String.join(", ", list));
		if (ignore)
		{
			info(present ? "Already ignoring " + nick + "." : "Ignoring " + nick + ". ::iunignore " + nick + " to undo.");
		}
		else
		{
			info(present ? "No longer ignoring " + nick + "." : nick + " wasn't on your ignore list.");
		}
	}

	/** Casefold with the server's rules when connected, otherwise IRC's default (rfc1459). */
	private String fold(String nick)
	{
		IrcClient c = irc;
		return c != null ? c.casefold(nick) : IrcClient.casefold(nick, "rfc1459");
	}

	private IrcClient connected()
	{
		IrcClient c = irc;
		if (c == null || !c.isRegistered())
		{
			info("Not connected to IRC. Type ::ircconnect, or check the plugin settings.");
			return null;
		}
		return c;
	}

	private void sayToChannel(String text, boolean action)
	{
		IrcClient c = connected();
		if (c == null)
		{
			return;
		}
		String channel = activeChannel(c);
		// With echo-message the server sends our message back and we display that copy instead.
		boolean echo = c.hasCap("echo-message");
		if (action)
		{
			c.action(channel, text);
			if (!echo)
			{
				showAction(channel, c.getNick(), text);
			}
		}
		else
		{
			List<String> chunks = c.privmsg(channel, text);
			if (!echo)
			{
				for (String chunk : chunks)
				{
					showMessage(channel, c.getNick(), chunk);
				}
			}
		}
	}

	private void privateMessage(String nick, String text)
	{
		IrcClient c = connected();
		if (c == null)
		{
			return;
		}
		List<String> chunks = c.privmsg(nick, text);
		if (!c.hasCap("echo-message"))
		{
			for (String chunk : chunks)
			{
				showOutgoingPm(nick, chunk);
			}
		}
	}

	private void showStatus()
	{
		IrcClient c = irc;
		if (c != null && c.isRegistered())
		{
			String net = c.getNetwork() != null ? c.getNetwork() : config.server();
			String acct = c.getAccount() != null ? ", logged in as " + c.getAccount() : "";
			List<String> chans = c.getChannels();
			String where = chans.size() > 1
				? String.join(", ", chans) + " (talking in " + activeChannel(c) + ")"
				: activeChannel(c);
			info("Connected to " + net + " in " + where + " as " + c.getNick() + acct
				+ ". Type ::irchelp for commands.");
		}
		else if (c != null)
		{
			info("Connecting to " + config.server() + "...");
		}
		else
		{
			info("Not connected. Type ::ircconnect, or ::irchelp for commands.");
		}
	}

	// ------------------------------------------------------------------
	// Incoming IRC events
	// ------------------------------------------------------------------

	private final class ConnectionListener implements IrcClient.Listener
	{
		/** Ignore callbacks from a connection that has since been replaced. */
		private boolean stale()
		{
			IrcClient c = irc;
			return c == null || c.getListener() != this;
		}

		@Override
		public void onRegistered(String nick)
		{
			if (stale())
			{
				return;
			}
			reconnectDelaySeconds = MIN_RECONNECT_SECONDS;
			log.info("Registered with IRC server as {}", nick);
			info("Connected as " + nick + ".");
			updatePanelStatus();
			if (client.getGameState() == GameState.LOGIN_SCREEN)
			{
				setAutoAway(true, false);
			}
		}

		@Override
		public void onUsersChanged(String channel)
		{
			IrcClient c = irc;
			IrcPanel p = panel;
			// The panel lists the active channel only, so other channels' changes don't matter to it.
			if (!stale() && p != null && c != null && (channel == null || channel.equalsIgnoreCase(activeChannel(c))))
			{
				p.requestUsers(() -> c.getUsers(activeChannel(c)), c.getPrefixSymbols());
			}
		}

		@Override
		public void onReady()
		{
			IrcClient c = irc;
			if (stale() || c == null)
			{
				return;
			}
			watchOnline.clear();
			List<String> watched = splitList(config.watchList());
			if (watched.isEmpty())
			{
				return;
			}
			if (!c.supportsMonitor())
			{
				info("This server doesn't support MONITOR, so your watch list won't work here.");
				return;
			}
			c.monitorAdd(watched);
		}

		@Override
		public void onMonitor(List<String> nicks, boolean online)
		{
			if (stale())
			{
				return;
			}
			for (String n : nicks)
			{
				Boolean before = watchOnline.put(fold(n), online);
				if (online && !Boolean.TRUE.equals(before))
				{
					info(n + " is online.");
					if (config.notifyOnWatch())
					{
						notifier.notify("IRC: " + n + " is online");
					}
				}
				else if (!online && Boolean.TRUE.equals(before))
				{
					// Only when we knew they were on; the first "offline" after watching is just the starting state.
					info(n + " went offline.");
				}
			}
		}

		@Override
		public void onLoggedIn(String account)
		{
			if (!stale())
			{
				info("Logged in as " + account + ".");
			}
		}

		@Override
		public void onSelfJoin(String channel)
		{
			if (!stale())
			{
				IrcClient c = irc;
				boolean first = c != null && c.getChannels().size() == 1;
				// Switch the panel first (both go through the Swing queue in order), so after ::ijoin
				// the "Joined" line lands in the new channel's scrollback.
				refreshPanelChannels();
				info("Joined " + channel + "." + (first ? " Type ::irc <message> to chat, ::irchelp for commands." : ""));
			}
		}

		@Override
		public void onMessage(IrcClient.ChatEvent e)
		{
			if (stale() || (!e.self && isIgnored(e.sender)))
			{
				return;
			}
			if (!e.channel)
			{
				if (e.self)
				{
					showOutgoingPm(e.target, e.text);
					return;
				}
				lastPmFrom = e.sender;
				emit(new Line(e.time, "PM", e.sender, e.action, e.text, true, false));
				if (config.notifyOnMention())
				{
					notifier.notify("IRC PM from " + e.sender + ": " + e.text);
				}
				return;
			}
			IrcClient c = irc;
			boolean highlight = !e.self && c != null && isHighlight(e.text, c.getNick());
			emit(new Line(e.time, e.target, e.sender, e.action, e.text, highlight, true));
			if (highlight && config.notifyOnMention())
			{
				notifier.notify("IRC " + e.target + " <" + e.sender + "> " + e.text);
			}
		}

		@Override
		public void onNotice(IrcClient.ChatEvent e)
		{
			if (!stale() && !isIgnored(e.sender))
			{
				emit(new Line(e.time, "Notice", e.sender, false, e.text, false, false));
			}
		}

		@Override
		public void onJoin(String channel, IrcClient.User user)
		{
			if (!stale() && config.showJoinPart())
			{
				String acct = user.getAccount() != null && !user.getAccount().equalsIgnoreCase(user.getNick())
					? " [" + user.getAccount() + "]" : "";
				info(user.getNick() + acct + " joined " + channel);
			}
		}

		@Override
		public void onPart(String channel, String nick, String reason)
		{
			if (stale())
			{
				return;
			}
			IrcClient c = irc;
			if (c != null && c.casefold(nick).equals(c.casefold(c.getNick())))
			{
				info("You left " + channel + "." + (c.getChannels().isEmpty() ? " ::ijoin <#channel> to join one." : ""));
				refreshPanelChannels();
			}
			else if (config.showJoinPart())
			{
				info(nick + " left " + channel + (reason.isEmpty() ? "" : " (" + IrcClient.stripFormatting(reason) + ")"));
			}
		}

		@Override
		public void onQuit(String nick, String reason)
		{
			if (!stale() && config.showJoinPart())
			{
				info(nick + " quit" + (reason.isEmpty() ? "" : " (" + IrcClient.stripFormatting(reason) + ")"));
			}
		}

		@Override
		public void onNetBatch(String type, String servers, List<String> nicks)
		{
			if (!stale() && config.showJoinPart())
			{
				String what = "netsplit".equals(type) ? "Netsplit" : "Netjoin";
				info(what + " " + servers + ": " + nicks.size() + " user" + (nicks.size() == 1 ? "" : "s")
					+ ("netsplit".equals(type) ? " quit" : " rejoined"));
			}
		}

		@Override
		public void onKick(String channel, String kicked, String by, String reason)
		{
			if (stale())
			{
				return;
			}
			IrcClient c = irc;
			boolean self = c != null && c.casefold(kicked).equals(c.casefold(c.getNick()));
			info((self ? "You were" : kicked + " was") + " kicked from " + channel + " by " + by
				+ (reason.isEmpty() ? "" : " (" + IrcClient.stripFormatting(reason) + ")"));
			if (self)
			{
				refreshPanelChannels();
			}
		}

		@Override
		public void onNickChange(String oldNick, String newNick, boolean self)
		{
			if (stale())
			{
				return;
			}
			if (self)
			{
				info("You are now known as " + newNick);
				updatePanelStatus();
			}
			else if (config.showJoinPart())
			{
				info(oldNick + " is now known as " + newNick);
			}
		}

		@Override
		public void onAway(String nick, String message)
		{
			if (!stale() && config.showAway())
			{
				info(message == null ? nick + " is back" : nick + " is away: " + IrcClient.stripFormatting(message));
			}
		}

		@Override
		public void onInvite(String by, String target, String channel)
		{
			if (stale() || isIgnored(by))
			{
				return;
			}
			IrcClient c = irc;
			if (c != null && c.casefold(target).equals(c.casefold(c.getNick())))
			{
				lastInviteChannel = channel;
				info(by + " invited you to " + channel + ". Type ::ijoin to join.");
			}
			else if (config.showJoinPart())
			{
				info(by + " invited " + target + " to " + channel);
			}
		}

		@Override
		public void onNames(String channel, List<IrcClient.User> users)
		{
			if (stale())
			{
				return;
			}
			List<String> names = new ArrayList<>(users.size());
			int away = 0;
			for (IrcClient.User u : users)
			{
				names.add(u.getPrefix() + u.getNick() + (u.isAway() ? " (away)" : ""));
				if (u.isAway())
				{
					away++;
				}
			}
			info(channel + " (" + users.size() + (away > 0 ? ", " + away + " away" : "") + "): " + String.join(", ", names));
		}

		@Override
		public void onTopic(String channel, String topic)
		{
			if (!stale() && !topic.isEmpty())
			{
				info("Topic for " + channel + ": " + topic);
			}
		}

		@Override
		public void onTopicInfo(String channel, String setBy, Instant setAt)
		{
			if (!stale())
			{
				info("Topic set by " + setBy + (setAt == null ? "" : " " + ago(setAt) + " ago") + ".");
			}
		}

		@Override
		public void onTopicChange(String channel, String nick, String topic)
		{
			if (!stale())
			{
				info(topic.isEmpty() ? nick + " cleared the topic of " + channel
					: nick + " changed the topic of " + channel + " to: " + topic);
			}
		}

		@Override
		public void onWhois(IrcClient.Whois w)
		{
			if (stale())
			{
				return;
			}
			StringBuilder sb = new StringBuilder(w.nick);
			if (w.user != null && w.host != null)
			{
				sb.append(" (").append(w.user).append('@').append(w.host).append(')');
			}
			if (w.realName != null && !w.realName.isEmpty())
			{
				sb.append(": ").append(w.realName);
			}
			if (w.account != null)
			{
				sb.append(" | logged in as ").append(w.account);
			}
			if (w.awayMessage != null)
			{
				sb.append(" | away: ").append(w.awayMessage);
			}
			if (w.idleSeconds >= 0)
			{
				sb.append(" | idle ").append(duration(w.idleSeconds));
			}
			if (w.signedOn != null)
			{
				sb.append(" | connected ").append(ago(w.signedOn)).append(" ago");
			}
			if (w.channels != null && !w.channels.isEmpty())
			{
				sb.append(" | in ").append(w.channels);
			}
			if (w.server != null)
			{
				sb.append(" | on ").append(w.server);
			}
			if (w.secure)
			{
				sb.append(" | secure connection");
			}
			if (w.operator)
			{
				sb.append(" | IRC operator");
			}
			info(sb.toString());
		}

		@Override
		public void onStandardReply(String type, String command, String code, String description)
		{
			if (!stale() && !"NOTE".equals(type))
			{
				info(("FAIL".equals(type) ? "Error" : "Warning") + " (" + command + "): " + description);
			}
		}

		@Override
		public void onStsUpgrade(int port)
		{
			if (stale())
			{
				return;
			}
			String host = config.server().trim().toLowerCase();
			stsPolicies.put(host, port);
			log.info("STS policy for {}: reconnecting with TLS on port {}", host, port);
			info("Server requires TLS; reconnecting securely on port " + port + "...");
			synchronized (IrcPlugin.this)
			{
				irc = null; // the old connection is closing; ignore its disconnect callback
			}
			scheduleConnect(0);
		}

		@Override
		public void onStatus(String text)
		{
			if (!stale())
			{
				info(text);
			}
		}

		@Override
		public void onDisconnected(String reason, boolean requested)
		{
			synchronized (IrcPlugin.this)
			{
				if (stale())
				{
					// Detached by shutDown() or a reconnect; nothing to show in chat, but log that it closed.
					log.info("Previous IRC connection closed ({})", reason);
					return;
				}
				irc = null;
			}
			IrcPanel p = panel;
			if (p != null)
			{
				p.setChannels(new ArrayList<>(), null);
				p.clearUsers();
			}
			if (requested || !wantConnected)
			{
				log.info("Disconnected from IRC");
				info("Disconnected from IRC.");
				updatePanelStatus();
				return;
			}
			long delay = reconnectDelaySeconds;
			reconnectDelaySeconds = Math.min(reconnectDelaySeconds * 2, MAX_RECONNECT_SECONDS);
			log.info("Disconnected from IRC ({}); reconnecting in {}s", reason, delay);
			info("Disconnected (" + reason + "). Reconnecting in " + delay + "s...");
			if (p != null)
			{
				p.setStatus("Reconnecting in " + delay + "s");
			}
			scheduleConnect(delay);
		}
	}

	private void updatePanelStatus()
	{
		IrcPanel p = panel;
		if (p == null)
		{
			return;
		}
		IrcClient c = irc;
		if (c != null && c.isRegistered())
		{
			// The channel is in the panel's channel box, so the status line names the network.
			p.setStatus(c.getNick() + " on " + (c.getNetwork() != null ? c.getNetwork() : config.server()));
		}
		else
		{
			p.setStatus(c != null ? "Connecting..." : "Not connected");
		}
	}

	// ------------------------------------------------------------------
	// Auto-away and highlights
	// ------------------------------------------------------------------

	/**
	 * Marks us away (or back) for auto-away. Never touches an away message the player set
	 * themselves. {@code force} clears an auto-away even when the setting has just been turned off.
	 */
	private void setAutoAway(boolean away, boolean force)
	{
		if ((!config.autoAway() && !force) || manualAway)
		{
			return;
		}
		IrcClient c = irc;
		if (c == null || !c.isRegistered())
		{
			return;
		}
		if (away && !autoAwaySet)
		{
			c.setAway(config.autoAwayMessage().trim().isEmpty() ? "Logged out of the game" : config.autoAwayMessage().trim());
			autoAwaySet = true;
			log.info("Marked away on IRC (logged out of the game)");
		}
		else if (!away && autoAwaySet)
		{
			c.setAway(null);
			autoAwaySet = false;
			log.info("Marked back on IRC");
		}
	}

	/** Our nick, or any of the configured highlight words, as a whole word. */
	private boolean isHighlight(String text, String nick)
	{
		if (mentions(text, nick))
		{
			return true;
		}
		for (String word : config.highlightWords().split(","))
		{
			if (!word.trim().isEmpty() && mentions(text, word.trim()))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean mentions(String text, String nick)
	{
		if (nick == null || nick.isEmpty())
		{
			return false;
		}
		return Pattern.compile("(?i)(^|[^A-Za-z0-9_])" + Pattern.quote(nick) + "($|[^A-Za-z0-9_])")
			.matcher(text).find();
	}

	// ------------------------------------------------------------------
	// Output: every line goes to the side panel and (optionally) the chatbox
	// ------------------------------------------------------------------

	/** One displayable line. {@code nick} is null for status lines. */
	static final class Line
	{
		final Instant time;
		/** "#channel", "PM", "PM → nick", "Notice" or "IRC". */
		final String tag;
		final String nick;
		final boolean action;
		final String text;
		/** Mentions us, matches a highlight word, or is a PM to us. */
		final boolean highlight;
		/** A message in our channel (the panel leaves out the channel tag). */
		final boolean channelMessage;

		Line(Instant time, String tag, String nick, boolean action, String text, boolean highlight, boolean channelMessage)
		{
			this.time = time == null ? Instant.now() : time;
			this.tag = tag;
			this.nick = nick;
			this.action = action;
			this.text = text;
			this.highlight = highlight;
			this.channelMessage = channelMessage;
		}
	}

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	private void emit(Line line)
	{
		IrcPanel p = panel;
		if (p != null && navButtonAdded)
		{
			p.append(line);
		}
		// With the panel hidden, the chatbox is the only place to see IRC, so always use it then.
		if (config.chatboxMessages() || !navButtonAdded)
		{
			show(chatboxFormat(line));
		}
	}

	private String chatboxFormat(Line line)
	{
		StringBuilder sb = new StringBuilder();
		if (config.showTimestamps())
		{
			sb.append(col(TIMESTAMP.format(line.time) + " ", config.messageColor()));
		}
		sb.append(tag(line.tag)).append(' ');
		if (line.nick == null)
		{
			sb.append(col(line.text, config.messageColor()));
		}
		else if (line.action)
		{
			sb.append(col("* ", config.messageColor())).append(nick(line.nick)).append(col(" " + line.text, config.messageColor()));
		}
		else
		{
			sb.append(nick(line.nick)).append(col(": " + line.text, config.messageColor()));
		}
		return sb.toString();
	}

	/** Our own channel message shown locally (when the server doesn't echo it back). */
	private void showMessage(String channel, String nick, String text)
	{
		emit(new Line(null, channel, nick, false, text, false, true));
	}

	private void showAction(String channel, String nick, String text)
	{
		emit(new Line(null, channel, nick, true, text, false, true));
	}

	private void showOutgoingPm(String nick, String text)
	{
		emit(new Line(null, "PM → " + nick, null, false, text, false, false));
	}

	private void info(String text)
	{
		emit(new Line(null, "IRC", null, false, text, false, false));
	}

	/**
	 * Mid-dark colours that stay readable on the default (parchment) chatbox, matching the
	 * default black message text. The side panel has a lighter set in the same order.
	 */
	private static final Color[] NICK_PALETTE = {
		new Color(0x0060C0), // blue
		new Color(0xB00000), // red
		new Color(0x007A00), // green
		new Color(0xB05000), // orange
		new Color(0x7A00B0), // purple
		new Color(0x007878), // teal
		new Color(0xB0005A), // magenta
		new Color(0x606000), // olive
	};

	/** A coloured nick for the chatbox: its own palette colour, or the configured nick colour. */
	private String nick(String nick)
	{
		return col(nick, config.colorNicks() ? nickColorFor(fold(nick)) : config.nickColor());
	}

	/** Stable chatbox palette colour for an already-casefolded nick. */
	static Color nickColorFor(String foldedNick)
	{
		return NICK_PALETTE[nickPaletteIndex(foldedNick, NICK_PALETTE.length)];
	}

	/** String.hashCode is specified by the JLS, so a nick keeps its colour across restarts. */
	static int nickPaletteIndex(String foldedNick, int paletteSize)
	{
		return Math.floorMod(foldedNick.hashCode(), paletteSize);
	}

	/** "3m", "2h 5m", "4d 1h". */
	static String duration(long seconds)
	{
		if (seconds < 60)
		{
			return seconds + "s";
		}
		long m = seconds / 60;
		if (m < 60)
		{
			return m + "m";
		}
		long h = m / 60;
		if (h < 24)
		{
			return h + "h" + (m % 60 == 0 ? "" : " " + m % 60 + "m");
		}
		long d = h / 24;
		return d + "d" + (h % 24 == 0 ? "" : " " + h % 24 + "h");
	}

	private static String ago(Instant when)
	{
		return duration(Math.max(0, Instant.now().getEpochSecond() - when.getEpochSecond()));
	}

	private String tag(String label)
	{
		return col("[" + label + "]", config.tagColor());
	}

	/** Escapes text so IRC users can't inject RuneLite <col>/<img> tags, then colours it. */
	private static String col(String text, Color color)
	{
		return ColorUtil.wrapWithColorTag(Text.escapeJagex(text), color);
	}

	private void show(String formatted)
	{
		synchronized (heldMessages)
		{
			if (!canShow)
			{
				// Logged out: other plugins (e.g. Loot Tracker) assume a local player when they see a
				// game message, so hold IRC lines until a character is loaded.
				if (heldMessages.size() >= MAX_HELD_MESSAGES)
				{
					heldMessages.removeFirst();
					droppedMessages++;
				}
				heldMessages.addLast(formatted);
				return;
			}
		}
		post(formatted);
	}

	private void post(String formatted)
	{
		// ChatMessageManager queues messages and adds them on the client thread.
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted)
			.build());
	}

	/** Game ticks only fire while logged in, so this is where held messages are released. */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (canShow || client.getLocalPlayer() == null)
		{
			return;
		}
		List<String> release;
		int dropped;
		synchronized (heldMessages)
		{
			canShow = true;
			release = new ArrayList<>(heldMessages);
			heldMessages.clear();
			dropped = droppedMessages;
			droppedMessages = 0;
		}
		if (dropped > 0)
		{
			post(tag("IRC") + " " + col(dropped + " older IRC message" + (dropped == 1 ? " was" : "s were")
				+ " dropped while you were logged out.", config.messageColor()));
		}
		release.forEach(this::post);
		setAutoAway(false, false);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			synchronized (heldMessages)
			{
				canShow = false;
			}
		}
		// Only the login screen counts as logged out; loading and world hops pass through other states.
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			setAutoAway(true, false);
		}
	}

	private void clearHeldMessages()
	{
		synchronized (heldMessages)
		{
			canShow = false;
			heldMessages.clear();
			droppedMessages = 0;
		}
	}
}
