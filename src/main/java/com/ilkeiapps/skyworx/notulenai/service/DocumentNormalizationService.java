package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.NormalizedElement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Normalization layer between Tika extraction and {@code DocumentElement} persistence. Tika's
 * {@code /rmeta/md} endpoint returns structure-preserving Markdown, which this service parses with a
 * focused, line-based reader (no third-party Markdown library). The parse yields an ordered list of
 * {@link NormalizedElement}s that preserve heading hierarchy (parent linkage), and paragraph / list /
 * table / code boundaries, plus extraction order.
 * <p>
 * This decouples chunking from the raw Tika format: chunking consumes {@code DocumentElement}s, never
 * the Tika response. If no structural Markdown is recognised, a best-effort single text element is
 * produced so no content is lost.
 * <p>
 * Note: Markdown carries no page/slide boundaries (those existed only in Tika's XHTML via
 * {@code div.page} / {@code div.slide-content}), so {@code pageNumber} / {@code slideNumber} are left
 * null here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentNormalizationService {

	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
	private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+]|\\d+[.)])\\s+(.*)$");
	private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)(.*)$");
	private static final Pattern BLOCKQUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
	private static final Pattern IMAGE_ONLY = Pattern.compile("^!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)$");
	/** CommonMark backslash-escapable punctuation; Tika escapes these when emitting Markdown. */
	private static final Pattern MD_ESCAPE = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>~])");

	/** Mutable traversal state threaded through the parse. */
	private static final class Context {
		final List<NormalizedElement> elements = new ArrayList<>();
		final Deque<HeadingRef> headingStack = new ArrayDeque<>();
		final int rootIndex;

		Context(int rootIndex) {
			this.rootIndex = rootIndex;
		}
	}

	private record HeadingRef(int index, int level, String text) {
	}

	/**
	 * @param documentTitle fallback title (from Tika metadata or the original filename)
	 */
	public List<NormalizedElement> normalize(String markdown, String documentTitle) {
		var ctx = new Context(0);

		var root = new NormalizedElement();
		root.setElementType("DOCUMENT");
		root.setSequenceNumber(0);
		root.setParentIndex(-1);
		root.setContent(documentTitle);
		root.setElementPath("/");
		ctx.elements.add(root);

		if (markdown == null || markdown.isBlank()) {
			log.warn("normalize: empty markdown, produced {} element(s)", ctx.elements.size());
			return ctx.elements;
		}

		var lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
		var i = 0;
		while (i < lines.length) {
			var line = lines[i];
			if (line.isBlank()) {
				i++;
				continue;
			}
			var fence = FENCE.matcher(line);
			if (fence.matches()) {
				i = consumeCode(lines, i, fence.group(1).trim().charAt(0), ctx);
				continue;
			}
			var heading = HEADING.matcher(line);
			if (heading.matches()) {
				emitHeading(heading.group(1).length(), heading.group(2), ctx);
				i++;
				continue;
			}
			if (isTableRow(line) && i + 1 < lines.length && isTableDelimiter(lines[i + 1])) {
				i = consumeTable(lines, i, ctx);
				continue;
			}
			if (LIST_ITEM.matcher(line).matches()) {
				i = consumeList(lines, i, ctx);
				continue;
			}
			if (BLOCKQUOTE.matcher(line).matches()) {
				i = consumeBlockquote(lines, i, ctx);
				continue;
			}
			var image = IMAGE_ONLY.matcher(line.strip());
			if (image.matches()) {
				emitImage(image.group(1), image.group(2), ctx);
				i++;
				continue;
			}
			i = consumeParagraph(lines, i, ctx);
		}

		if (ctx.elements.size() == 1) {
			fallback(markdown, ctx);
			log.warn("normalize: no structural markdown recognised, produced {} fallback element(s)",
					ctx.elements.size());
			return ctx.elements;
		}
		log.info("normalize: produced {} elements from markdown", ctx.elements.size());
		return ctx.elements;
	}

	// ---------------------------------------------------------------- block consumers

	/** Collects a run of plain lines (soft-wrapped) into one PARAGRAPH; stops at a blank or block start. */
	private int consumeParagraph(String[] lines, int start, Context ctx) {
		var parts = new ArrayList<String>();
		var i = start;
		while (i < lines.length && !lines[i].isBlank() && !isBlockStart(lines, i)) {
			parts.add(lines[i].strip());
			i++;
		}
		emitContent("PARAGRAPH", clean(String.join(" ", parts)), ctx);
		return i;
	}

	/** Collects consecutive list items (ordered/unordered) into one LIST element. */
	private int consumeList(String[] lines, int start, Context ctx) {
		var items = new ArrayList<String>();
		var i = start;
		while (i < lines.length && !lines[i].isBlank()) {
			var m = LIST_ITEM.matcher(lines[i]);
			if (m.matches()) {
				var marker = m.group(1);
				var text = clean(m.group(2));
				var ordered = Character.isDigit(marker.charAt(0));
				items.add((ordered ? marker + " " : "- ") + text);
			} else if (lines[i].startsWith(" ") || lines[i].startsWith("\t")) {
				// Continuation / nested line of the previous item.
				if (!items.isEmpty()) {
					items.set(items.size() - 1, items.getLast() + " " + clean(lines[i].strip()));
				}
			} else {
				break;
			}
			i++;
		}
		emitContent("LIST", String.join("\n", items), ctx);
		return i;
	}

	/** Collects a GFM pipe table (header row, delimiter, body rows) into a TABLE + TABLE_ROW elements. */
	private int consumeTable(String[] lines, int start, Context ctx) {
		var rows = new ArrayList<List<String>>();
		rows.add(parseRow(lines[start]));
		var i = start + 2; // skip the header row and the delimiter row
		while (i < lines.length && isTableRow(lines[i])) {
			rows.add(parseRow(lines[i]));
			i++;
		}
		if (rows.isEmpty()) {
			return i;
		}
		var parentIndex = currentParent(ctx);
		var tableEl = base("TABLE", parentIndex);
		tableEl.setContent(renderTable(rows));
		tableEl.setElementPath(currentPath(ctx));
		var structured = new LinkedHashMap<String, Object>();
		structured.put("rows", rows);
		tableEl.setStructuredContent(structured);
		var meta = new LinkedHashMap<String, Object>();
		meta.put("rowCount", rows.size());
		meta.put("columnCount", rows.getFirst().size());
		tableEl.setMetadata(meta);
		var tableIndex = add(tableEl, ctx);

		for (List<String> row : rows) {
			var rowEl = base("TABLE_ROW", tableIndex);
			rowEl.setContent(String.join(" | ", row));
			rowEl.setElementPath(currentPath(ctx));
			add(rowEl, ctx);
		}
		return i;
	}

	/** Collects a fenced code block verbatim into a CODE element. */
	private int consumeCode(String[] lines, int start, char fenceChar, Context ctx) {
		var body = new ArrayList<String>();
		var i = start + 1;
		while (i < lines.length) {
			var stripped = lines[i].strip();
			if (stripped.length() >= 3 && stripped.charAt(0) == fenceChar
					&& stripped.chars().allMatch(c -> c == fenceChar)) {
				i++; // consume the closing fence
				break;
			}
			body.add(lines[i]);
			i++;
		}
		var content = String.join("\n", body).stripTrailing();
		if (!content.isBlank()) {
			var ne = base("CODE", currentParent(ctx));
			ne.setContent(content);
			ne.setElementPath(currentPath(ctx));
			add(ne, ctx);
		}
		return i;
	}

	/** Collects consecutive blockquote lines into one PARAGRAPH (markers stripped). */
	private int consumeBlockquote(String[] lines, int start, Context ctx) {
		var parts = new ArrayList<String>();
		var i = start;
		while (i < lines.length && !lines[i].isBlank()) {
			var m = BLOCKQUOTE.matcher(lines[i]);
			if (!m.matches()) {
				break;
			}
			parts.add(m.group(1).strip());
			i++;
		}
		emitContent("PARAGRAPH", clean(String.join(" ", parts)), ctx);
		return i;
	}

	// ---------------------------------------------------------------- element emitters

	private void emitHeading(int level, String rawText, Context ctx) {
		// Drop any trailing closing hashes of an ATX heading, then clean.
		var text = clean(rawText.replaceAll("\\s*#+\\s*$", ""));
		if (text.isEmpty()) {
			return;
		}
		while (!ctx.headingStack.isEmpty() && ctx.headingStack.peek().level() >= level) {
			ctx.headingStack.pop();
		}
		var parentIndex = ctx.headingStack.isEmpty() ? ctx.rootIndex : ctx.headingStack.peek().index();

		var ne = base("HEADING", parentIndex);
		ne.setHeadingLevel(level);
		ne.setContent(text);
		ne.setElementPath(headingPath(ctx, text));
		var index = add(ne, ctx);
		ctx.headingStack.push(new HeadingRef(index, level, text));
	}

	private void emitContent(String type, String text, Context ctx) {
		if (text == null || text.isBlank()) {
			return;
		}
		var ne = base(type, currentParent(ctx));
		ne.setContent(text);
		ne.setElementPath(currentPath(ctx));
		add(ne, ctx);
	}

	private void emitImage(String alt, String src, Context ctx) {
		var ne = base("IMAGE", currentParent(ctx));
		ne.setContent(clean(alt));
		ne.setElementPath(currentPath(ctx));
		var meta = new LinkedHashMap<String, Object>();
		meta.put("src", src);
		ne.setMetadata(meta);
		add(ne, ctx);
	}

	/** Produces a single OTHER element from the raw content when no structure was recognised. */
	private void fallback(String markdown, Context ctx) {
		var text = clean(markdown);
		if (text.isEmpty()) {
			return;
		}
		var ne = base("OTHER", ctx.rootIndex);
		ne.setContent(text);
		ne.setElementPath("/");
		add(ne, ctx);
	}

	// ---------------------------------------------------------------- parsing helpers

	/** True if the line at {@code idx} starts a non-paragraph block (so a paragraph run must stop). */
	private boolean isBlockStart(String[] lines, int idx) {
		var line = lines[idx];
		if (HEADING.matcher(line).matches() || FENCE.matcher(line).matches()
				|| LIST_ITEM.matcher(line).matches() || BLOCKQUOTE.matcher(line).matches()) {
			return true;
		}
		return isTableRow(line) && idx + 1 < lines.length && isTableDelimiter(lines[idx + 1]);
	}

	private boolean isTableRow(String line) {
		var s = line.strip();
		return s.contains("|") && !isTableDelimiter(line);
	}

	/** A GFM delimiter row: only pipes, colons, dashes and spaces, with at least one dash. */
	private boolean isTableDelimiter(String line) {
		var s = line.strip();
		if (s.isEmpty() || s.indexOf('-') < 0) {
			return false;
		}
		for (var c : s.toCharArray()) {
			if (c != '|' && c != ':' && c != '-' && c != ' ') {
				return false;
			}
		}
		return s.contains("|");
	}

	/** Splits a pipe-table row into trimmed, unescaped cells (ignoring escaped pipes). */
	private List<String> parseRow(String line) {
		var s = line.strip();
		if (s.startsWith("|")) {
			s = s.substring(1);
		}
		if (s.endsWith("|") && !s.endsWith("\\|")) {
			s = s.substring(0, s.length() - 1);
		}
		var cells = new ArrayList<String>();
		for (var cell : s.split("(?<!\\\\)\\|", -1)) {
			cells.add(clean(cell));
		}
		return cells;
	}

	// ---------------------------------------------------------------- shared helpers

	private int currentParent(Context ctx) {
		return ctx.headingStack.isEmpty() ? ctx.rootIndex : ctx.headingStack.peek().index();
	}

	private NormalizedElement base(String type, int parentIndex) {
		var ne = new NormalizedElement();
		ne.setElementType(type);
		ne.setParentIndex(parentIndex);
		return ne;
	}

	private int add(NormalizedElement ne, Context ctx) {
		var index = ctx.elements.size();
		ne.setSequenceNumber(index);
		ctx.elements.add(ne);
		return index;
	}

	private String currentPath(Context ctx) {
		if (ctx.headingStack.isEmpty()) {
			return "/";
		}
		return ctx.headingStack.peek().text();
	}

	private String headingPath(Context ctx, String text) {
		var parts = new ArrayList<String>();
		var it = ctx.headingStack.descendingIterator();
		while (it.hasNext()) {
			parts.add(it.next().text());
		}
		parts.add(text);
		return "/" + String.join("/", parts);
	}

	private String renderTable(List<List<String>> rows) {
		var sb = new StringBuilder();
		for (List<String> row : rows) {
			sb.append(String.join(" | ", row)).append('\n');
		}
		return sb.toString().strip();
	}

	/** Unescapes Markdown backslash-escapes, collapses punctuation leaders and whitespace. */
	private String clean(String text) {
		if (text == null) {
			return "";
		}
		var unescaped = MD_ESCAPE.matcher(text).replaceAll("$1");
		// Collapse runs of 4+ identical punctuation chars (dot/dash leaders in tables of contents,
		// rule lines, etc.) to a single char — they carry no meaning and explode tokenizer counts.
		var collapsed = unescaped.replaceAll("([^\\w\\s])\\1{3,}", "$1");
		return collapsed.replaceAll("\\s+", " ").strip();
	}
}
