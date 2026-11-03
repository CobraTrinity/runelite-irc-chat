package com.ircchat;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class IrcPluginHelpersTest
{
	@Test
	public void nickColourIsStable()
	{
		assertEquals(IrcPlugin.nickColorFor("cobratrinity"), IrcPlugin.nickColorFor("cobratrinity"));
	}

	@Test
	public void formatsDurations()
	{
		assertEquals("45s", IrcPlugin.duration(45));
		assertEquals("3m", IrcPlugin.duration(180));
		assertEquals("2h", IrcPlugin.duration(7200));
		assertEquals("2h 5m", IrcPlugin.duration(7500));
		assertEquals("1d 1h", IrcPlugin.duration(90000));
	}

	@Test
	public void makesBanMasks()
	{
		assertEquals("Bob!*@*", IrcPlugin.banMask("Bob"));
		assertEquals("*!*@evil.example", IrcPlugin.banMask("*!*@evil.example"));
		assertEquals("$a:bobacct", IrcPlugin.banMask("$a:bobacct"));
	}

	@Test
	public void linksUrlsAndEscapesHtml()
	{
		assertEquals("see <a href=\"https://example.com/a?b=1&amp;c=2\">https://example.com/a?b=1&amp;c=2</a>.",
			IrcPanel.linkify("see https://example.com/a?b=1&c=2."));
		assertEquals("(<a href=\"www.runelite.net\">www.runelite.net</a>)", IrcPanel.linkify("(www.runelite.net)"));
		assertEquals("&lt;b&gt;not a tag&lt;/b&gt; &amp; no link", IrcPanel.linkify("<b>not a tag</b> & no link"));
	}

	@Test
	public void splitsConfigLists()
	{
		assertEquals(java.util.Arrays.asList("Bob", "Alice"), IrcPlugin.splitList(" Bob, Alice ,"));
		assertEquals(java.util.Collections.emptyList(), IrcPlugin.splitList(null));
		assertEquals(java.util.Arrays.asList("#runelite", "#osrs"), IrcPlugin.configChannels("runelite, #osrs"));
	}
}
