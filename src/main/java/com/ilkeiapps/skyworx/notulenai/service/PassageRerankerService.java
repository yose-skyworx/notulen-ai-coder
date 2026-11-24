package com.ilkeiapps.skyworx.notulenai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RerankedContext;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;
import com.ilkeiapps.skyworx.notulenai.utility.QueryTerms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cuts the retrieved chunks down to the few lines that actually answer the question.
 * <p>
 * Vector search ranks whole chunks, and a chunk from a table-heavy document is a 200-line section dump:
 * the row the question is about is one line among a hundred. The deployed chat model cannot read that —
 * measured on {@code lfm-1.2b}, it answers correctly from a single short passage but answers from the
 * wrong passage once the prompt carries a few thousand tokens, and invents an answer when the passage
 * does not contain one. So this service narrows the context before the model ever sees it.
 * <p>
 * Ranking is lexical rather than another embedding call, because the signal that matters here is exact
 * terminology: a term is matched after normalization (lowercase, every non-alphanumeric run removed),
 * which is what lets a table cell the PDF wrapped mid-word — {@code MODEKECOCOK AN} — match the
 * {@code modekecocokan} a user typed. Question terms that also appear contiguously in a line score a
 * phrase bonus, because term counts alone tie the sentence that answers the question against a
 * meta-sentence about the document that happens to mention the same words. The chunk's cosine score is
 * added on top, so it breaks ties between equally strong lexical matches without ever outranking them.
 * <p>
 * Pure in-memory work over already-detached DTOs: no database access, no transaction, no remote call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassageRerankerService {

	/** Matches anything that is not an ASCII letter or digit; stripping these normalizes a term or a line. */
	private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

	/** Shortest shared prefix still treated as a match; below this, coincidental overlaps outnumber real ones. */
	private static final int MIN_PREFIX = 6;

	/**
	 * Added for each pair of question terms that appears contiguously in a line. Deliberately larger than a
	 * single term match (2.0): the phrase the user actually typed is stronger evidence than the same terms
	 * turning up separately. Without it, {@code "komponen utama cbas"} scores a line holding
	 * {@code komponen utama} exactly as high as boilerplate holding {@code fungsi utama aplikasi CBAS}, and
	 * the winner is then decided by a cosine difference in the third decimal place.
	 */
	private static final double PHRASE_BONUS = 3.0;

	/**
	 * Added when a query term is <em>defined</em> on the line — {@code X adalah …}, {@code X merupakan …},
	 * {@code X (expansion) merupakan …} — which is the shape of the answer to an "apa itu X" question. A
	 * single-term query like {@code apa itu cbas} leaves every line that merely mentions the term tied at
	 * the base score, so without this bonus the tie is broken by cosine alone, and a short keyword-dense
	 * navigation row ({@code Rekap CBAS}) outranks the prose sentence that actually defines the term. Sized
	 * at {@link #PHRASE_BONUS} and added on top of the base match, so it clears any realistic cosine gap
	 * (cosine lies in [0,1]) without ever promoting a line that does not contain the term.
	 */
	private static final double DEFINITION_BONUS = 3.0;

	/**
	 * How far after the matched term to look for a copula. Wide enough to clear a parenthetical expansion
	 * such as {@code CBAS (Credit Bureau Automation System) merupakan …} before the copula appears.
	 */
	private static final int DEFINITION_SCAN_CHARS = 64;

	/**
	 * Indonesian definitional copulas. A query term immediately followed by one of these is being defined,
	 * not merely mentioned. These are also stopwords (removed from the query), so they never appear as
	 * query terms themselves; here they are matched in the document line, not the question.
	 */
	private static final Set<String> COPULAS = Set.of("adalah", "merupakan", "ialah", "yaitu", "yakni");

	/** Punctuation that closes a sentence, used to tell a whole sentence from a wrapped fragment. */
	private static final String SENTENCE_END = ".?!";

	private final KnowledgeProperties properties;

	/**
	 * Selects up to {@code knowledge.rag.max-passages} passages, one per distinct chunk, best match first.
	 * Returns an empty context when no line in any retrieved chunk reaches
	 * {@code knowledge.rag.min-passage-score}; the caller reports "not in the documents" rather than asking
	 * the model, which would fabricate an answer.
	 *
	 * @param question the user's question (required)
	 * @param results  the vector-search hits, in rank order (may be empty)
	 * @return the selected passages, never null
	 */
	public RerankedContext select(String question, List<KnowledgeSearchResult> results) {
		var terms = QueryTerms.significant(question);
		if (terms.isEmpty() || results == null || results.isEmpty()) {
			log.info("rerank: nothing to match (terms={}, chunks={})", terms, results == null ? 0 : results.size());
			return RerankedContext.empty();
		}

		var rag = properties.getRag();
		var budget = Math.max(80, rag.getPassageBudgetChars());
		var window = Math.max(0, rag.getPassageWindowLines());
		var minScore = rag.getMinPassageScore();
		var maxPassages = Math.max(1, rag.getMaxPassages());

		var candidates = new ArrayList<Candidate>();
		for (var i = 0; i < results.size(); i++) {
			var chunk = results.get(i);
			var lines = lines(chunk.getContent());
			for (var matched = 0; matched < lines.size(); matched++) {
				var raw = lines.get(matched);
				var normalized = normalize(raw);
				var lexical = scoreLine(normalized, terms) + definitionBonus(raw, terms);
				if (lexical < minScore) {
					continue;
				}
				var vector = chunk.getScore() != null ? chunk.getScore() : 0.0;
				candidates.add(new Candidate(i + 1, chunk, passage(lines, matched, window, budget),
						lexical + vector, longestMatchedTerm(normalized, terms)));
			}
		}

		if (candidates.isEmpty()) {
			log.info("rerank: no line in {} chunk(s) matched {} (minPassageScore={})", results.size(), terms, minScore);
			return RerankedContext.empty();
		}

		// Primary: combined score. Tiebreak: the line matching the more specific (longer) query term wins —
		// when "apa saja code dari MODEKECOCOKAN" ties a header holding the generic column word "code" against
		// the data row holding "modekecocokan", the subject term the question is really about decides it.
		candidates.sort((a, b) -> {
			var byScore = Double.compare(b.score(), a.score());
			return byScore != 0 ? byScore : Integer.compare(b.tieBreak(), a.tieBreak());
		});

		var passages = new ArrayList<RerankedContext.Passage>(maxPassages);
		var usedSources = new LinkedHashSet<Integer>();
		for (Candidate candidate : candidates) {
			if (!usedSources.add(candidate.sourceNumber())) {
				continue;
			}
			passages.add(new RerankedContext.Passage(candidate.sourceNumber(), candidate.chunk(),
					candidate.text(), candidate.score()));
			if (passages.size() == maxPassages) {
				break;
			}
		}

		log.info("rerank: {} matching line(s) -> {} passage(s); best is source [{}] score={} ({} chars)",
				candidates.size(), passages.size(), passages.getFirst().sourceNumber(), passages.getFirst().score(),
				passages.getFirst().text().length());
		return new RerankedContext(passages);
	}

	/** Non-blank lines of a chunk, trimmed. Blank lines are dropped so a window never wastes budget on them. */
	private List<String> lines(String content) {
		var out = new ArrayList<String>();
		if (content == null) {
			return out;
		}
		for (var line : content.split("\\R")) {
			var trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	/**
	 * Scores one normalized line against the query terms. A term appearing whole in the line is the
	 * strongest signal. A line that is itself a fragment of a term scores next — that is the case a wrapped
	 * table cell produces, where the document holds {@code MODEKECOCOK} and the question asks about
	 * {@code modekecocokan}. A long shared prefix is the weakest accepted signal. Terms that appear as a
	 * contiguous phrase score an extra bonus on top.
	 */
	private double scoreLine(String normalizedLine, List<String> terms) {
		if (normalizedLine.length() < 4) {
			return 0.0;
		}
		var score = 0.0;
		for (var term : terms) {
			if (normalizedLine.contains(term)) {
				score += 2.0;
			} else if (normalizedLine.length() >= MIN_PREFIX && term.contains(normalizedLine)) {
				score += 1.5;
			} else if (sharesPrefix(normalizedLine, term)) {
				score += 1.0;
			}
		}
		for (var i = 0; i + 1 < terms.size(); i++) {
			if (normalizedLine.contains(terms.get(i) + terms.get(i + 1))) {
				score += PHRASE_BONUS;
			}
		}
		return score;
	}

	/**
	 * Length of the longest query term the line matches, by the same rules {@link #scoreLine} scores on
	 * (whole term, wrapped fragment, or shared prefix). Used only as a tiebreaker, so a line matching a
	 * long, specific term ({@code modekecocokan}) outranks one matching only a short, generic term
	 * ({@code code}) when their scores are otherwise equal. Zero when nothing matches.
	 */
	private int longestMatchedTerm(String normalizedLine, List<String> terms) {
		var longest = 0;
		for (var term : terms) {
			var matches = normalizedLine.contains(term)
					|| (normalizedLine.length() >= MIN_PREFIX && term.contains(normalizedLine))
					|| sharesPrefix(normalizedLine, term);
			if (matches) {
				longest = Math.max(longest, term.length());
			}
		}
		return longest;
	}

	private boolean sharesPrefix(String normalizedLine, String term) {
		for (var length = term.length() - 1; length >= MIN_PREFIX; length--) {
			if (normalizedLine.contains(term.substring(0, length))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@link #DEFINITION_BONUS} when the line defines one of the query terms — the term followed, within
	 * {@link #DEFINITION_SCAN_CHARS}, by a {@linkplain #COPULAS copula}. Scored on the raw line (not the
	 * stripped form) so word boundaries survive: {@code cbas} in {@code CBAS (Credit Bureau Automation
	 * System) merupakan aplikasi …} reaches {@code merupakan} across the parenthetical, while {@code Rekap
	 * CBAS |} has no copula after the term and earns nothing. Awarded once per line — a line defines a thing
	 * once.
	 */
	private double definitionBonus(String line, List<String> terms) {
		var lower = line.toLowerCase(Locale.ROOT);
		for (var term : terms) {
			var at = lower.indexOf(term);
			if (at < 0) {
				continue;
			}
			var from = at + term.length();
			var window = lower.substring(from, Math.min(lower.length(), from + DEFINITION_SCAN_CHARS));
			for (var token : NON_ALPHANUMERIC.split(window)) {
				if (COPULAS.contains(token)) {
					return DEFINITION_BONUS;
				}
			}
		}
		return 0.0;
	}

	/**
	 * The matched line plus the neighbouring lines that belong to it, within the budget.
	 * <p>
	 * What "belongs" depends on what matched. A line that is not a whole sentence is a table cell or a
	 * fragment the PDF wrapped mid-sentence, and in this corpus a parameter's name, its allowed values and
	 * its explanation are consecutive rows — so the rows after it are taken whether or not they read as
	 * sentences. A whole sentence is either a section intro, whose answer is the numbered procedure listed
	 * under it, or the answer itself, whose neighbours are unrelated prose; appending unrelated prose hands
	 * the model a distractor, which it answers from in preference to the sentence that matched. So a
	 * sentence keeps only the fragments around it and stops at the next prose sentence.
	 * <p>
	 * A GFM table row is the exception: the OCR-based extractor emits one row per line with its cells
	 * joined by {@code " | "}, so a single line already carries the whole record — a parameter's name, its
	 * allowed values and its explanation. Its neighbouring rows are <em>different</em> records, and
	 * appending them hands the model a parameter it did not ask about (it then answers about the wrong one).
	 * So a complete pipe-delimited row is returned on its own, with no neighbours.
	 */
	private String passage(List<String> lines, int matched, int window, int budget) {
		var head = lines.get(matched);
		if (head.length() > budget) {
			return head.substring(0, budget);
		}
		var wholeSentence = isSentence(head);
		if (!wholeSentence && head.indexOf('|') >= 0) {
			return head;
		}
		var sb = new StringBuilder(head);
		var last = Math.min(lines.size() - 1, matched + window);
		for (var i = matched + 1; i <= last; i++) {
			var line = lines.get(i);
			if (wholeSentence && isSentence(line)) {
				break;
			}
			if (sb.length() + line.length() + 1 > budget) {
				break;
			}
			sb.append('\n').append(line);
		}
		if (matched > 0) {
			var previous = lines.get(matched - 1);
			var belongs = !wholeSentence || !isSentence(previous);
			if (belongs && sb.length() + previous.length() + 1 <= budget) {
				sb.insert(0, previous + "\n");
			}
		}
		return sb.toString();
	}

	/**
	 * True when a line reads as a whole sentence — it opens one and closes one. A line that ends with a full
	 * stop but opens lowercase, {@code "otomatis (Robot). Setiap komponen …"}, is the tail of a sentence the
	 * PDF wrapped across a chunk boundary, so it is not whole and still needs its neighbours.
	 */
	private boolean isSentence(String line) {
		return line.length() > 1
				&& Character.isUpperCase(line.charAt(0))
				&& SENTENCE_END.indexOf(line.charAt(line.length() - 1)) >= 0;
	}

	private String normalize(String text) {
		return NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
	}

	/**
	 * A passage candidate before ranking: which chunk it came from, its text, its combined score, and
	 * {@code tieBreak} — the length of the longest query term the line matched, used to settle an exact
	 * score tie in favour of the more specific term.
	 */
	private record Candidate(int sourceNumber, KnowledgeSearchResult chunk, String text, double score,
			int tieBreak) {
	}
}
