package com.ircchat;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * Sidebar panel: scrollback with clickable links, a nick list, and an input box.
 * Public methods may be called from any thread; they hand their work to the Swing thread.
 */
@Slf4j
class IrcPanel extends PluginPanel
{
	private static final int MAX_LINES = 500;
	private static final int MAX_HISTORY = 50;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+");

	/** Light colours for the dark sidebar; same order as the chatbox palette so a nick keeps its hue. */
	private static final Color[] NICK_PALETTE = {
		new Color(0x64B5F6), // blue
		new Color(0xEF5350), // red
		new Color(0x81C784), // green
		new Color(0xFFB74D), // orange
		new Color(0xBA68C8), // purple
		new Color(0x4DB6AC), // teal
		new Color(0xF06292), // pink
		new Color(0xDCE775), // lime
	};
	private static final Color TEXT = new Color(0xD8D8D8);
	private static final Color DIM = new Color(0x9A9A9A);
	private static final Color TAG = new Color(0xB39DDB);
	private static final Color HIGHLIGHT_BG = new Color(0x4A3F1A);

	private final JLabel status = new JLabel("Not connected");
	private final JButton toggle = new JButton("Nicks");
	private final CardLayout cards = new CardLayout();
	private final JPanel center = new JPanel(cards);
	private final JEditorPane chatView = new JEditorPane();
	private final JScrollPane logScroll;
	private final DefaultListModel<IrcClient.User> nickModel = new DefaultListModel<>();
	private final JList<IrcClient.User> nickList = new JList<>(nickModel);
	private final JTextField input = new JTextField();
	private final List<String> history = new ArrayList<>();
	private int historyPos;
	private boolean showingNicks;
	private final AtomicBoolean usersPending = new AtomicBoolean();

	private final JComboBox<String> channelBox = new JComboBox<>();
	/** True while setChannels rebuilds the box, so its selection events aren't taken as the user switching. */
	private boolean updatingChannels;

	// Per-channel scrollback (Swing thread only). Keys are casefolded channel names; "" holds lines
	// from before we joined anything. Lines that aren't from a channel go to the one being viewed.
	private final java.util.Map<String, java.util.ArrayDeque<String>> buffers = new java.util.HashMap<>();
	private final java.util.Map<String, Integer> unread = new java.util.HashMap<>();
	private String viewKey = "";

	private final Consumer<String> onInput;
	private final Consumer<String> onSwitch;
	private final java.util.function.Function<String, String> casefold;

