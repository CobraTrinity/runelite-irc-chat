package com.ircchat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import java.util.List;
import org.junit.Test;

public class IrcClientTest
{
	@Test
	public void parsesTagsPrefixAndTrailing()
	{
		IrcClient.Message m = IrcClient.Message.parse(
			"@time=2026-11-03T10:00:00Z :Bob!bob@host PRIVMSG #runelite :hello there :)");
		assertEquals("Bob", m.nick());
		assertEquals("PRIVMSG", m.command);
		assertEquals("#runelite", m.param(0));
		assertEquals("hello there :)", m.param(1));
	}

	@Test
	public void parsesWithoutTrailing()
	{
		IrcClient.Message m = IrcClient.Message.parse(":Bob!b@h JOIN #runelite");
		assertEquals("JOIN", m.command);
		assertEquals("#runelite", m.param(0));
		assertNull(IrcClient.Message.parse(":onlyprefix"));
	}

	@Test
	public void stripsFormatting()
	{
		assertEquals("red bold plain", IrcClient.stripFormatting("\u000304,01red\u0003 \u0002bold\u0002 \u000Fplain"));
		assertEquals("12 apples", IrcClient.stripFormatting("\u00030312 apples"));
		assertEquals("hex", IrcClient.stripFormatting("\u0004FF0000hex"));
	}

	@Test
	public void sanitizesNewlines()
	{
		assertEquals("hi QUIT", IrcClient.sanitize("hi\r\nQUIT"));
	}

