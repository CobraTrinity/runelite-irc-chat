package com.ircchat;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Run this to launch RuneLite with the plugin loaded (development mode). */
public class IrcPluginTest
{
	// loadBuiltin's generic varargs parameter isn't @SafeVarargs; passing a single class literal is safe.
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(IrcPlugin.class);
		RuneLite.main(args);
	}
}
