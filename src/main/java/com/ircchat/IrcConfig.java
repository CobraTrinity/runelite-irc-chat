package com.ircchat;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(IrcConfig.GROUP)
public interface IrcConfig extends Config
{
	String GROUP = "ircchat";
	String IGNORED_NICKS_KEY = "ignoredNicks";
	String SIDE_PANEL_KEY = "sidePanel";
	String EXTRA_CHANNELS_KEY = "extraChannels";
	String WATCH_LIST_KEY = "watchList";

	@ConfigSection(
		name = "Connection",
		description = "IRC server and channel",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Display",
		description = "How IRC messages appear in the chatbox",
		position = 1
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Side panel",
		description = "The IRC panel in the RuneLite sidebar",
		position = 2
	)
	String panelSection = "panel";

	// ---------------- Connection ----------------

	@ConfigItem(
		keyName = "server",
		name = "Server",
		description = "IRC server hostname",
		position = 0,
		section = connectionSection
	)
	default String server()
	{
		return "irc.libera.chat";
	}

	@Range(min = 1, max = 65535)
	@ConfigItem(
		keyName = "port",
		name = "Port",
		description = "IRC server port (usually 6697 for TLS, 6667 for plaintext)",
		position = 1,
		section = connectionSection
	)
	default int port()
	{
		return 6697;
	}

