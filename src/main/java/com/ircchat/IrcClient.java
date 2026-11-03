package com.ircchat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small, dependency-free IRC client with a barebones implementation of the IRCv3 core.
 *
 * <p>Specs implemented (see https://ircv3.net/irc/ and https://modern.ircdocs.horse/):
 * <ul>
 *   <li>Capability negotiation 3.2 ({@code CAP LS 302}, multi-line LS, REQ/ACK/NAK, cap-notify NEW/DEL)</li>
 *   <li>message-tags (parsing, escaping, client-only tags), server-time, account-tag, msgid</li>
 *   <li>SASL 3.1/3.2 with the PLAIN mechanism (chunked AUTHENTICATE)</li>
 *   <li>echo-message, batch (netsplit/netjoin are collapsed), multi-prefix, userhost-in-names</li>
 *   <li>extended-join, account-notify, away-notify, chghost, setname, invite-notify</li>
 *   <li>sts (upgrade from plaintext to TLS), standard replies (FAIL/WARN/NOTE)</li>
 *   <li>RPL_ISUPPORT (PREFIX, CHANMODES, CHANTYPES, CASEMAPPING, NICKLEN, NETWORK)</li>
 * </ul>
 *
 * <p>Not implemented: labeled-response, chathistory, multiline, monitor, SASL mechanisms other than PLAIN,
 * persistent STS policies. One instance = one connection attempt. Listener callbacks run on the reader thread.
 */
public class IrcClient
{
	// ------------------------------------------------------------------
	// Public types
	// ------------------------------------------------------------------

	public interface Listener
	{
		/** Registered with the server (numeric 001). */
		default void onRegistered(String nick)
		{
		}

		/** Capability negotiation finished (or changed via cap-notify). */
		default void onCapsChanged(Set<String> enabled)
		{
		}

		/** SASL or NickServ login succeeded. */
		default void onLoggedIn(String account)
		{
		}

		default void onSelfJoin(String channel)
		{
		}

		/** PRIVMSG to a channel or to us, including our own messages when echo-message is enabled. */
		default void onMessage(ChatEvent event)
		{
		}

		default void onNotice(ChatEvent event)
		{
		}

		default void onJoin(String channel, User user)
		{
		}

		default void onPart(String channel, String nick, String reason)
		{
		}

		default void onQuit(String nick, String reason)
		{
		}

		default void onKick(String channel, String kicked, String by, String reason)
		{
		}

		default void onNickChange(String oldNick, String newNick, boolean self)
		{
		}

		/** Full member list for a channel, after a NAMES reply finishes. */
		default void onNames(String channel, List<User> users)
		{
		}

		default void onTopic(String channel, String topic)
		{
		}

		/** RPL_TOPICWHOTIME: who set the current topic and when ({@code setAt} may be null). */
		default void onTopicInfo(String channel, String setBy, Instant setAt)
		{
		}

		/** Someone changed the topic while we were in the channel. */
		default void onTopicChange(String channel, String nick, String topic)
		{
		}

		/**
		 * A channel's member list, or someone's prefixes/away state, changed; call {@link #getUsers} for it.
		 * {@code channel} is null when the change could affect every channel (e.g. a nick change).
		 */
		default void onUsersChanged(String channel)
		{
		}

		/** Registration and the MOTD are done; ISUPPORT (e.g. MONITOR) is known. */
		default void onReady()
		{
		}

		/** MONITOR: watched nicks came online ({@code online}) or went offline. */
		default void onMonitor(List<String> nicks, boolean online)
		{
		}

		/** A WHOIS reply finished (RPL_ENDOFWHOIS). Not called when the nick doesn't exist. */
		default void onWhois(Whois whois)
		{
		}

		/** away-notify. {@code message} is null when the user came back. */
		default void onAway(String nick, String message)
		{
		}

		/** account-notify. {@code account} is null when the user logged out. */
		default void onAccount(String nick, String account)
		{
		}

		/** invite-notify, or a direct invite to us. */
		default void onInvite(String by, String target, String channel)
		{
		}

		/** A netsplit/netjoin batch ended. Individual QUIT/JOIN callbacks are suppressed while it is open. */
		default void onNetBatch(String type, String servers, List<String> nicks)
		{
		}

		/** FAIL, WARN or NOTE. */
		default void onStandardReply(String type, String command, String code, String description)
		{
		}

		/** The server has an STS policy: reconnect with TLS on {@code port}. This connection is closing. */
		default void onStsUpgrade(int port)
		{
		}

		/** Server errors and other informational text worth showing the user. */
		default void onStatus(String text)
		{
		}

		/** Connection ended. {@code requested} is true when we called {@link #disconnect}. */
		void onDisconnected(String reason, boolean requested);
	}

	public static final class Settings
	{
		public String host;
		public int port = 6697;
		public boolean tls = true;
		public String nick;
		public String user = "runelite";
		public String realName = "RuneLite IRC Chat";
		public String serverPassword = "";
		/** Main channel, joined with {@link #channelKey}. */
		public String channel = "";
		public String channelKey = "";
		/** More channels to join after registering (no keys). */
		public List<String> extraChannels = new ArrayList<>();
		/** SASL PLAIN account; blank = no SASL. */
		public String saslAccount = "";
		public String saslPassword = "";
		/** Fallback when SASL is not configured or not available. */
		public String nickservPassword = "";
		public String version = "RuneLite IRC Chat";
	}

	/** A PRIVMSG or NOTICE with its IRCv3 metadata. */
	public static final class ChatEvent
	{
		public final String target;
		public final String sender;
		public final String text;
		public final boolean action;
		/** Our own message echoed back by the server (echo-message). */
		public final boolean self;
		/** True when sent to a channel rather than directly to a nick. */
		public final boolean channel;
		/** account-tag; null if not logged in or not available. */
		public final String account;
		/** server-time if available, otherwise when we received it. */
		public final Instant time;
		/** msgid tag, or null. */
		public final String msgId;
		public final Map<String, String> tags;

		ChatEvent(String target, String sender, String text, boolean action, boolean self, boolean channel,
			Map<String, String> tags)
		{
			this.target = target;
			this.sender = sender;
			this.text = text;
			this.action = action;
			this.self = self;
			this.channel = channel;
			this.tags = Collections.unmodifiableMap(tags);
			String acct = tags.get("account");
			this.account = acct == null || acct.isEmpty() || acct.equals("*") ? null : acct;
			this.time = parseTime(tags.get("time"));
			String id = tags.get("msgid");
			this.msgId = id == null || id.isEmpty() ? null : id;
		}
	}

	/** Collected WHOIS reply. Fields are null/0/false when the server didn't send them. */
	public static final class Whois
	{
		public String nick;
		public String user;
		public String host;
		public String realName;
		public String server;
		public String account;
		public String awayMessage;
		public String channels;
		/** Seconds idle, or -1 if unknown. */
		public long idleSeconds = -1;
		public Instant signedOn;
		public boolean operator;
		public boolean secure;

		Whois(String nick)
		{
			this.nick = nick;
		}
	}

	/** A channel member, as tracked from NAMES/JOIN/MODE/AWAY/ACCOUNT/CHGHOST/SETNAME. Snapshots are immutable. */
	public static final class User
	{
		private String nick;
		/** Channel status symbols, highest rank first, e.g. "@+". */
		private String prefixes = "";
		private String user;
		private String host;
		private String account;
		private String realName;
		private boolean away;
		private String awayMessage;

		User(String nick)
		{
			this.nick = nick;
		}

		User copy()
		{
			User u = new User(nick);
			u.prefixes = prefixes;
			u.user = user;
			u.host = host;
			u.account = account;
			u.realName = realName;
			u.away = away;
			u.awayMessage = awayMessage;
			return u;
		}

		public String getNick()
		{
			return nick;
		}

		public String getPrefixes()
		{
			return prefixes;
		}

		/** Highest status symbol, or "" for none. */
		public String getPrefix()
		{
			return prefixes.isEmpty() ? "" : prefixes.substring(0, 1);
		}

		public String getUser()
		{
			return user;
		}

		public String getHost()
		{
			return host;
		}

		public String getAccount()
		{
			return account;
		}

		public String getRealName()
		{
			return realName;
		}

		public boolean isAway()
		{
			return away;
		}

		public String getAwayMessage()
		{
			return awayMessage;
		}

		@Override
		public String toString()
		{
			return getPrefix() + nick;
		}
	}

	// ------------------------------------------------------------------
	// Constants
	// ------------------------------------------------------------------

	static final int CONNECT_TIMEOUT_MS = 15_000;
	static final int READ_TIMEOUT_MS = 180_000;
	/** Bytes of message text per PRIVMSG; leaves room for the prefix servers prepend when relaying. */
	static final int MAX_TEXT_BYTES = 400;
	static final int DEFAULT_NICK_LENGTH = 16;
	static final int SASL_CHUNK = 400;

	/** Capabilities we ask for when the server offers them. sasl is added only when credentials are set. */
	static final List<String> WANTED_CAPS = Arrays.asList(
		"message-tags", "server-time", "echo-message", "batch", "multi-prefix", "userhost-in-names",
		"extended-join", "account-notify", "account-tag", "away-notify", "chghost", "setname",
		"invite-notify", "cap-notify");

	// ------------------------------------------------------------------
	// State
	// ------------------------------------------------------------------

	private final Settings settings;
	private final Listener listener;
	private final BlockingQueue<String> outQueue = new LinkedBlockingQueue<>();

	private volatile Socket socket;
	private volatile boolean requestedClose;
	private volatile boolean registered;
	private volatile String currentNick;
	private volatile String account;
	private int nickAttempts;

	// CAP negotiation (reader thread only, except the concurrent sets)
	private boolean capNegotiating;
	private boolean lsInProgress;
	private int pendingReqs;
	private boolean saslInProgress;
	private final Map<String, String> availableCaps = new LinkedHashMap<>();
	private final Set<String> enabledCaps = ConcurrentHashMap.newKeySet();

	// ISUPPORT
	private volatile String prefixModes = "ov";
	private volatile String prefixSymbols = "@+";
	private volatile String[] chanModes = {"beI", "k", "l", "imnpst"};
	private volatile String chanTypes = "#&";
	private volatile String caseMapping = "rfc1459";
	private volatile int nickLength = DEFAULT_NICK_LENGTH;
	private volatile String network;

	/** A channel we're in and its members, keyed by casefolded nick. */
	private static final class Chan
	{
		final String name;
		final Map<String, User> users = new LinkedHashMap<>();

		Chan(String name)
		{
			this.name = name;
		}
	}

	// Joined channels in join order, keyed by casefolded name. Guarded by itself.
	private final Map<String, Chan> channels = new LinkedHashMap<>();
	// NAMES replies being collected, keyed by casefolded channel (reader thread only). Also used
	// for channels we're not in, so ::inames #other works.
	private final Map<String, Map<String, User>> pendingNames = new HashMap<>();

	// MONITOR (watch list): 0 = not supported, -1 = no limit, otherwise the most nicks allowed.
	private volatile int monitorLimit;

	// Open batches: ref -> batch
	private final Map<String, Batch> batches = new HashMap<>();

	// WHOIS replies being collected, keyed by casefolded nick (reader thread only).
	private final Map<String, Whois> pendingWhois = new HashMap<>();

	private Thread readerThread;
	private Thread writerThread;

	public IrcClient(Settings settings, Listener listener)
	{
		this.settings = settings;
		this.listener = listener;
		this.currentNick = settings.nick;
	}

	// ------------------------------------------------------------------
	// Accessors
	// ------------------------------------------------------------------

	Listener getListener()
	{
		return listener;
	}

	public String getNick()
	{
		return currentNick;
	}

	public boolean isRegistered()
	{
		Socket s = socket;
		return registered && s != null && !s.isClosed();
	}

	/** Channels we're currently in, in the order we joined them. */
	public List<String> getChannels()
	{
		synchronized (channels)
		{
			List<String> out = new ArrayList<>(channels.size());
			for (Chan c : channels.values())
			{
				out.add(c.name);
			}
			return out;
		}
	}

	/** The joined channel's name as the server spells it, or null if we're not in it. */
	public String findChannel(String channel)
	{
		synchronized (channels)
		{
			Chan c = channels.get(casefold(channel));
			return c == null ? null : c.name;
		}
	}

	/** Whether the server supports MONITOR (the watch list). */
	public boolean supportsMonitor()
	{
		return monitorLimit != 0;
	}

	/** Most nicks the server lets us MONITOR, or -1 for no limit (0 = not supported). */
	public int getMonitorLimit()
	{
		return monitorLimit;
	}

	public boolean hasCap(String cap)
	{
		return enabledCaps.contains(cap);
	}

	public Set<String> getEnabledCaps()
	{
		return Collections.unmodifiableSet(new LinkedHashSet<>(enabledCaps));
	}

	/** Account we're logged in to (SASL/NickServ), or null. */
	public String getAccount()
	{
		return account;
	}

	public String getNetwork()
	{
		return network;
	}

	public int getNickLength()
	{
		return nickLength;
	}

	/** Snapshot of a joined channel's members (empty if we're not in it). */
	public List<User> getUsers(String channel)
	{
		synchronized (channels)
		{
			Chan c = channels.get(casefold(channel));
			if (c == null)
			{
				return new ArrayList<>();
			}
			List<User> out = new ArrayList<>(c.users.size());
			for (User u : c.users.values())
			{
				out.add(u.copy());
			}
			return out;
		}
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	public synchronized void connect()
	{
		if (readerThread != null)
		{
			throw new IllegalStateException("IrcClient instances are single-use");
		}
		readerThread = new Thread(this::run, "irc-chat-reader");
		readerThread.setDaemon(true);
		readerThread.start();
	}

	/** Sends QUIT and closes the connection. Safe to call more than once. */
	public void disconnect(String reason)
	{
		requestedClose = true;
		Socket s = socket;
		if (s != null && !s.isClosed())
		{
			try
			{
				// Bypass the throttled queue so the QUIT goes out before we close.
				writeNow("QUIT :" + sanitize(reason == null ? "" : reason));
			}
			catch (IOException ignored)
			{
			}
		}
		closeQuietly();
		Thread r = readerThread;
		if (r != null)
		{
			r.interrupt();
		}
	}

	private void run()
	{
		String reason = "Connection closed";
		try
		{
			open();
			writerThread = new Thread(this::writeLoop, "irc-chat-writer");
			writerThread.setDaemon(true);
			writerThread.start();

			// CAP LS before NICK/USER holds registration until we send CAP END.
			capNegotiating = true;
			lsInProgress = true;
			sendRaw("CAP LS 302");
			if (!isBlank(settings.serverPassword))
			{
				sendRaw("PASS " + settings.serverPassword);
			}
			sendRaw("NICK " + currentNick);
			sendRaw("USER " + settings.user + " 0 * :" + settings.realName);

			readLoop();
		}
		catch (IOException e)
		{
			reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		}
		catch (ServerError e)
		{
			reason = e.getMessage();
		}
		finally
		{
			registered = false;
			closeQuietly();
			if (writerThread != null)
			{
				writerThread.interrupt();
			}
			listener.onDisconnected(requestedClose ? "Disconnected" : reason, requestedClose);
		}
	}

	private void open() throws IOException
	{
		Socket raw = new Socket();
		socket = raw;
		raw.connect(new InetSocketAddress(settings.host, settings.port), CONNECT_TIMEOUT_MS);
		raw.setKeepAlive(true);

		Socket s = raw;
		if (settings.tls)
		{
			SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			SSLSocket ssl = (SSLSocket) factory.createSocket(raw, settings.host, settings.port, true);
			SSLParameters params = ssl.getSSLParameters();
			// Verify the certificate matches the host name.
			params.setEndpointIdentificationAlgorithm("HTTPS");
			ssl.setSSLParameters(params);
			ssl.startHandshake();
			s = ssl;
		}
		s.setSoTimeout(READ_TIMEOUT_MS);
		socket = s;
		if (requestedClose)
		{
			throw new IOException("Disconnected");
		}
	}

	private void closeQuietly()
	{
		Socket s = socket;
		if (s != null)
		{
			try
			{
				s.close();
			}
			catch (IOException ignored)
			{
			}
		}
	}

	// ------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------

	private static final class ServerError extends Exception
	{
		private static final long serialVersionUID = 1L;

		ServerError(String msg)
		{
			super(msg);
		}
	}

	private static final class Batch
	{
		final String type;
		final List<String> params;
		final List<String> nicks = new ArrayList<>();

		Batch(String type, List<String> params)
		{
			this.type = type;
			this.params = params;
		}
	}

	private void readLoop() throws IOException, ServerError
	{
		BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		boolean pingSent = false;
		while (!requestedClose)
		{
			String line;
			try
			{
				line = in.readLine();
			}
			catch (SocketTimeoutException e)
			{
				// Quiet for a while: check the connection is still alive.
				if (pingSent)
				{
					throw new IOException("Ping timeout");
				}
				sendRaw("PING :keepalive");
				pingSent = true;
				continue;
			}
			if (line == null)
			{
				throw new IOException("Server closed the connection");
			}
			pingSent = false;
			if (!line.isEmpty())
			{
				handle(Message.parse(line));
			}
		}
	}

	void handle(Message m) throws ServerError, IOException
	{
		if (m == null)
		{
			return;
		}
		String nick = m.nick();
		Batch batch = m.tags.containsKey("batch") ? batches.get(m.tags.get("batch")) : null;

		switch (m.command)
		{
			case "PING":
				sendRaw("PONG :" + m.param(0));
				return;
			case "ERROR":
				throw new ServerError(m.param(0).isEmpty() ? "Server error" : m.param(0));
			case "CAP":
				handleCap(m);
				return;
			case "AUTHENTICATE":
				handleAuthenticate(m.param(0));
				return;
			case "001":
				registered = true;
				capNegotiating = false; // server without CAP support, or we already ended
				currentNick = m.param(0);
				listener.onRegistered(currentNick);
				if (account == null && !isBlank(settings.nickservPassword))
				{
					sendRawPrivate("PRIVMSG NickServ :IDENTIFY " + settings.nickservPassword);
				}
				if (!isBlank(settings.channel))
				{
					join(settings.channel, settings.channelKey);
				}
				for (String extra : settings.extraChannels)
				{
					if (!isBlank(extra) && !casefold(extra).equals(casefold(settings.channel)))
					{
						join(extra, null);
					}
				}
				return;
			case "005":
				handleIsupport(m);
				return;
			case "376": // RPL_ENDOFMOTD
			case "422": // ERR_NOMOTD
				// ISUPPORT has arrived by now, so features like MONITOR are known.
				listener.onReady();
				return;
			case "730": // RPL_MONONLINE: <me> :nick!user@host[,...]
			case "731": // RPL_MONOFFLINE: <me> :nick[,...]
			{
				List<String> nicks = new ArrayList<>();
				for (String target : m.trailing().split(","))
				{
					int bang = target.indexOf('!');
					String n = (bang >= 0 ? target.substring(0, bang) : target).trim();
					if (!n.isEmpty())
					{
						nicks.add(n);
					}
				}
				listener.onMonitor(nicks, "730".equals(m.command));
				return;
			}
			case "734": // ERR_MONLISTFULL: <me> <limit> <targets> :Monitor list is full
				listener.onStatus("Watch list is full (the server allows " + m.param(1) + "); not watching " + m.param(2) + ".");
				return;
			case "410": // ERR_INVALIDCAPCMD
				endCapNegotiation();
				return;
			case "421": // ERR_UNKNOWNCOMMAND, e.g. a server that doesn't know CAP
				if ("CAP".equalsIgnoreCase(m.param(1)))
				{
					endCapNegotiation();
				}
				else
				{
					listener.onStatus(m.param(1) + ": " + m.trailing());
				}
				return;
			case "432": // erroneous nickname
			case "433": // nickname in use
			case "437": // temporarily unavailable
				if (!registered)
				{
					String next = alternateNick(settings.nick, ++nickAttempts, nickLength);
					listener.onStatus("Nick " + currentNick + " unavailable, trying " + next);
					currentNick = next;
					sendRaw("NICK " + next);
				}
				else
				{
					listener.onStatus(m.trailing());
				}
				return;
			// --- SASL numerics ---
			case "900": // RPL_LOGGEDIN <nick> <nick!user@host> <account> :msg
				account = m.param(2);
				listener.onLoggedIn(account);
				return;
			case "901": // RPL_LOGGEDOUT
				account = null;
				return;
			case "903": // RPL_SASLSUCCESS
				saslInProgress = false;
				maybeEndCap();
				return;
			case "902": // ERR_NICKLOCKED
			case "904": // ERR_SASLFAIL
			case "905": // ERR_SASLTOOLONG
			case "906": // ERR_SASLABORTED
			case "907": // ERR_SASLALREADY
				if (saslInProgress)
				{
					listener.onStatus("SASL login failed: " + m.trailing());
					saslInProgress = false;
					maybeEndCap();
				}
				return;
			case "908": // RPL_SASLMECHS
				return;
			// --- standard replies ---
			case "FAIL":
			case "WARN":
			case "NOTE":
				listener.onStandardReply(m.command, m.param(0), m.param(1), m.trailing());
				return;
			case "BATCH":
				handleBatch(m);
				return;
			case "JOIN":
				handleJoin(m, nick, batch);
				return;
			case "PART":
				if (isSelf(nick))
				{
					removeChannel(m.param(0));
				}
				else
				{
					removeUser(m.param(0), nick);
				}
				listener.onPart(m.param(0), nick, m.param(1));
				return;
			case "QUIT":
				removeUserEverywhere(nick);
				if (batch != null && isNetBatch(batch))
				{
					batch.nicks.add(nick);
				}
				else
				{
					listener.onQuit(nick, m.param(0));
				}
				return;
			case "KICK":
				if (isSelf(m.param(1)))
				{
					removeChannel(m.param(0));
				}
				else
				{
					removeUser(m.param(0), m.param(1));
				}
				listener.onKick(m.param(0), m.param(1), nick, m.param(2));
				return;
			case "NICK":
			{
				boolean self = isSelf(nick);
				if (self)
				{
					currentNick = m.param(0);
				}
				renameUser(nick, m.param(0));
				listener.onNickChange(nick, m.param(0), self);
				return;
			}
			case "MODE":
				handleMode(m);
				return;
			case "AWAY":
			{
				String msg = m.params.isEmpty() ? null : m.param(0);
				updateUser(nick, u ->
				{
					u.away = msg != null;
					u.awayMessage = msg;
				});
				listener.onAway(nick, msg);
				return;
			}
			case "ACCOUNT":
			{
				String acct = "*".equals(m.param(0)) ? null : m.param(0);
				updateUser(nick, u -> u.account = acct);
				if (isSelf(nick))
				{
					account = acct;
				}
				listener.onAccount(nick, acct);
				return;
			}
			case "CHGHOST":
				updateUser(nick, u ->
				{
					u.user = m.param(0);
					u.host = m.param(1);
				});
				return;
			case "SETNAME":
				updateUser(nick, u -> u.realName = m.param(0));
				return;
			case "INVITE":
				listener.onInvite(nick, m.param(0), m.param(1));
				return;
			case "PRIVMSG":
				handlePrivmsg(m, nick);
				return;
			case "NOTICE":
				if (!registered || nick.isEmpty() || nick.contains(".") || isSelf(nick))
				{
					// Server notices are noisy during connect; our own notices are CTCP replies.
					return;
				}
				listener.onNotice(new ChatEvent(m.param(0), nick, stripFormatting(m.param(1)), false, false,
					isChannel(m.param(0)), m.tags));
				return;
			case "TAGMSG":
				// Client-only tags such as +typing; nothing to display.
				return;
			case "332": // RPL_TOPIC: <me> <channel> :<topic>
				listener.onTopic(m.param(1), stripFormatting(m.param(2)));
				return;
			case "331": // RPL_NOTOPIC: <me> <channel> :No topic is set
				listener.onStatus("No topic is set for " + m.param(1) + ".");
				return;
			case "333": // RPL_TOPICWHOTIME: <me> <channel> <setter> <unix time>
			{
				String setter = m.param(2);
				int bang = setter.indexOf('!');
				listener.onTopicInfo(m.param(1), bang >= 0 ? setter.substring(0, bang) : setter,
					parseEpochSeconds(m.param(3)));
				return;
			}
			case "TOPIC": // <channel> :<new topic>
				listener.onTopicChange(m.param(0), nick, stripFormatting(m.param(1)));
				return;
			case "311": // RPL_WHOISUSER: <me> <nick> <user> <host> * :<real name>
			{
				Whois w = whoisFor(m.param(1));
				w.nick = m.param(1);
				w.user = m.param(2);
				w.host = m.param(3);
				w.realName = stripFormatting(m.param(5));
				return;
			}
			case "312": // RPL_WHOISSERVER: <me> <nick> <server> :<info>
				whoisFor(m.param(1)).server = m.param(2);
				return;
			case "313": // RPL_WHOISOPERATOR
				whoisFor(m.param(1)).operator = true;
				return;
			case "317": // RPL_WHOISIDLE: <me> <nick> <idle secs> [<signon>] :seconds idle
			{
				Whois w = whoisFor(m.param(1));
				try
				{
					w.idleSeconds = Long.parseLong(m.param(2));
				}
				catch (NumberFormatException ignored)
				{
				}
				if (m.params.size() >= 5)
				{
					w.signedOn = parseEpochSeconds(m.param(3));
				}
				return;
			}
			case "319": // RPL_WHOISCHANNELS: <me> <nick> :<channels>
			{
				Whois w = whoisFor(m.param(1));
				String chans = m.param(2).trim();
				w.channels = w.channels == null ? chans : w.channels + " " + chans;
				return;
			}
			case "330": // RPL_WHOISACCOUNT: <me> <nick> <account> :is logged in as
				whoisFor(m.param(1)).account = m.param(2);
				return;
			case "301": // RPL_AWAY: <me> <nick> :<message>; also sent when messaging an away user
			{
				Whois w = pendingWhois.get(casefold(m.param(1)));
				if (w != null)
				{
					w.awayMessage = stripFormatting(m.param(2));
				}
				else
				{
					// We messaged someone who is away.
					listener.onStatus(m.param(1) + " is away: " + stripFormatting(m.param(2)));
				}
				return;
			}
			case "671": // RPL_WHOISSECURE
				whoisFor(m.param(1)).secure = true;
				return;
			case "318": // RPL_ENDOFWHOIS: <me> <nick> :End of /WHOIS list
			{
				Whois w = pendingWhois.remove(casefold(m.param(1)));
				if (w != null)
				{
					listener.onWhois(w);
				}
				return;
			}
			case "353": // RPL_NAMREPLY: <me> <=|*|@> <channel> :<names>
			{
				Map<String, User> names = pendingNames.computeIfAbsent(casefold(m.param(2)), k -> new LinkedHashMap<>());
				for (String entry : m.trailing().split(" "))
				{
					if (!entry.isEmpty())
					{
						User u = parseNamesEntry(entry);
						names.put(casefold(u.nick), u);
					}
				}
				return;
			}
			case "366": // RPL_ENDOFNAMES: <me> <channel> :End
			{
				String channel = m.param(1);
				Map<String, User> names = pendingNames.remove(casefold(channel));
				if (names == null)
				{
					names = new LinkedHashMap<>();
				}
				List<User> snapshot = new ArrayList<>(names.size());
				synchronized (channels)
				{
					Chan c = channels.get(casefold(channel));
					if (c != null)
					{
						// Keep what we learned from extended-join / away-notify for users still present.
						for (Map.Entry<String, User> e : names.entrySet())
						{
							User old = c.users.get(e.getKey());
							if (old != null)
							{
								User n = e.getValue();
								if (n.account == null)
								{
									n.account = old.account;
								}
								if (n.realName == null)
								{
									n.realName = old.realName;
								}
								n.away = old.away;
								n.awayMessage = old.awayMessage;
							}
						}
						c.users.clear();
						c.users.putAll(names);
					}
					for (User u : names.values())
					{
						snapshot.add(u.copy());
					}
				}
				listener.onUsersChanged(channel);
				listener.onNames(channel, snapshot);
				return;
			}
			default:
				if (m.command.length() == 3 && (m.command.charAt(0) == '4' || m.command.charAt(0) == '5'))
				{
					// Error numerics: show "<subject>: <text>" e.g. "#chan: Cannot join channel (+i)"
					String subject = m.params.size() >= 3 ? m.param(1) + ": " : "";
					listener.onStatus(subject + m.trailing());
				}
		}
	}

	// ------------------------------------------------------------------
	// CAP / SASL / STS
	// ------------------------------------------------------------------

	private void handleCap(Message m) throws IOException
	{
		// CAP <nick|*> <subcommand> [*] :<caps>
		String sub = m.param(1).toUpperCase();
		boolean more = m.params.size() >= 4 && "*".equals(m.param(2));
		String list = m.trailing();

		switch (sub)
		{
			case "LS":
				availableCaps.putAll(parseCapList(list));
				if (more)
				{
					return;
				}
				lsInProgress = false;
				if (checkSts())
				{
					return;
				}
				requestCaps(availableCaps.keySet(), true);
				maybeEndCap();
				return;
			case "ACK":
				for (String cap : list.trim().split(" +"))
				{
					if (cap.isEmpty())
					{
						continue;
					}
					if (cap.startsWith("-"))
					{
						enabledCaps.remove(cap.substring(1));
					}
					else
					{
						enabledCaps.add(cap);
						if (cap.equals("sasl") && capNegotiating && saslCredentialsSet())
						{
							startSasl();
						}
					}
				}
				if (!more)
				{
					pendingReqs = Math.max(0, pendingReqs - 1);
					listener.onCapsChanged(getEnabledCaps());
					maybeEndCap();
				}
				return;
			case "NAK":
				if (!more)
				{
					pendingReqs = Math.max(0, pendingReqs - 1);
					maybeEndCap();
				}
				return;
			case "NEW":
			{
				Map<String, String> added = parseCapList(list);
				availableCaps.putAll(added);
				if (checkSts())
				{
					return;
				}
				requestCaps(added.keySet(), false);
				return;
			}
			case "DEL":
				for (String cap : list.trim().split(" +"))
				{
					availableCaps.remove(cap);
					enabledCaps.remove(cap);
				}
				listener.onCapsChanged(getEnabledCaps());
				return;
			case "LIST":
			default:
		}
	}

	private void requestCaps(Set<String> offered, boolean allowSasl)
	{
		List<String> want = new ArrayList<>();
		for (String cap : offered)
		{
			if (enabledCaps.contains(cap))
			{
				continue;
			}
			if (WANTED_CAPS.contains(cap))
			{
				want.add(cap);
			}
			else if (cap.equals("sasl") && allowSasl && saslCredentialsSet() && saslSupportsPlain())
			{
				want.add(cap);
			}
		}
		// Keep each REQ line comfortably under 512 bytes.
		StringBuilder line = new StringBuilder();
		for (String cap : want)
		{
			if (line.length() + cap.length() + 1 > 400)
			{
				sendRaw("CAP REQ :" + line);
				pendingReqs++;
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(cap);
		}
		if (line.length() > 0)
		{
			sendRaw("CAP REQ :" + line);
			pendingReqs++;
		}
	}

	private boolean saslCredentialsSet()
	{
		return !isBlank(settings.saslAccount) && settings.saslPassword != null && !settings.saslPassword.isEmpty();
	}

	private boolean saslSupportsPlain()
	{
		String mechs = availableCaps.get("sasl");
		if (mechs == null || mechs.isEmpty())
		{
			return true; // pre-302 servers don't list mechanisms
		}
		return Arrays.asList(mechs.toUpperCase().split(",")).contains("PLAIN");
	}

	private void startSasl()
	{
		saslInProgress = true;
		sendRaw("AUTHENTICATE PLAIN");
	}

	private void handleAuthenticate(String arg)
	{
		if (!saslInProgress || !"+".equals(arg))
		{
			return;
		}
		// PLAIN: authzid \0 authcid \0 password (authzid left empty)
		String payload = "\0" + settings.saslAccount + "\0" + settings.saslPassword;
		String b64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		for (int i = 0; i < b64.length(); i += SASL_CHUNK)
		{
			sendRawPrivate("AUTHENTICATE " + b64.substring(i, Math.min(b64.length(), i + SASL_CHUNK)));
		}
		if (b64.length() % SASL_CHUNK == 0)
		{
			sendRawPrivate("AUTHENTICATE +");
		}
	}

	private void maybeEndCap()
	{
		if (capNegotiating && !lsInProgress && pendingReqs == 0 && !saslInProgress)
		{
			endCapNegotiation();
		}
	}

	private void endCapNegotiation()
	{
		if (capNegotiating)
		{
			capNegotiating = false;
			lsInProgress = false;
			sendRaw("CAP END");
		}
	}

	/**
	 * sts: on a plaintext connection, a policy tells us to reconnect with TLS on the given port.
	 * Returns true if we are abandoning this connection.
	 */
	private boolean checkSts() throws IOException
	{
		String sts = availableCaps.get("sts");
		if (sts == null || settings.tls)
		{
			return false;
		}
		Map<String, String> kv = parseKeyValues(sts);
		int port;
		try
		{
			port = Integer.parseInt(kv.getOrDefault("port", ""));
		}
		catch (NumberFormatException e)
		{
			return false; // no usable port: ignore the policy, as the spec requires
		}
		listener.onStsUpgrade(port);
		requestedClose = false;
		throw new IOException("Upgrading to TLS (STS policy)");
	}

	// ------------------------------------------------------------------
	// ISUPPORT, batches, modes, users
	// ------------------------------------------------------------------

	private void handleIsupport(Message m)
	{
		// <me> TOKEN[=value] ... :are supported by this server
		for (int i = 1; i < m.params.size() - 1; i++)
		{
			String tok = m.param(i);
			if (tok.startsWith("-"))
			{
				continue; // negation: we keep defaults
			}
			int eq = tok.indexOf('=');
			String key = (eq < 0 ? tok : tok.substring(0, eq)).toUpperCase();
			String value = eq < 0 ? "" : unescapeIsupport(tok.substring(eq + 1));
			switch (key)
			{
				case "PREFIX":
				{
					// (ov)@+
					int close = value.indexOf(')');
					if (value.startsWith("(") && close > 0 && value.length() - close - 1 == close - 1)
					{
						prefixModes = value.substring(1, close);
						prefixSymbols = value.substring(close + 1);
					}
					break;
				}
				case "CHANMODES":
				{
					String[] parts = value.split(",", -1);
					if (parts.length >= 4)
					{
						chanModes = Arrays.copyOf(parts, 4);
					}
					break;
				}
				case "CHANTYPES":
					chanTypes = value;
					break;
				case "CASEMAPPING":
					caseMapping = value.toLowerCase();
					break;
				case "NICKLEN":
					try
					{
						nickLength = Integer.parseInt(value);
					}
					catch (NumberFormatException ignored)
					{
					}
					break;
				case "NETWORK":
					network = value;
					break;
				case "MONITOR":
					try
					{
						monitorLimit = value.isEmpty() ? -1 : Math.max(1, Integer.parseInt(value));
					}
					catch (NumberFormatException e)
					{
						monitorLimit = -1;
					}
					break;
				default:
			}
		}
	}

	private void handleBatch(Message m)
	{
		String ref = m.param(0);
		if (ref.startsWith("+"))
		{
			batches.put(ref.substring(1), new Batch(m.param(1), m.params.subList(Math.min(2, m.params.size()), m.params.size())));
		}
		else if (ref.startsWith("-"))
		{
			Batch b = batches.remove(ref.substring(1));
			if (b != null && isNetBatch(b) && !b.nicks.isEmpty())
			{
				listener.onNetBatch(b.type, String.join(" ", b.params), b.nicks);
			}
		}
	}

	private static boolean isNetBatch(Batch b)
	{
		return "netsplit".equals(b.type) || "netjoin".equals(b.type);
	}

	private void handleJoin(Message m, String nick, Batch batch)
	{
		String channel = m.param(0);
		if (isSelf(nick))
		{
			synchronized (channels)
			{
				channels.put(casefold(channel), new Chan(channel));
			}
			listener.onUsersChanged(channel);
			listener.onSelfJoin(channel);
			return;
		}
		User u = new User(nick);
		u.user = m.prefixUser();
		u.host = m.prefixHost();
		if (hasCap("extended-join") && m.params.size() >= 3)
		{
			// JOIN <channel> <account|*> :<realname>
			u.account = "*".equals(m.param(1)) ? null : m.param(1);
			u.realName = m.param(2);
		}
		else if (m.tags.containsKey("account"))
		{
			u.account = m.tags.get("account");
		}
		synchronized (channels)
		{
			Chan c = channels.get(casefold(channel));
			if (c != null)
			{
				c.users.put(casefold(nick), u);
			}
		}
		listener.onUsersChanged(channel);
		if (batch != null && isNetBatch(batch))
		{
			batch.nicks.add(nick);
		}
		else
		{
			listener.onJoin(channel, u.copy());
		}
	}

	private void handleMode(Message m)
	{
		// MODE <channel> <modestring> [args...]; we only care about prefix modes on our channel.
		String target = m.param(0);
		if (!isChannel(target) || m.params.size() < 2)
		{
			return;
		}
		String modes = m.param(1);
		int arg = 2;
		boolean adding = true;
		String[] cm = chanModes;
		for (char c : modes.toCharArray())
		{
			if (c == '+' || c == '-')
			{
				adding = c == '+';
				continue;
			}
			int pi = prefixModes.indexOf(c);
			boolean takesArg = pi >= 0
				|| cm[0].indexOf(c) >= 0
				|| cm[1].indexOf(c) >= 0
				|| (adding && cm[2].indexOf(c) >= 0);
			String param = takesArg ? m.param(arg++) : null;
			if (pi >= 0 && param != null && !param.isEmpty())
			{
				char symbol = prefixSymbols.charAt(pi);
				final boolean add = adding;
				updateUserIn(target, param, u -> u.prefixes = add ? addPrefix(u.prefixes, symbol) : u.prefixes.replace(String.valueOf(symbol), ""));
			}
		}
	}

	private String addPrefix(String current, char symbol)
	{
		if (current.indexOf(symbol) >= 0)
		{
			return current;
		}
		StringBuilder sb = new StringBuilder();
		for (char s : prefixSymbols.toCharArray())
		{
			if (s == symbol || current.indexOf(s) >= 0)
			{
				sb.append(s);
			}
		}
		return sb.toString();
	}

	/** NAMES entry: [prefixes]nick[!user@host] (multi-prefix / userhost-in-names). */
	User parseNamesEntry(String entry)
	{
		int i = 0;
		while (i < entry.length() && prefixSymbols.indexOf(entry.charAt(i)) >= 0)
		{
			i++;
		}
		String prefixes = addAllPrefixes(entry.substring(0, i));
		String rest = entry.substring(i);
		int bang = rest.indexOf('!');
		int at = rest.indexOf('@');
		User u = new User(bang < 0 ? (at < 0 ? rest : rest.substring(0, at)) : rest.substring(0, bang));
		u.prefixes = prefixes;
		if (bang >= 0 && at > bang)
		{
			u.user = rest.substring(bang + 1, at);
			u.host = rest.substring(at + 1);
		}
		return u;
	}

	private String addAllPrefixes(String symbols)
	{
		String p = "";
		for (char c : symbols.toCharArray())
		{
			p = addPrefix(p, c);
		}
		return p;
	}

	private interface UserUpdate
	{
		void apply(User u);
	}

	/** Applies an update to this nick in every channel we share with them. */
	private void updateUser(String nick, UserUpdate update)
	{
		String key = casefold(nick);
		synchronized (channels)
		{
			for (Chan c : channels.values())
			{
				User u = c.users.get(key);
				if (u != null)
				{
					update.apply(u);
				}
			}
		}
		listener.onUsersChanged(null);
	}

	/** Applies an update to this nick in one channel (e.g. a +o there). */
	private void updateUserIn(String channel, String nick, UserUpdate update)
	{
		synchronized (channels)
		{
			Chan c = channels.get(casefold(channel));
			User u = c == null ? null : c.users.get(casefold(nick));
			if (u != null)
			{
				update.apply(u);
			}
		}
		listener.onUsersChanged(channel);
	}

	private void renameUser(String oldNick, String newNick)
	{
		String oldKey = casefold(oldNick);
		synchronized (channels)
		{
			for (Chan c : channels.values())
			{
				User u = c.users.remove(oldKey);
				if (u != null)
				{
					u.nick = newNick;
					c.users.put(casefold(newNick), u);
				}
			}
		}
		listener.onUsersChanged(null);
	}

	private void removeUser(String channel, String nick)
	{
		synchronized (channels)
		{
			Chan c = channels.get(casefold(channel));
			if (c != null)
			{
				c.users.remove(casefold(nick));
			}
		}
		listener.onUsersChanged(channel);
	}

	private void removeUserEverywhere(String nick)
	{
		String key = casefold(nick);
		synchronized (channels)
		{
			for (Chan c : channels.values())
			{
				c.users.remove(key);
			}
		}
		listener.onUsersChanged(null);
	}

	/** We left (or were kicked from) a channel. */
	private void removeChannel(String channel)
	{
		synchronized (channels)
		{
			channels.remove(casefold(channel));
		}
		listener.onUsersChanged(channel);
	}

	// ------------------------------------------------------------------
	// PRIVMSG
	// ------------------------------------------------------------------

	private void handlePrivmsg(Message m, String sender)
	{
		String target = m.param(0);
		String text = m.param(1);
		boolean self = isSelf(sender);
		boolean chan = isChannel(target);

		if (self && "NickServ".equalsIgnoreCase(target) && text.regionMatches(true, 0, "IDENTIFY", 0, 8))
		{
			return; // never surface our own password via echo-message
		}

		if (text.length() > 1 && text.charAt(0) == '\u0001')
		{
			String ctcp = text.substring(1, text.endsWith("\u0001") ? text.length() - 1 : text.length());
			int sp = ctcp.indexOf(' ');
			String cmd = (sp < 0 ? ctcp : ctcp.substring(0, sp)).toUpperCase();
			String arg = sp < 0 ? "" : ctcp.substring(sp + 1);
			switch (cmd)
			{
				case "ACTION":
					listener.onMessage(new ChatEvent(target, sender, stripFormatting(arg), true, self, chan, m.tags));
					break;
				case "VERSION":
					if (!self)
					{
						sendRaw("NOTICE " + sender + " :\u0001VERSION " + settings.version + "\u0001");
					}
					break;
				case "PING":
					if (!self)
					{
						sendRaw("NOTICE " + sender + " :\u0001PING " + sanitize(arg) + "\u0001");
					}
					break;
				default:
					// Ignore other CTCP requests (TIME, FINGER, DCC, ...).
			}
			return;
		}
		listener.onMessage(new ChatEvent(target, sender, stripFormatting(text), false, self, chan, m.tags));
	}

	private boolean isSelf(String nick)
	{
		return nick != null && casefold(nick).equals(casefold(currentNick));
	}

	public boolean isChannel(String target)
	{
		return target != null && !target.isEmpty() && chanTypes.indexOf(target.charAt(0)) >= 0;
	}

	/** Casefolds a nick/channel according to the server's CASEMAPPING. */
	public String casefold(String s)
	{
		return casefold(s, caseMapping);
	}

	static String casefold(String s, String mapping)
	{
		if (s == null)
		{
			return "";
		}
		boolean rfc = "rfc1459".equals(mapping);
		boolean strict = "strict-rfc1459".equals(mapping);
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toCharArray())
		{
			if (c >= 'A' && c <= 'Z')
			{
				c = (char) (c + 32);
			}
			else if (rfc || strict)
			{
				if (c == '[')
				{
					c = '{';
				}
				else if (c == ']')
				{
					c = '}';
				}
				else if (c == '\\')
				{
					c = '|';
				}
				else if (c == '~' && rfc)
				{
					c = '^';
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// Sending
	// ------------------------------------------------------------------

	/** Sends a message to a channel or nick, splitting long text. Returns the chunks actually sent. */
	public List<String> privmsg(String target, String text)
	{
		return privmsg(target, text, Collections.emptyMap());
	}

	/** As {@link #privmsg(String, String)}, attaching client tags (e.g. "+draft/reply") when message-tags is enabled. */
	public List<String> privmsg(String target, String text, Map<String, String> clientTags)
	{
		List<String> chunks = splitUtf8(sanitize(text), MAX_TEXT_BYTES);
		String tagPrefix = hasCap("message-tags") ? formatTags(clientTags) : "";
		for (String chunk : chunks)
		{
			sendRaw(tagPrefix + "PRIVMSG " + target + " :" + chunk);
		}
		return chunks;
	}

	public void action(String target, String text)
	{
		for (String chunk : splitUtf8(sanitize(text), MAX_TEXT_BYTES - 10))
		{
			sendRaw("PRIVMSG " + target + " :\u0001ACTION " + chunk + "\u0001");
		}
	}

	public void names(String channel)
	{
		sendRaw("NAMES " + channel);
	}

	/** WHOIS with the nick twice so the user's own server answers, which includes idle time. */
	public void whois(String nick)
	{
		sendRaw("WHOIS " + nick + " " + nick);
	}

	/** Ask the server for the channel's current topic. */
	public void topic(String channel)
	{
		sendRaw("TOPIC " + channel);
	}

	/** Set the channel topic (needs channel operator on most channels). */
	public void setTopic(String channel, String topic)
	{
		sendRaw("TOPIC " + channel + " :" + sanitize(topic));
	}

	public void join(String channel, String key)
	{
		sendRaw("JOIN " + channel + (isBlank(key) ? "" : " " + key));
	}

	public void part(String channel, String reason)
	{
		sendRaw("PART " + channel + (isBlank(reason) ? "" : " :" + sanitize(reason)));
	}

	/** Adds nicks to the server-side watch list; the server answers with online/offline status. */
	public void monitorAdd(List<String> nicks)
	{
		sendMonitor('+', nicks);
	}

	public void monitorRemove(List<String> nicks)
	{
		sendMonitor('-', nicks);
	}

	private void sendMonitor(char op, List<String> nicks)
	{
		// Keep each line well under the 512-byte limit.
		StringBuilder sb = new StringBuilder();
		for (String n : nicks)
		{
			if (sb.length() > 0 && sb.length() + n.length() > 400)
			{
				sendRaw("MONITOR " + op + " " + sb);
				sb.setLength(0);
			}
			sb.append(sb.length() == 0 ? "" : ",").append(n);
		}
		if (sb.length() > 0)
		{
			sendRaw("MONITOR " + op + " " + sb);
		}
	}

	public void kick(String channel, String nick, String reason)
	{
		sendRaw("KICK " + channel + " " + nick + (isBlank(reason) ? "" : " :" + sanitize(reason)));
	}

	/** MODE on a channel, e.g. {@code mode("#chan", "+b nick!*@*")}. */
	public void mode(String channel, String modeArgs)
	{
		sendRaw("MODE " + channel + " " + sanitize(modeArgs).trim());
	}

	/** Status prefix symbols from highest to lowest rank, e.g. "~&@%+". */
	public String getPrefixSymbols()
	{
		return prefixSymbols;
	}

	private Whois whoisFor(String nick)
	{
		return pendingWhois.computeIfAbsent(casefold(nick), k -> new Whois(nick));
	}

	/** Unix seconds to Instant, or null if not a number. */
	static Instant parseEpochSeconds(String s)
	{
		try
		{
			return Instant.ofEpochSecond(Long.parseLong(s));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	public void changeNick(String nick)
	{
		sendRaw("NICK " + nick);
	}

	/** Mark ourselves away (null/blank = back). */
	public void setAway(String message)
	{
		sendRaw(isBlank(message) ? "AWAY" : "AWAY :" + sanitize(message));
	}

	/** Queue a raw line for the throttled writer. */
	void sendRaw(String line)
	{
		outQueue.offer(sanitize(line));
	}

	/** Same as sendRaw; separate name so password-bearing lines are easy to spot. */
	private void sendRawPrivate(String line)
	{
		outQueue.offer(sanitize(line));
	}

	private void writeLoop()
	{
		// Flood protection after registration: each line costs 2s of "penalty"; we may run up
		// to 10s ahead (a burst of ~5 lines) before waiting. Registration traffic is not throttled.
		long penaltyUntil = System.currentTimeMillis();
		try
		{
			while (!Thread.currentThread().isInterrupted())
			{
				String line = outQueue.take();
				if (registered)
				{
					long now = System.currentTimeMillis();
					if (penaltyUntil < now)
					{
						penaltyUntil = now;
					}
					long wait = penaltyUntil - now - 10_000;
					if (wait > 0)
					{
						Thread.sleep(wait);
					}
					penaltyUntil += 2_000;
				}
				writeNow(line);
			}
		}
		catch (InterruptedException ignored)
		{
		}
		catch (IOException e)
		{
			// Reader thread will notice the dead socket and report the disconnect.
			closeQuietly();
		}
	}

	private void writeNow(String line) throws IOException
	{
		Socket s = socket;
		if (s == null || s.isClosed())
		{
			throw new IOException("Not connected");
		}
		byte[] bytes = (line + "\r\n").getBytes(StandardCharsets.UTF_8);
		synchronized (this)
		{
			OutputStream out = s.getOutputStream();
			out.write(bytes);
			out.flush();
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** Removes CR/LF/NUL so user text can never inject a second IRC command. */
	static String sanitize(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("\r", "").replace("\n", " ").replace("\0", "");
	}

	static Instant parseTime(String t)
	{
		if (t != null)
		{
			try
			{
				return Instant.parse(t);
			}
			catch (DateTimeParseException ignored)
			{
			}
		}
		return Instant.now();
	}

	/** "a b=1 c" -> {a:"", b:"1", c:""} */
	static Map<String, String> parseCapList(String list)
	{
		Map<String, String> caps = new LinkedHashMap<>();
		for (String tok : list.trim().split(" +"))
		{
			if (tok.isEmpty())
			{
				continue;
			}
			int eq = tok.indexOf('=');
			caps.put(eq < 0 ? tok : tok.substring(0, eq), eq < 0 ? "" : tok.substring(eq + 1));
		}
		return caps;
	}

	/** "port=6697,duration=300" -> map */
	static Map<String, String> parseKeyValues(String s)
	{
		Map<String, String> kv = new HashMap<>();
		for (String part : s.split(","))
		{
			int eq = part.indexOf('=');
			kv.put(eq < 0 ? part : part.substring(0, eq), eq < 0 ? "" : part.substring(eq + 1));
		}
		return kv;
	}

	/** ISUPPORT values escape bytes as \xHH. */
	static String unescapeIsupport(String v)
	{
		if (v.indexOf("\\x") < 0)
		{
			return v;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < v.length(); i++)
		{
			if (v.startsWith("\\x", i) && isHex(v, i + 2, 2))
			{
				sb.append((char) Integer.parseInt(v.substring(i + 2, i + 4), 16));
				i += 3;
			}
			else
			{
				sb.append(v.charAt(i));
			}
		}
		return sb.toString();
	}

	/** message-tags value escaping. */
	static String escapeTagValue(String v)
	{
		StringBuilder sb = new StringBuilder();
		for (char c : v.toCharArray())
		{
			switch (c)
			{
				case ';':
					sb.append("\\:");
					break;
				case ' ':
					sb.append("\\s");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\n':
					sb.append("\\n");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	static String unescapeTagValue(String v)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < v.length(); i++)
		{
			char c = v.charAt(i);
			if (c != '\\')
			{
				sb.append(c);
				continue;
			}
			if (i + 1 >= v.length())
			{
				break; // trailing lone backslash is dropped
			}
			char n = v.charAt(++i);
			switch (n)
			{
				case ':':
					sb.append(';');
					break;
				case 's':
					sb.append(' ');
					break;
				case 'r':
					sb.append('\r');
					break;
				case 'n':
					sb.append('\n');
					break;
				default:
					sb.append(n); // includes "\\" -> "\" and unknown escapes -> the char itself
			}
		}
		return sb.toString();
	}

	/** Formats tags as "@k=v;k2 " for an outgoing line, or "" when empty. */
	static String formatTags(Map<String, String> tags)
	{
		if (tags == null || tags.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder("@");
		for (Map.Entry<String, String> e : tags.entrySet())
		{
			if (sb.length() > 1)
			{
				sb.append(';');
			}
			sb.append(e.getKey());
			if (e.getValue() != null && !e.getValue().isEmpty())
			{
				sb.append('=').append(escapeTagValue(e.getValue()));
			}
		}
		return sb.append(' ').toString();
	}

	/** Strips mIRC-style bold/colour/italic/underline/reset codes. */
	static String stripFormatting(String s)
	{
		if (s == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			switch (c)
			{
				case '\u0003': // colour: \x03[fg[,bg]]
				{
					int j = skipDigits(s, i + 1, 2);
					if (j > i + 1 && j < s.length() - 1 && s.charAt(j) == ',' && Character.isDigit(s.charAt(j + 1)))
					{
						j = skipDigits(s, j + 1, 2);
					}
					i = j - 1;
					break;
				}
				case '\u0004': // hex colour: \x04[RRGGBB[,RRGGBB]]
				{
					int j = i + 1;
					if (isHex(s, j, 6))
					{
						j += 6;
						if (j < s.length() && s.charAt(j) == ',' && isHex(s, j + 1, 6))
						{
							j += 7;
						}
					}
					i = j - 1;
					break;
				}
				case '\u0002':
				case '\u000F':
				case '\u0011':
				case '\u0016':
				case '\u001D':
				case '\u001E':
				case '\u001F':
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	private static int skipDigits(String s, int start, int max)
	{
		int j = start;
		while (j < s.length() && j - start < max && Character.isDigit(s.charAt(j)))
		{
			j++;
		}
		return j;
	}

	private static boolean isHex(String s, int start, int len)
	{
		if (start + len > s.length())
		{
			return false;
		}
		for (int k = start; k < start + len; k++)
		{
			if (Character.digit(s.charAt(k), 16) < 0)
			{
				return false;
			}
		}
		return true;
	}

	/** Splits text into pieces of at most maxBytes UTF-8 bytes, preferring to break at spaces. */
	static List<String> splitUtf8(String text, int maxBytes)
	{
		if (text.isEmpty())
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		int start = 0;
		while (start < text.length())
		{
			int bytes = 0;
			int end = start;
			int lastSpace = -1;
			while (end < text.length())
			{
				int cp = text.codePointAt(end);
				int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
				if (bytes + len > maxBytes)
				{
					break;
				}
				if (cp == ' ')
				{
					lastSpace = end;
				}
				bytes += len;
				end += Character.charCount(cp);
			}
			if (end < text.length() && lastSpace > start)
			{
				end = lastSpace;
			}
			out.add(text.substring(start, end));
			start = end;
			while (start < text.length() && text.charAt(start) == ' ')
			{
				start++;
			}
		}
		return out;
	}

	/** Turns any string (e.g. an RS display name) into a valid IRC nick. */
	static String toValidNick(String name)
	{
		return toValidNick(name, DEFAULT_NICK_LENGTH);
	}

	static String toValidNick(String name, int maxLength)
	{
		if (name == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (char c : name.toCharArray())
		{
			if (c == ' ' || c == ' ')
			{
				sb.append('_');
			}
			else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| "_-[]\\`^{}|".indexOf(c) >= 0)
			{
				sb.append(c);
			}
		}
		String nick = sb.toString();
		if (!nick.isEmpty() && (Character.isDigit(nick.charAt(0)) || nick.charAt(0) == '-'))
		{
			nick = "rl" + nick;
		}
		if (nick.length() > maxLength)
		{
			nick = nick.substring(0, maxLength);
		}
		return nick;
	}

	static String randomNick()
	{
		return "rl" + (10000 + ThreadLocalRandom.current().nextInt(90000));
	}

	static String alternateNick(String base, int attempt, int maxLength)
	{
		if (isBlank(base))
		{
			return randomNick();
		}
		String suffix = attempt == 1 ? "_" : attempt == 2 ? "__"
			: String.valueOf(ThreadLocalRandom.current().nextInt(100, 1000));
		String b = base.length() + suffix.length() > maxLength ? base.substring(0, Math.max(1, maxLength - suffix.length())) : base;
		return b + suffix;
	}

	static boolean isBlank(String s)
	{
		return s == null || s.trim().isEmpty();
	}

	// ------------------------------------------------------------------
	// Parsed line
	// ------------------------------------------------------------------

	static final class Message
	{
		final Map<String, String> tags;
		final String prefix;
		final String command;
		final List<String> params;

		Message(Map<String, String> tags, String prefix, String command, List<String> params)
		{
			this.tags = tags;
			this.prefix = prefix;
			this.command = command;
			this.params = params;
		}

		/** Parses "[@tags] [:prefix] COMMAND params... [:trailing]". */
		static Message parse(String line)
		{
			String rest = line;
			Map<String, String> tags = Collections.emptyMap();
			if (rest.startsWith("@"))
			{
				int sp = rest.indexOf(' ');
				if (sp < 0)
				{
					return null;
				}
				tags = parseTags(rest.substring(1, sp));
				rest = rest.substring(sp + 1);
			}
			rest = trimLeadingSpaces(rest);
			String prefix = "";
			if (rest.startsWith(":"))
			{
				int sp = rest.indexOf(' ');
				if (sp < 0)
				{
					return null;
				}
				prefix = rest.substring(1, sp);
				rest = trimLeadingSpaces(rest.substring(sp + 1));
			}
			int sp = rest.indexOf(' ');
			String command = (sp < 0 ? rest : rest.substring(0, sp)).toUpperCase();
			if (command.isEmpty())
			{
				return null;
			}
			List<String> params = new ArrayList<>();
			rest = sp < 0 ? "" : rest.substring(sp + 1);
			while (!rest.isEmpty())
			{
				rest = trimLeadingSpaces(rest);
				if (rest.isEmpty())
				{
					break;
				}
				if (rest.startsWith(":"))
				{
					params.add(rest.substring(1));
					break;
				}
				int s2 = rest.indexOf(' ');
				if (s2 < 0)
				{
					params.add(rest);
					break;
				}
				params.add(rest.substring(0, s2));
				rest = rest.substring(s2 + 1);
			}
			return new Message(tags, prefix, command, params);
		}

		static Map<String, String> parseTags(String raw)
		{
			Map<String, String> tags = new LinkedHashMap<>();
			for (String tag : raw.split(";"))
			{
				if (tag.isEmpty())
				{
					continue;
				}
				int eq = tag.indexOf('=');
				String key = eq < 0 ? tag : tag.substring(0, eq);
				String value = eq < 0 ? "" : unescapeTagValue(tag.substring(eq + 1));
				tags.put(key, value); // later duplicates win, per spec
			}
			return tags;
		}

		private static String trimLeadingSpaces(String s)
		{
			int i = 0;
			while (i < s.length() && s.charAt(i) == ' ')
			{
				i++;
			}
			return s.substring(i);
		}

		String param(int i)
		{
			return i < params.size() ? params.get(i) : "";
		}

		String trailing()
		{
			return params.isEmpty() ? "" : params.get(params.size() - 1);
		}

		String nick()
		{
			int bang = prefix.indexOf('!');
			int at = prefix.indexOf('@');
			return bang >= 0 ? prefix.substring(0, bang) : at >= 0 ? prefix.substring(0, at) : prefix;
		}

		String prefixUser()
		{
			int bang = prefix.indexOf('!');
			int at = prefix.indexOf('@');
			return bang >= 0 && at > bang ? prefix.substring(bang + 1, at) : null;
		}

		String prefixHost()
		{
			int at = prefix.indexOf('@');
			return at >= 0 ? prefix.substring(at + 1) : null;
		}

		@Override
		public String toString()
		{
			return "Message{" + tags + " " + prefix + " " + command + " " + params + "}";
		}
	}
}
