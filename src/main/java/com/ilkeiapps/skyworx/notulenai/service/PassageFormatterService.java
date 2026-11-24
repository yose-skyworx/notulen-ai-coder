package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RerankedContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders the selected passage into the shape the deployed chat model can actually read — after
 * {@link PassageRerankerService} has chosen it and before the prompt is built.
 * <p>
 * Two things the reranker structurally cannot deliver. First, it returns a GFM table row on its own, which
 * is correct because the neighbouring rows are different records, but a bare {@code | 8 | MODEKECOCOK AN |
 * • SIMILARITY • EXACT | … |} then asks a 1.2B model to infer that the third cell is the answer to "apa
 * saja code dari MODEKECOCOKAN". Measured on {@code lfm-1.2b} with exactly that passage in the prompt, it
 * did not: it answered {@code "Code dari MODEKECOCOKAN adalah dalam Bahasa Indonesia."}, completing the
 * trailing instruction instead of the sentence. So the row is paired with the header row of its own table
 * and rewritten as one {@code Column: value} line per cell. Second, {@link ContextExpansionService}
 * prefixes a chunk with its ancestor heading path, but the reranker scores the <em>lines</em> of that chunk
 * and the matched row sits dozens of lines below the prefix, so the heading can influence which line wins
 * and still never reach the prompt. It is re-attached here, where the prompt is assembled from the passage
 * rather than from the chunk.
 * <p>
 * Nothing is invented: the labels come from the document's own header row and the path from the chunk's own
 * first line. A passage that cannot be labelled with certainty is returned as it arrived, because a wrong
 * label — the explanation presented as the code — is worse than the pipe row it replaced.
 * <p>
 * Deterministic in-memory work over already-detached DTOs: no database access, no transaction, no remote
 * call, no LLM. {@link RerankedContext} is internal to the agent and {@link com.ilkeiapps.skyworx.notulenai.bean.knowledge.Citation}
 * never copies chunk content, so rewording a passage cannot change an API response.
 */
@Slf4j
@Service
public class PassageFormatterService {

	/**
	 * Rewrites every passage into its prompt form, preserving source numbering, provenance and score.
	 * Returns a new context; the one passed in is left untouched.
	 *
	 * @param grounding the passages the reranker selected (may be empty; never null)
	 * @return the same passages in prompt form, never null
	 */
	public RerankedContext format(RerankedContext grounding) {
		var source = grounding.getPassages();
		var out = new ArrayList<RerankedContext.Passage>(source.size());
		var labelled = 0;
		var headed = 0;

		for (RerankedContext.Passage passage : source) {
			var body = labelTableRow(passage);
			if (!body.equals(passage.text())) {
				labelled++;
			}
			var rendered = attachSectionPath(body, passage.source());
			if (!rendered.equals(body)) {
				headed++;
			}
			out.add(new RerankedContext.Passage(passage.sourceNumber(), passage.source(), rendered,
					passage.score()));
		}

		log.info("format: {} passage(s) -> {} labelled from its table header, {} carrying its section path",
				out.size(), labelled, headed);
		return new RerankedContext(out);
	}

	/**
	 * Rewrites a single-line GFM table row as one {@code Column: value} line per cell, labelled from the
	 * header row of the table the row belongs to. Blank cells are dropped but never shift the labels, so a
	 * column the document left empty cannot re-align the ones after it.
	 * <p>
	 * Multi-line passages are left alone: {@code passage()} only ever returns a bare row on its own, so
	 * anything with a newline in it is prose or a wrapped fragment whose neighbours were deliberately kept,
	 * and relabelling it would discard them.
	 */
	private String labelTableRow(RerankedContext.Passage passage) {
		var text = passage.text();
		if (text.indexOf('\n') >= 0) {
			return text;
		}
		var cells = cells(text);
		if (cells.size() < 2) {
			return text;
		}
		var header = headerCells(passage.source().getContent(), text, cells.size());
		if (header == null) {
			return text;
		}

		var sb = new StringBuilder();
		for (var i = 0; i < cells.size(); i++) {
			var value = cells.get(i);
			if (value.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append('\n');
			}
			var label = header.get(i);
			sb.append(label.isEmpty() ? value : label + ": " + value);
		}
		return sb.length() == 0 ? text : sb.toString();
	}