	@ConfigItem(
		keyName = "tls",
		name = "Use TLS",
		description = "Encrypt the connection. Strongly recommended.",
		position = 2,
		section = connectionSection
	)
	default boolean tls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "channel",
		name = "Channel",
		description = "Channel to join, e.g. #mychannel",
		position = 3,
		section = connectionSection
	)
	default String channel()
	{
		return "";
	}

	@ConfigItem(
		keyName = "channelKey",
		name = "Channel key",
		description = "Password for the channel, if it has one",
		position = 4,
		section = connectionSection,
		secret = true
	)
	default String channelKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = EXTRA_CHANNELS_KEY,
		name = "Other channels",
		description = "More channels to join, comma-separated (e.g. #runelite, #osrs). Channels with a key aren't "
			+ "supported here; use ::ijoin #chan key. Switch between them with ::iswitch or the side panel",
		position = 5,
		section = connectionSection
	)
	default String extraChannels()
	{
		return "";
	}

	@ConfigItem(
		keyName = "nick",
		name = "Nickname",
		description = "IRC nickname. Leave blank to use your RuneScape name (if logged in) or a random name.",
		position = 6,
		section = connectionSection
	)
	default String nick()
	{
		return "";
	}

	@ConfigItem(
		keyName = "saslAccount",
		name = "SASL account",
		description = "Services account name for SASL login (usually your registered nick). Leave blank to skip SASL.",
		position = 7,
		section = connectionSection
	)
	default String saslAccount()
	{
		return "";
	}

	@ConfigItem(
		keyName = "saslPassword",
		name = "SASL password",
		description = "Password for SASL login (PLAIN mechanism; use TLS)",
		position = 8,
		section = connectionSection,
		secret = true
	)
	default String saslPassword()
	{
		return "";
	}

	@ConfigItem(
		keyName = "nickservPassword",
		name = "NickServ password",
		description = "Fallback: identify to NickServ after connecting if SASL isn't set up or fails (leave blank to skip)",
		position = 9,
		section = connectionSection,
		secret = true
	)
	default String nickservPassword()
	{
		return "";
	}

	@ConfigItem(
		keyName = "serverPassword",
		name = "Server password",
		description = "Server PASS, only needed for private servers or bouncers",
		position = 10,
		section = connectionSection,
		secret = true
	)
	default String serverPassword()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoAway",
		name = "Away when logged out",
		description = "Mark yourself away on IRC while you're logged out of the game, and back when you log in. "
			+ "Doesn't override an away message you set yourself with ::iaway",
		position = 11,
		section = connectionSection
	)
	default boolean autoAway()
	{
		return false;
	}

	@ConfigItem(
		keyName = "autoAwayMessage",
		name = "Logged-out away message",
		description = "Away message used by 'Away when logged out'",
		position = 12,
		section = connectionSection
	)
	default String autoAwayMessage()
	{
		return "Logged out of the game";
	}

	// ---------------- Display ----------------

	@ConfigItem(
		keyName = "highlightWords",
		name = "Highlight words",
		description = "Comma-separated words that notify you like your nick does (needs 'Notify on mention'). "
			+ "Matched as whole words, ignoring case",
		position = 8,
		section = displaySection
	)
	default String highlightWords()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showTimestamps",
		name = "Show timestamps",
		description = "Put the time (HH:mm) before IRC messages in the chatbox. The side panel always shows it",
		position = 9,
		section = displaySection
	)
	default boolean showTimestamps()
	{
		return false;
	}

	@ConfigItem(
		keyName = WATCH_LIST_KEY,
		name = "Watch list",
		description = "Comma-separated nicks to watch: you're told when they come online or go offline on IRC, "
			+ "even if they're not in your channels. Also managed with ::iwatch and ::iunwatch. "
			+ "Needs a server with MONITOR (Libera has it)",
		position = 10,
		section = displaySection
	)
	default String watchList()
	{
		return "";
	}

	@ConfigItem(
		keyName = "notifyOnWatch",
		name = "Notify when watched nicks connect",
		description = "Send a RuneLite notification when someone on your watch list comes online",
		position = 11,
		section = displaySection
	)
	default boolean notifyOnWatch()
	{
		return true;
	}

	// ---------------- Side panel ----------------

	@ConfigItem(
		keyName = SIDE_PANEL_KEY,
		name = "Show side panel",
		description = "Add an IRC panel to the RuneLite sidebar with scrollback, a nick list, clickable links and "
			+ "an input box",
		position = 0,
		section = panelSection
	)
	default boolean sidePanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatboxMessages",
		name = "Show in chatbox",
		description = "Also show IRC messages in the game chatbox. Turn off to keep IRC in the side panel only",
		position = 1,
		section = panelSection
	)
	default boolean chatboxMessages()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showJoinPart",
		name = "Show joins/parts",
		description = "Show when people join, leave or quit the channel",
		position = 0,
		section = displaySection
	)
	default boolean showJoinPart()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showAway",
		name = "Show away changes",
		description = "Show when channel members go away or come back (needs a server with away-notify)",
		position = 1,
		section = displaySection
	)
	default boolean showAway()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyOnMention",
		name = "Notify on mention",
		description = "Send a RuneLite notification when someone says your nick or messages you privately",
		position = 2,
		section = displaySection
	)
	default boolean notifyOnMention()
	{
		return true;
	}

	@ConfigItem(
		keyName = "tagColor",
		name = "Tag colour",
		description = "Colour of the [#channel] tag",
		position = 3,
		section = displaySection
	)
	default Color tagColor()
	{
		return new Color(0x7F, 0x00, 0xFF);
	}

	@ConfigItem(
		keyName = "colorNicks",
		name = "Colour each nick",
		description = "Give every nick its own colour, picked from the name, so people are easy to tell apart",
		position = 4,
		section = displaySection
	)
	default boolean colorNicks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "nickColor",
		name = "Nick colour",
		description = "Colour of sender names when 'Colour each nick' is off",
		position = 5,
		section = displaySection
	)
	default Color nickColor()
	{
		return new Color(0x00, 0x60, 0xC0);
	}

	@ConfigItem(
		keyName = "messageColor",
		name = "Message colour",
		description = "Colour of message text",
		position = 6,
		section = displaySection
	)
	default Color messageColor()
	{
		return Color.BLACK;
	}

	@ConfigItem(
		keyName = IGNORED_NICKS_KEY,
		name = "Ignored nicks",
		description = "Comma-separated nicks whose messages, notices and invites are hidden. "
			+ "Also managed with ::iignore and ::iunignore",
		position = 7,
		section = displaySection
	)
	default String ignoredNicks()
	{
		return "";
	}
}
