package com.mi465.plugins.uppercasetransformer;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Restores intended uppercase in player chat messages that OSRS displays in title case.
 * Supports public, clan/friends, and private chat; optional overhead replacement for public say only.
 */
@Slf4j
@PluginDescriptor(
	name = "Upper Case Transformer",
	description = "Restores intended all-caps player messages in public, clan, and private chat",
	tags = {"chat", "uppercase", "caps", "public", "clan", "private"}
)
public class UpperCaseTransformerPlugin extends Plugin
{
	private static final long DEBUG_MESSAGE_INTERVAL_NANOS = 1_000_000_000L;
	/** Game client ticks for overhead text (150 = 3 seconds, matches normal player chat overhead). */
	private static final int OVERHEAD_CYCLE_TICKS = 150;
	private static final int MIN_WORDS_FOR_TRANSFORM = 2;
	private static final String CHAT_TAG_PATTERN = "<[^>]*>";
	private static final String CMD_DEBUG = "uctdebug";
	private static final String CMD_DEBUG_ALT = "uppercasedebug";

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

		// Only replace overhead for public chat (when the game already shows say overhead)
		if (config.transformOverhead() && isPublicChatType(chatMessage.getType()))
		{
			setOverheadTextForSender(chatMessage.getName(), transformed);
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted)
	{
		String command = commandExecuted.getCommand();
		if (command == null)
		{
			return;
		}

		if (!command.equalsIgnoreCase(CMD_DEBUG) && !command.equalsIgnoreCase(CMD_DEBUG_ALT))
		{
			return;
		}

		String[] args = commandExecuted.getArguments();
		if (args == null || args.length == 0)
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
			sendGameMessage("[UCT] Usage: ::" + CMD_DEBUG + " [on|off]");
			return;
		}