	/**
	 * @param onInput  called on the Swing thread with each line typed into the input box
	 * @param onSwitch called on the Swing thread when a channel is picked in the channel box
	 * @param casefold the server's nick casefolding, used for nick colours and sorting
	 */
	IrcPanel(Consumer<String> onInput, Consumer<String> onSwitch, java.util.function.Function<String, String> casefold)
	{
		super(false);
		this.onInput = onInput;
		this.onSwitch = onSwitch;
		this.casefold = casefold;

		setLayout(new BorderLayout(0, 4));
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header: connection status and a Chat/Nicks toggle.
		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		status.setForeground(DIM);
		status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
		toggle.setFocusable(false);
		toggle.setMargin(new java.awt.Insets(1, 6, 1, 6));
		toggle.addActionListener(e -> showNicks(!showingNicks));
		header.add(status, BorderLayout.CENTER);
		header.add(toggle, BorderLayout.EAST);
		// Channel picker: only shown when we're in more than one channel.
		channelBox.setVisible(false);
		channelBox.setFocusable(false);
		channelBox.setToolTipText("Channel you're viewing and talking in. (n) = unread messages");
		channelBox.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
				boolean focus)
			{
				String ch = (String) value;
				Integer n = ch == null ? null : unread.get(casefold.apply(ch));
				return super.getListCellRendererComponent(list, n == null ? ch : ch + " (" + n + ")", index, selected, focus);
			}
		});
		channelBox.addActionListener(e ->
		{
			String picked = (String) channelBox.getSelectedItem();
			if (!updatingChannels && picked != null)
			{
				onSwitch.accept(picked);
			}
		});
		header.add(channelBox, BorderLayout.NORTH);
		add(header, BorderLayout.NORTH);

		// Scrollback.
		chatView.setEditable(false);
		chatView.setContentType("text/html");
		chatView.setEditorKit(new HTMLEditorKit());
		chatView.setText("<html><body></body></html>");
		chatView.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		chatView.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		HTMLDocument doc = (HTMLDocument) chatView.getDocument();
		// Rules go on this document's own sheet; the kit's default sheet is shared by every HTML pane.
		doc.getStyleSheet().addRule("body { color: " + hex(TEXT) + "; font-family: sans-serif; font-size: 10pt; margin: 2px; }");
		doc.getStyleSheet().addRule("p { margin-top: 0; margin-bottom: 2px; }");
		doc.getStyleSheet().addRule("a { color: #82B1FF; }");
		chatView.addHyperlinkListener(e ->
		{
			if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED)
			{
				String url = e.getDescription();
				LinkBrowser.browse(url.regionMatches(true, 0, "www.", 0, 4) ? "https://" + url : url);
			}
		});
		logScroll = new JScrollPane(chatView, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		logScroll.setBorder(BorderFactory.createEmptyBorder());

		// Nick list: double-click starts a private message.
		nickList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nickList.setCellRenderer(new NickRenderer());
		nickList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				IrcClient.User u = nickList.getSelectedValue();
				if (e.getClickCount() == 2 && u != null)
				{
					showNicks(false);
					input.setText("/msg " + u.getNick() + " ");
					input.requestFocusInWindow();
				}
			}
		});
		JScrollPane nickScroll = new JScrollPane(nickList);
		nickScroll.setBorder(BorderFactory.createEmptyBorder());

		center.add(logScroll, "chat");
		center.add(nickScroll, "nicks");
		add(center, BorderLayout.CENTER);

		// Input with Up/Down history.
		input.setToolTipText("Type a message, or /help for commands");
		input.addActionListener(e -> submit());
		input.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_UP && historyPos > 0)
				{
					input.setText(history.get(--historyPos));
				}
				else if (e.getKeyCode() == KeyEvent.VK_DOWN && historyPos < history.size())
				{
					historyPos++;
					input.setText(historyPos == history.size() ? "" : history.get(historyPos));
				}
			}
		});
		add(input, BorderLayout.SOUTH);
	}

	// ------------------------------------------------------------------
	// Thread-safe API
	// ------------------------------------------------------------------

	void append(IrcPlugin.Line line)
	{
		String html = toHtml(line);
		// Channel lines are filed under their channel; everything else under whatever is being viewed.
		String channelKey = line.channelMessage ? casefold.apply(line.tag) : null;
		SwingUtilities.invokeLater(() -> file(channelKey == null ? viewKey : channelKey, html));
	}

	void setStatus(String text)
	{
		SwingUtilities.invokeLater(() -> status.setText(text));
	}

	/** Coalesces bursts of member changes (e.g. a NAMES reply) into one list rebuild. */
	void requestUsers(Supplier<List<IrcClient.User>> users, String prefixSymbols)
	{
		if (!usersPending.compareAndSet(false, true))
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			usersPending.set(false);
			setUsers(users.get(), prefixSymbols);
		});
	}

	/** Channels we're in and the one we're talking in (null when disconnected). */
	/**
	 * Channels we're in and the one we're talking in. The scrollback switches to that channel's
	 * history. With no active channel (disconnected) the current view is kept, so the
	 * "Disconnected" line stays visible.
	 */
	void setChannels(List<String> channels, String activeChannel)
	{
		List<String> copy = new ArrayList<>(channels);
		String activeKey = activeChannel == null ? null : casefold.apply(activeChannel);
		SwingUtilities.invokeLater(() ->
		{
			updatingChannels = true;
			try
			{
				channelBox.removeAllItems();
				for (String ch : copy)
				{
					channelBox.addItem(ch);
				}
				if (activeChannel != null)
				{
					channelBox.setSelectedItem(activeChannel);
				}
				channelBox.setVisible(copy.size() > 1);
				revalidate();
			}
			finally
			{
				updatingChannels = false;
			}
			if (activeKey != null && !activeKey.equals(viewKey))
			{
				showView(activeKey);
			}
		});
	}

	void clearUsers()
	{
		SwingUtilities.invokeLater(() -> setUsers(new ArrayList<>(), ""));
	}

	/** 16px "#" icon for the sidebar button, drawn so no image file is needed. */
	static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(0xDCDCDC));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
		g.drawString("#", 3, 14);
		g.dispose();
		return img;
	}

	// ------------------------------------------------------------------
	// Swing thread only
	// ------------------------------------------------------------------

	private void submit()
	{
		String text = input.getText();
		if (text.trim().isEmpty())
		{
			return;
		}
		input.setText("");
		history.add(text);
		if (history.size() > MAX_HISTORY)
		{
			history.remove(0);
		}
		historyPos = history.size();
		onInput.accept(text);
	}

	private void showNicks(boolean nicks)
	{
		showingNicks = nicks;
		cards.show(center, nicks ? "nicks" : "chat");
		updateToggle();
	}

	private void updateToggle()
	{
		toggle.setText(showingNicks ? "Chat" : "Nicks (" + nickModel.size() + ")");
	}

	private void setUsers(List<IrcClient.User> users, String prefixSymbols)
	{
		users.sort(Comparator
			.comparingInt((IrcClient.User u) -> rank(u.getPrefix(), prefixSymbols))
			.thenComparing(u -> casefold.apply(u.getNick())));
		IrcClient.User selected = nickList.getSelectedValue();
		nickModel.clear();
		for (IrcClient.User u : users)
		{
			nickModel.addElement(u);
		}
		if (selected != null)
		{
			for (int i = 0; i < nickModel.size(); i++)
			{
				if (nickModel.get(i).getNick().equals(selected.getNick()))
				{
					nickList.setSelectedIndex(i);
					break;
				}
			}
		}
		updateToggle();
	}

	/** Lower is higher rank; users with no prefix sort last. */
	private static int rank(String prefix, String symbols)
	{
		if (prefix.isEmpty())
		{
			return symbols.length() + 1;
		}
		int i = symbols.indexOf(prefix.charAt(0));
		return i < 0 ? symbols.length() : i;
	}

	/** Stores a line in a channel's scrollback, and shows it if that channel is on screen. */
	private void file(String key, String html)
	{
		java.util.ArrayDeque<String> buf = buffers.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
		buf.addLast(html);
		if (buf.size() > MAX_LINES)
		{
			buf.removeFirst();
		}
		if (key.equals(viewKey))
		{
			appendHtml(html);
		}
		else
		{
			unread.merge(key, 1, Integer::sum);
			channelBox.repaint();
		}
	}

	/** Replaces the scrollback with another channel's history and scrolls to the end. */
	private void showView(String key)
	{
		// Lines from before the first join (connecting, registered...) move into the first channel shown.
		java.util.ArrayDeque<String> early = viewKey.isEmpty() ? buffers.remove("") : null;
		if (early != null && !early.isEmpty())
		{
			java.util.ArrayDeque<String> buf = buffers.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
			java.util.Iterator<String> it = early.descendingIterator();
			while (it.hasNext())
			{
				buf.addFirst(it.next());
			}
			while (buf.size() > MAX_LINES)
			{
				buf.removeFirst();
			}
		}
		viewKey = key;
		unread.remove(key);
		channelBox.repaint();
		HTMLDocument doc = (HTMLDocument) chatView.getDocument();
		Element body = doc.getElement(doc.getDefaultRootElement(), javax.swing.text.StyleConstants.NameAttribute, HTML.Tag.BODY);
		try
		{
			doc.remove(0, doc.getLength());
			java.util.ArrayDeque<String> buf = buffers.get(key);
			if (buf != null && !buf.isEmpty())
			{
				doc.insertBeforeEnd(body, String.join("", buf));
			}
		}
		catch (BadLocationException | java.io.IOException e)
		{
			log.warn("Couldn't switch the IRC panel to {}", key, e);
		}
		JScrollBar bar = logScroll.getVerticalScrollBar();
		SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
	}

	private void appendHtml(String html)
	{
		JScrollBar bar = logScroll.getVerticalScrollBar();
		boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 16;
		HTMLDocument doc = (HTMLDocument) chatView.getDocument();
		Element body = doc.getElement(doc.getDefaultRootElement(), javax.swing.text.StyleConstants.NameAttribute, HTML.Tag.BODY);
		try
		{
			doc.insertBeforeEnd(body, html);
			while (body.getElementCount() > MAX_LINES)
			{
				Element first = body.getElement(0);
				doc.remove(first.getStartOffset(), first.getEndOffset() - first.getStartOffset());
			}
		}
		catch (BadLocationException | java.io.IOException e)
		{
			log.warn("Couldn't add a line to the IRC panel", e);
			return;
		}
		if (atBottom)
		{
			SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
		}
	}

	// ------------------------------------------------------------------
	// HTML
	// ------------------------------------------------------------------

	private String toHtml(IrcPlugin.Line line)
	{
		StringBuilder sb = new StringBuilder("<p");
		if (line.highlight)
		{
			sb.append(" style=\"background-color: ").append(hex(HIGHLIGHT_BG)).append('"');
		}
		sb.append('>');
		sb.append(span(DIM, TIME.format(line.time))).append(' ');
		if (!line.channelMessage)
		{
			// Channel lines are filed under their own channel, so they don't need a [#channel] tag.
			sb.append(span(TAG, "[" + line.tag + "]")).append(' ');
		}
		if (line.nick != null)
		{
			Color c = nickColor(line.nick);
			if (line.action)
			{
				sb.append(span(DIM, "* ")).append(span(c, line.nick)).append(' ');
			}
			else
			{
				sb.append(span(c, line.nick)).append(span(DIM, ": "));
			}
		}
		sb.append(linkify(line.text));
		return sb.append("</p>").toString();
	}

	private Color nickColor(String nick)
	{
		return NICK_PALETTE[IrcPlugin.nickPaletteIndex(casefold.apply(nick), NICK_PALETTE.length)];
	}

	private static String span(Color c, String text)
	{
		return "<span style=\"color: " + hex(c) + "\">" + escape(text) + "</span>";
	}

	/** Escapes text and turns http(s):// and www. URLs into links. */
	static String linkify(String text)
	{
		StringBuilder sb = new StringBuilder();
		Matcher m = URL.matcher(text);
		int last = 0;
		while (m.find())
		{
			String url = m.group();
			// Leave sentence punctuation after a link out of the link.
			int end = url.length();
			while (end > 0 && ".,;:!?)]}'".indexOf(url.charAt(end - 1)) >= 0)
			{
				end--;
			}
			url = url.substring(0, end);
			sb.append(escape(text.substring(last, m.start())));
			sb.append("<a href=\"").append(escape(url)).append("\">").append(escape(url)).append("</a>");
			last = m.start() + end;
		}
		sb.append(escape(text.substring(last)));
		return sb.toString();
	}

	static String escape(String s)
	{
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toCharArray())
		{
			switch (c)
			{
				case '&':
					sb.append("&amp;");
					break;
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				case '"':
					sb.append("&quot;");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String hex(Color c)
	{
		return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
	}

	private class NickRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
			boolean focus)
		{
			IrcClient.User u = (IrcClient.User) value;
			super.getListCellRendererComponent(list, u.getPrefix() + u.getNick() + (u.isAway() ? " (away)" : ""),
				index, selected, focus);
			if (!selected)
			{
				setForeground(u.isAway() ? DIM : nickColor(u.getNick()));
				setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
			return this;
		}
	}
}
