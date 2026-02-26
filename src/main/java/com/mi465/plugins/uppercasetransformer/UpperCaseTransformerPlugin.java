package com.mi465.plugins.uppercasetransformer;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Upper Case Transformer",
	description = "Restores intended all-caps player messages in public, clan, and private chat",
	tags = {"chat", "uppercase", "caps", "public", "clan", "private"}
)
public class UpperCaseTransformerPlugin extends Plugin
{
	private static final long DEBUG_MESSAGE_INTERVAL_NANOS = 1_000_000_000L;

	@Inject
	private Client client;

	@Inject
	private UpperCaseTransformerConfig config;

	private boolean debugChatTypesEnabled;
	private long lastDebugMessageNanos;
	private String lastDebugType = "";

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Upper Case Transformer started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Upper Case Transformer stopped");
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		if (!shouldHandleType(chatMessage.getType()))
		{
			return;
		}
		if (!isPlayerMessage(chatMessage))
		{
			return;
		}
		if (debugChatTypesEnabled)
		{
			sendDebugTypeMessage(chatMessage);
		}

		String message = chatMessage.getMessage();
		if (!looksLikeUppercaseWasTitleCased(message))
		{
			return;
		}

		String transformed = upperCaseTitleCaseWordsOutsideTagsIfChanged(message);
		if (transformed == null)
		{
			return;
		}

		MessageNode messageNode = chatMessage.getMessageNode();
		if (messageNode != null)
		{
			messageNode.setValue(transformed);
		}
		chatMessage.setMessage(transformed);
		client.refreshChat();
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted)
	{
		String command = commandExecuted.getCommand();
		if (command == null)
		{
			return;
		}

		if (!command.equalsIgnoreCase("uctdebug") && !command.equalsIgnoreCase("uppercasedebug"))
		{
			return;
		}

		String[] args = commandExecuted.getArguments();
		if (args.length == 0)
		{
			debugChatTypesEnabled = !debugChatTypesEnabled;
		}
		else if (args[0].equalsIgnoreCase("on"))
		{
			debugChatTypesEnabled = true;
		}
		else if (args[0].equalsIgnoreCase("off"))
		{
			debugChatTypesEnabled = false;
		}
		else
		{
			client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"[UCT] Usage: ::uctdebug [on|off]",
				null
			);
			return;
		}

		client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"",
			"[UCT] Debug chat types " + (debugChatTypesEnabled ? "enabled" : "disabled"),
			null
		);
	}

	@Provides
	UpperCaseTransformerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(UpperCaseTransformerConfig.class);
	}

	private boolean shouldHandleType(ChatMessageType type)
	{
		if (type == null)
		{
			return false;
		}

		String typeName = type.name();
		if (config.transformPublic()
			&& (typeName.equals("PUBLICCHAT")
			|| typeName.equals("MODCHAT")
			|| typeName.equals("AUTOTYPER")
			|| typeName.equals("MODAUTOTYPER")))
		{
			return true;
		}

		if (config.transformClan()
			&& (typeName.equals("CLAN_CHAT")
			|| typeName.equals("CLAN_GUEST_CHAT")
			|| typeName.equals("FRIENDSCHAT")
			|| typeName.equals("CLAN_GIM_CHAT")))
		{
			return true;
		}

		return config.transformPrivate() && typeName.contains("PRIVATECHAT");
	}

	private boolean isPlayerMessage(ChatMessage chatMessage)
	{
		String name = chatMessage.getName();
		if (name == null)
		{
			return false;
		}

		for (int i = 0; i < name.length(); i++)
		{
			if (!Character.isWhitespace(name.charAt(i)))
			{
				return true;
			}
		}
		return false;
	}

	private void sendDebugTypeMessage(ChatMessage chatMessage)
	{
		String typeName = chatMessage.getType() == null ? "UNKNOWN" : chatMessage.getType().name();
		long now = System.nanoTime();
		if (typeName.equals(lastDebugType) && (now - lastDebugMessageNanos) < DEBUG_MESSAGE_INTERVAL_NANOS)
		{
			return;
		}

		lastDebugType = typeName;
		lastDebugMessageNanos = now;
		client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"",
			"[UCT Debug] type=" + typeName + ", name=" + chatMessage.getName(),
			null
		);
	}

	private boolean looksLikeUppercaseWasTitleCased(String message)
	{
		if (message == null || message.isEmpty())
		{
			return false;
		}

		int titleCaseWords = 0;
		int alphabeticWords = 0;

		boolean inTag = false;
		boolean tokenHasLetters = false;
		boolean tokenStartsUpper = false;
		boolean tokenRestLower = true;
		int tokenLetterCount = 0;

		for (int i = 0; i < message.length(); i++)
		{
			char ch = message.charAt(i);
			if (ch == '<')
			{
				inTag = true;
				continue;
			}
			if (ch == '>')
			{
				inTag = false;
				continue;
			}
			if (inTag)
			{
				continue;
			}

			if (Character.isWhitespace(ch))
			{
				if (tokenHasLetters)
				{
					alphabeticWords++;
					if (tokenLetterCount > 1 && tokenStartsUpper && tokenRestLower)
					{
						titleCaseWords++;
					}
				}

				tokenHasLetters = false;
				tokenStartsUpper = false;
				tokenRestLower = true;
				tokenLetterCount = 0;
				continue;
			}

			if (!Character.isLetter(ch))
			{
				continue;
			}

			if (!tokenHasLetters)
			{
				tokenHasLetters = true;
				tokenStartsUpper = Character.isUpperCase(ch);
				tokenRestLower = true;
				tokenLetterCount = 1;
				continue;
			}

			tokenLetterCount++;
			if (!Character.isLowerCase(ch))
			{
				tokenRestLower = false;
			}
		}

		if (tokenHasLetters)
		{
			alphabeticWords++;
			if (tokenLetterCount > 1 && tokenStartsUpper && tokenRestLower)
			{
				titleCaseWords++;
			}
		}

		// Support partial-uppercase intent: if at least half the words look title-cased,
		// treat the message as likely transformed by RuneScape casing rules.
		if (alphabeticWords < 2)
		{
			return false;
		}

		int requiredTitleCaseWords = Math.max(2, (alphabeticWords + 1) / 2);
		return titleCaseWords >= requiredTitleCaseWords;
	}

	private String upperCaseTitleCaseWordsOutsideTagsIfChanged(String input)
	{
		StringBuilder out = new StringBuilder(input);
		boolean inTag = false;
		boolean changed = false;
		int i = 0;
		while (i < out.length())
		{
			char ch = out.charAt(i);
			if (ch == '<')
			{
				inTag = true;
				i++;
				continue;
			}

			if (ch == '>')
			{
				inTag = false;
				i++;
				continue;
			}

			if (inTag)
			{
				i++;
				continue;
			}

			if (!Character.isLetter(ch))
			{
				i++;
				continue;
			}

			int start = i;
			int letters = 0;
			boolean startsUpper = false;
			boolean restLower = true;
			while (i < out.length() && Character.isLetter(out.charAt(i)))
			{
				char letter = out.charAt(i);
				if (letters == 0)
				{
					startsUpper = Character.isUpperCase(letter);
				}
				else if (!Character.isLowerCase(letter))
				{
					restLower = false;
				}

				letters++;
				i++;
			}

			if (letters > 1 && startsUpper && restLower)
			{
				for (int j = start; j < i; j++)
				{
					char current = out.charAt(j);
					char upper = Character.toUpperCase(current);
					if (upper != current)
					{
						out.setCharAt(j, upper);
						changed = true;
					}
				}
			}
		}

		return changed ? out.toString() : null;
	}
}