		sendGameMessage("[UCT] Debug chat types " + (debugChatTypesEnabled ? "enabled" : "disabled"));
	}

	private void sendGameMessage(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
	}

	@Provides
	UpperCaseTransformerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(UpperCaseTransformerConfig.class);
	}

	private void setOverheadTextForSender(String senderName, String transformedMessage)
	{
		if (senderName == null || transformedMessage == null)
		{
			return;
		}

		String plain = stripChatTags(transformedMessage);
		if (plain.isEmpty())
		{
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return;
		}
		for (Player player : worldView.players())
		{
			if (senderName.equals(player.getName()))
			{
				player.setOverheadText(plain);
				player.setOverheadCycle(OVERHEAD_CYCLE_TICKS);
				break;
			}
		}
	}

	private static String stripChatTags(String s)
	{
		return s == null ? "" : s.replaceAll(CHAT_TAG_PATTERN, "").trim();
	}

	private boolean shouldHandleType(ChatMessageType type)
	{
		if (type == null)
		{
			return false;
		}

		if (config.transformPublic() && isPublicChatTypeOrMod(type))
		{
			return true;
		}
		if (config.transformClan() && isClanChatType(type))
		{
			return true;
		}
		return config.transformPrivate() && type.name().contains("PRIVATECHAT");
	}

	private static boolean isPublicChatTypeOrMod(ChatMessageType type)
	{
		return type == ChatMessageType.PUBLICCHAT
			|| type == ChatMessageType.MODCHAT
			|| type == ChatMessageType.AUTOTYPER
			|| type == ChatMessageType.MODAUTOTYPER;
	}

	private static boolean isClanChatType(ChatMessageType type)
	{
		return type == ChatMessageType.CLAN_CHAT
			|| type == ChatMessageType.CLAN_GUEST_CHAT
			|| type == ChatMessageType.FRIENDSCHAT
			|| type == ChatMessageType.CLAN_GIM_CHAT;
	}

	private static boolean isPublicChatType(ChatMessageType type)
	{
		return type == ChatMessageType.PUBLICCHAT;
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
		sendGameMessage("[UCT Debug] type=" + typeName + ", name=" + chatMessage.getName());
	}

	private boolean looksLikeUppercaseWasTitleCased(String message)
	{
		if (message == null || message.isEmpty())
		{
			return false;
		}

		int[] titleCaseWords = {0};
		int[] allCapsWords = {0};
		int alphabeticWords = 0;

		boolean inTag = false;
		boolean tokenHasLetters = false;
		boolean tokenStartsUpper = false;
		boolean tokenRestLower = true;
		boolean tokenAllCaps = true;
		int tokenLetterCount = 0;

		boolean firstWordWasTitleCase = false;
		boolean firstWordWasAllCaps = false;
		boolean secondWordStartsLower = false;

		for (int idx = 0; idx < message.length(); idx++)
		{
			char ch = message.charAt(idx);
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
					boolean isTitleCase = tokenLetterCount > 1 && tokenStartsUpper && tokenRestLower;
					boolean isAllCaps = tokenLetterCount >= 1 && tokenAllCaps;
					applyWordCounts(isTitleCase, isAllCaps, alphabeticWords,
						firstWordWasTitleCase, firstWordWasAllCaps, secondWordStartsLower,
						titleCaseWords, allCapsWords);
					alphabeticWords++;
					if (alphabeticWords == 1)
					{
						firstWordWasTitleCase = isTitleCase;
						firstWordWasAllCaps = isAllCaps;
					}
				}

				tokenHasLetters = false;
				tokenStartsUpper = false;
				tokenRestLower = true;
				tokenAllCaps = true;
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
				tokenAllCaps = Character.isUpperCase(ch);
				tokenLetterCount = 1;
				if (alphabeticWords == 1)
				{
					secondWordStartsLower = Character.isLowerCase(ch);
				}
				continue;
			}

			tokenLetterCount++;
			if (!Character.isLowerCase(ch))
			{
				tokenRestLower = false;
			}
			if (!Character.isUpperCase(ch))
			{
				tokenAllCaps = false;
			}
		}

		if (tokenHasLetters)
		{
			boolean isTitleCase = tokenLetterCount > 1 && tokenStartsUpper && tokenRestLower;
			boolean isAllCaps = tokenLetterCount >= 1 && tokenAllCaps;
			applyWordCounts(isTitleCase, isAllCaps, alphabeticWords,
				firstWordWasTitleCase, firstWordWasAllCaps, secondWordStartsLower,
				titleCaseWords, allCapsWords);
			alphabeticWords++;
			if (alphabeticWords == 1)
			{
				firstWordWasTitleCase = isTitleCase;
				firstWordWasAllCaps = isAllCaps;
			}
		}

		if (alphabeticWords < MIN_WORDS_FOR_TRANSFORM)
		{
			return false;
		}

		// Trigger if there's at least one word we'll capitalize: any title-case (after first-word rule) or all-caps
		return (titleCaseWords[0] + allCapsWords[0]) >= 1;
	}

	private static void applyWordCounts(boolean isTitleCase, boolean isAllCaps, int alphabeticWordsSoFar,
		boolean firstWordWasTitleCase, boolean firstWordWasAllCaps, boolean secondWordStartsLower,
		int[] titleCaseWords, int[] allCapsWords)
	{
		if (alphabeticWordsSoFar == 0)
		{
			return;
		}
		if (alphabeticWordsSoFar == 1)
		{
			if (!(firstWordWasTitleCase && secondWordStartsLower))
			{
				if (firstWordWasTitleCase)
				{
					titleCaseWords[0]++;
				}
				if (firstWordWasAllCaps)
				{
					allCapsWords[0]++;
				}
			}
		}
		if (isTitleCase)
		{
			titleCaseWords[0]++;
		}
		if (isAllCaps)
		{
			allCapsWords[0]++;
		}
	}

	private String upperCaseTitleCaseWordsOutsideTagsIfChanged(String input)
	{
		StringBuilder out = new StringBuilder(input);
		boolean inTag = false;
		boolean changed = false;
		int wordIndex = 0;
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

			boolean isTitleCase = letters > 1 && startsUpper && restLower;
			boolean skipFirstWordRule = wordIndex == 0 && isTitleCase && nextWordStartsLower(out, i);

			if (isTitleCase && !skipFirstWordRule)
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

			wordIndex++;
		}

		return changed ? out.toString() : null;
	}

	/**
	 * Returns true if the next word (after position end) starts with a lowercase letter.
	 * Skips tags and whitespace.
	 */
	private static boolean nextWordStartsLower(StringBuilder s, int end)
	{
		int idx = end;
		while (idx < s.length())
		{
			char c = s.charAt(idx);
			if (c == '<')
			{
				idx++;
				while (idx < s.length() && s.charAt(idx) != '>')
				{
					idx++;
				}
				idx++;
				continue;
			}
			if (Character.isWhitespace(c) || !Character.isLetter(c))
			{
				idx++;
				continue;
			}
			return Character.isLowerCase(c);
		}
		return false;
	}
}