	@Test
	public void splitsLongTextOnUtf8Boundaries()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			sb.append("é ");
		}
		List<String> parts = IrcClient.splitUtf8(sb.toString().trim(), 100);
		for (String p : parts)
		{
			assert p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 100;
		}
		assertEquals(sb.toString().trim().replace(" ", ""), String.join("", parts).replace(" ", ""));
	}

	@Test
	public void parsesAndUnescapesTags()
	{
		IrcClient.Message m = IrcClient.Message.parse(
			"@time=2026-11-03T10:00:00.000Z;account=bob;+example=a\\sb\\:c;flag :Bob!b@h PRIVMSG #rl :hi");
		assertEquals("2026-11-03T10:00:00.000Z", m.tags.get("time"));
		assertEquals("bob", m.tags.get("account"));
		assertEquals("a b;c", m.tags.get("+example"));
		assertEquals("", m.tags.get("flag"));
		assertEquals("x", IrcClient.unescapeTagValue("x\\"));
		String raw = "semi;space back\\slash\r\nend";
		assertEquals(raw, IrcClient.unescapeTagValue(IrcClient.escapeTagValue(raw)));
	}

	@Test
	public void casefoldsPerCasemapping()
	{
		assertEquals("{nick}|^", IrcClient.casefold("[NICK]\\~", "rfc1459"));
		assertEquals("{nick}|~", IrcClient.casefold("[NICK]\\~", "strict-rfc1459"));
		assertEquals("[nick]\\~", IrcClient.casefold("[NICK]\\~", "ascii"));
	}

	@Test
	public void parsesCapLists()
	{
		java.util.Map<String, String> caps = IrcClient.parseCapList("sasl=PLAIN,EXTERNAL  server-time sts=port=6697,duration=300");
		assertEquals("PLAIN,EXTERNAL", caps.get("sasl"));
		assertEquals("", caps.get("server-time"));
		assertEquals("6697", IrcClient.parseKeyValues(caps.get("sts")).get("port"));
	}

	@Test
	public void makesValidNicks()
	{
		assertEquals("Cobra_Trinity", IrcClient.toValidNick("Cobra Trinity"));
		assertEquals("rl1_Def", IrcClient.toValidNick("1 Def"));
		assertEquals(IrcClient.DEFAULT_NICK_LENGTH, IrcClient.toValidNick("abcdefghijklmnopqrstuvwxyz").length());
	}

	/** Client fed server lines directly, with a listener that records what it was told. */
	private static final class Fed implements IrcClient.Listener
	{
		final List<IrcClient.Whois> whois = new java.util.ArrayList<>();
		final List<String> events = new java.util.ArrayList<>();
		final IrcClient client;

		Fed()
		{
			IrcClient.Settings s = new IrcClient.Settings();
			s.nick = "me";
			s.channel = "#chan";
			client = new IrcClient(s, this);
		}

		void feed(String line) throws Exception
		{
			client.handle(IrcClient.Message.parse(line));
		}

		@Override
		public void onWhois(IrcClient.Whois w)
		{
			whois.add(w);
		}

		@Override
		public void onTopicInfo(String channel, String setBy, java.time.Instant setAt)
		{
			events.add("info " + channel + " " + setBy + " " + setAt.getEpochSecond());
		}

		@Override
		public void onTopicChange(String channel, String nick, String topic)
		{
			events.add("change " + channel + " " + nick + " " + topic);
		}

		@Override
		public void onStatus(String text)
		{
			events.add("status " + text);
		}

		@Override
		public void onDisconnected(String reason, boolean requested)
		{
		}
	}

	@Test
	public void collectsWhoisReply() throws Exception
	{
		Fed f = new Fed();
		f.feed(":srv 311 me Bob ~bob user/bob * :Bob Smith");
		f.feed(":srv 319 me Bob :@#chan #other");
		f.feed(":srv 312 me Bob tungsten.libera.chat :Server info");
		f.feed(":srv 301 me Bob :at lunch");
		f.feed(":srv 671 me Bob :is using a secure connection");
		f.feed(":srv 317 me Bob 125 1700000000 :seconds idle, signon time");
		f.feed(":srv 330 me Bob bobacct :is logged in as");
		assertTrue("nothing reported before end of whois", f.whois.isEmpty());
		f.feed(":srv 318 me bob :End of /WHOIS list.");

		assertEquals(1, f.whois.size());
		IrcClient.Whois w = f.whois.get(0);
		assertEquals("Bob", w.nick);
		assertEquals("~bob", w.user);
		assertEquals("user/bob", w.host);
		assertEquals("Bob Smith", w.realName);
		assertEquals("@#chan #other", w.channels);
		assertEquals("tungsten.libera.chat", w.server);
		assertEquals("at lunch", w.awayMessage);
		assertEquals("bobacct", w.account);
		assertEquals(125, w.idleSeconds);
		assertEquals(1700000000L, w.signedOn.getEpochSecond());
		assertTrue(w.secure);
		assertFalse(w.operator);
	}

	@Test
	public void awayReplyOutsideWhoisIsShownAsStatus() throws Exception
	{
		Fed f = new Fed();
		f.feed(":srv 301 me Bob :at lunch");
		f.feed(":srv 318 me Bob :End of /WHOIS list.");
		assertTrue(f.whois.isEmpty());
		assertEquals(java.util.Collections.singletonList("status Bob is away: at lunch"), f.events);
	}

	@Test
	public void reportsTopicReplies() throws Exception
	{
		Fed f = new Fed();
		f.feed(":srv 333 me #chan Alice!a@host 1700000000");
		f.feed(":srv 331 me #chan :No topic is set.");
		f.feed(":Alice!a@host TOPIC #chan :new topic");
		assertEquals(java.util.Arrays.asList(
			"info #chan Alice 1700000000",
			"status No topic is set for #chan.",
			"change #chan Alice new topic"), f.events);
	}

	private static List<String> nicks(List<IrcClient.User> users)
	{
		List<String> out = new java.util.ArrayList<>();
		for (IrcClient.User u : users)
		{
			out.add(u.getPrefix() + u.getNick());
		}
		java.util.Collections.sort(out);
		return out;
	}

	@Test
	public void tracksMembersPerChannel() throws Exception
	{
		Fed f = new Fed();
		f.feed(":me!u@h JOIN #a");
		f.feed(":me!u@h JOIN #b");
		f.feed(":srv 353 me = #a :@me Bob Carol");
		f.feed(":srv 366 me #a :End of /NAMES list.");
		f.feed(":srv 353 me = #b :me Bob");
		f.feed(":srv 366 me #b :End of /NAMES list.");
		assertEquals(java.util.Arrays.asList("#a", "#b"), f.client.getChannels());
		assertEquals(java.util.Arrays.asList("@me", "Bob", "Carol"), nicks(f.client.getUsers("#a")));
		assertEquals(java.util.Arrays.asList("Bob", "me"), nicks(f.client.getUsers("#B")));

		// Modes and parts only touch their own channel.
		f.feed(":ChanServ!s@services MODE #b +o Bob");
		f.feed(":Carol!c@h PART #a :bye");
		assertEquals(java.util.Arrays.asList("@me", "Bob"), nicks(f.client.getUsers("#a")));
		assertEquals(java.util.Arrays.asList("@Bob", "me"), nicks(f.client.getUsers("#b")));

		// Nick changes and quits apply everywhere.
		f.feed(":Bob!b@h NICK Robert");
		assertEquals(java.util.Arrays.asList("@me", "Robert"), nicks(f.client.getUsers("#a")));
		assertEquals(java.util.Arrays.asList("@Robert", "me"), nicks(f.client.getUsers("#b")));
		f.feed(":Robert!b@h QUIT :gone");
		assertEquals(java.util.Collections.singletonList("@me"), nicks(f.client.getUsers("#a")));
		assertEquals(java.util.Collections.singletonList("me"), nicks(f.client.getUsers("#b")));

		// Leaving or being kicked drops just that channel.
		f.feed(":me!u@h PART #a");
		assertEquals(java.util.Collections.singletonList("#b"), f.client.getChannels());
		assertNull(f.client.findChannel("#a"));
		f.feed(":op!o@h KICK #b me :out");
		assertTrue(f.client.getChannels().isEmpty());
		assertTrue(f.client.getUsers("#b").isEmpty());
	}

	@Test
	public void namesForAChannelWeAreNotInStillReport() throws Exception
	{
		List<String> reported = new java.util.ArrayList<>();
		IrcClient.Settings s = new IrcClient.Settings();
		s.nick = "me";
		IrcClient c = new IrcClient(s, new IrcClient.Listener()
		{
			@Override
			public void onNames(String channel, List<IrcClient.User> users)
			{
				reported.add(channel + " " + users.size());
			}

			@Override
			public void onDisconnected(String reason, boolean requested)
			{
			}
		});
		c.handle(IrcClient.Message.parse(":srv 353 me = #other :x y z"));
		c.handle(IrcClient.Message.parse(":srv 366 me #other :End of /NAMES list."));
		assertEquals(java.util.Collections.singletonList("#other 3"), reported);
		assertTrue(c.getChannels().isEmpty());
	}

	@Test
	public void parsesMonitorRepliesAndLimit() throws Exception
	{
		List<String> seen = new java.util.ArrayList<>();
		IrcClient.Settings s = new IrcClient.Settings();
		s.nick = "me";
		IrcClient c = new IrcClient(s, new IrcClient.Listener()
		{
			@Override
			public void onMonitor(List<String> nicks, boolean online)
			{
				seen.add((online ? "on " : "off ") + String.join(",", nicks));
			}

			@Override
			public void onDisconnected(String reason, boolean requested)
			{
			}
		});
		assertFalse(c.supportsMonitor());
		c.handle(IrcClient.Message.parse(":srv 005 me MONITOR=100 NETWORK=Libera.Chat :are supported by this server"));
		assertTrue(c.supportsMonitor());
		assertEquals(100, c.getMonitorLimit());
		c.handle(IrcClient.Message.parse(":srv 730 me :Bob!b@host,Alice!a@host"));
		c.handle(IrcClient.Message.parse(":srv 731 me :Carol"));
		assertEquals(java.util.Arrays.asList("on Bob,Alice", "off Carol"), seen);
	}
}
