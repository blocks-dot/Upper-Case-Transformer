package com.mi465.plugins.uppercasetransformer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class UpperCaseTransformerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(UpperCaseTransformerPlugin.class);
		RuneLite.main(args);
	}
}
