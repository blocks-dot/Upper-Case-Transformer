package com.mi465.plugins.uppercasetransformer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("uppercasetransformer")
public interface UpperCaseTransformerConfig extends Config
{
	@ConfigItem(
		keyName = "transformPublic",
		name = "Transform public chat",
		description = "Detect and convert likely-uppercase public messages"
	)
	default boolean transformPublic()
	{
		return true;
	}

	@ConfigItem(
		keyName = "transformClan",
		name = "Transform clan chat",
		description = "Detect and convert likely-uppercase clan/friends chat messages"
	)
	default boolean transformClan()
	{
		return true;
	}

	@ConfigItem(
		keyName = "transformPrivate",
		name = "Transform private chat",
		description = "Detect and convert likely-uppercase private messages"
	)
	default boolean transformPrivate()
	{
		return true;
	}

	@ConfigItem(
		keyName = "transformOverhead",
		name = "Transform overhead chat",
		description = "Show transformed capitalization above players' heads for public chat only (when overhead is already shown)"
	)
	default boolean transformOverhead()
	{
		return true;
	}
}