	/**
	 * The cells of the header row of the table {@code row} belongs to, or null when the chunk holds none.
	 * <p>
	 * Walking up from the row, the first pipe line whose own next line is a GFM delimiter row is that table's
	 * header — nearest-above is the right table by construction, since any table in between would have been
	 * found first. Requiring the delimiter is what makes this safe: without it a chunk boundary that cut a
	 * header off from its body would leave another record's row at the top, and its cells would be used as
	 * labels. The cell count must agree as well, so a row from a differently shaped table is left alone.
	 * <p>
	 * The row is located by prefix rather than equality because {@code passage()} truncates a head longer
	 * than {@code knowledge.rag.passage-budget-chars}.
	 */
	private List<String> headerCells(String content, String row, int cellCount) {
		var lines = lines(content);
		var at = -1;
		for (var i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(row)) {
				at = i;
				break;
			}
		}
		for (var i = at - 1; i >= 0; i--) {
			if (!isDelimiter(lines.get(i + 1))) {
				continue;
			}
			var header = cells(lines.get(i));
			return header.size() == cellCount ? header : null;
		}
		return null;
	}

	/**
	 * Re-attaches the ancestor heading path {@link ContextExpansionService} left on the chunk's first line.
	 * Skipped when the passage already carries it — the reranker's line window pulls the prefix in when the
	 * match is near the top of the chunk — so a heading is never stated twice in one prompt.
	 */
	private String attachSectionPath(String body, KnowledgeSearchResult source) {
		var path = sectionPath(source.getContent());
		if (path == null || body.toLowerCase(Locale.ROOT).contains(path.toLowerCase(Locale.ROOT))) {
			return body;
		}
		return path + "\n" + body;
	}

	/**
	 * The chunk's first line when it is the bracketed path the expander prefixes, otherwise null. This is the
	 * only place the path survives to prompt time: it is not a field on {@link KnowledgeSearchResult}, and
	 * adding one would put it in the search endpoint's response.
	 */
	private String sectionPath(String content) {
		var lines = lines(content);
		if (lines.isEmpty()) {
			return null;
		}
		var first = lines.getFirst();
		return first.length() > 2 && first.startsWith("[") && first.endsWith("]") ? first : null;
	}

	/**
	 * The cells of a pipe-delimited row, trimmed. Empty outer cells produced by a leading and a trailing pipe
	 * are dropped, so {@code | a | b |} and {@code a | b} both yield two cells — this corpus holds both
	 * shapes — while an empty cell between two pipes is a real blank column and is kept. A line with no pipe
	 * at all yields nothing, which is how prose is told apart from a row.
	 */
	private List<String> cells(String row) {
		if (row.indexOf('|') < 0) {
			return List.of();
		}
		var out = new ArrayList<String>();
		for (var part : row.split("\\|", -1)) {
			out.add(part.strip());
		}
		if (out.getFirst().isEmpty()) {
			out.removeFirst();
		}
		if (!out.isEmpty() && out.getLast().isEmpty()) {
			out.removeLast();
		}
		return out;
	}

	/** True for a GFM delimiter row — {@code | --- | :--: |} — the line that marks the row above it as a header. */
	private boolean isDelimiter(String line) {
		if (line.indexOf('|') < 0 || line.indexOf('-') < 0) {
			return false;
		}
		for (var i = 0; i < line.length(); i++) {
			var c = line.charAt(i);
			if (c != '|' && c != '-' && c != ':' && c != ' ') {
				return false;
			}
		}
		return true;
	}

	/** Non-blank lines of a chunk, trimmed — the same view of the text {@link PassageRerankerService} scores. */
	private List<String> lines(String content) {
		var out = new ArrayList<String>();
		if (content == null) {
			return out;
		}
		for (var line : content.split("\\R")) {
			var trimmed = line.strip();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}
}
