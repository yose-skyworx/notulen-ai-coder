package com.ilkeiapps.skyworx.notulenai.utility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts the salient terms of a user's question — the words worth matching a document against,
 * with the Indonesian function words, question words and request verbs removed. Shared by the
 * retrieval layer (to find chunks that actually contain a term the embedding search missed) and the
 * passage reranker (to score lines), so both agree on what counts as a term.
 * <p>
 * A term is a run of 4+ ASCII alphanumerics that is not a stopword, lowercased and deduplicated in
 * order of first appearance. The stopword list deliberately excludes domain words ({@code data},
 * {@code proses}, {@code mode}, …), which are exactly the terms worth matching on.
 */
public final class QueryTerms {

	/** Matches anything that is not an ASCII letter or digit; used to split a question into candidate terms. */
	private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

	/** Shortest run of alphanumerics treated as a term; below this, coincidental overlaps outnumber real ones. */
	private static final int MIN_TERM_LENGTH = 4;

	/**
	 * Indonesian function words, question words, and request verbs. Filtered out so they cannot dominate
	 * matching — {@code yang} and {@code jelaskan} appear in most questions and in most documents.
	 */
	private static final Set<String> STOPWORDS = Set.of(
			"ada", "adalah", "agar", "apa", "apakah", "artinya", "atau", "bagaimana", "bahwa", "baik",
			"berikan", "berikut", "bisa", "coba", "dalam", "dan", "dari", "di", "dimana", "dimaksud",
			"dengan", "harus", "ini", "itu", "jadi", "jelaskan", "jelasin", "karena", "ke", "kemudian",
			"lalu", "mana", "mohon", "pada", "pula", "saja", "saya", "secara", "sehingga", "sebutkan",
			"supaya", "tersebut", "tidak", "tolong", "tunjukkan", "untuk", "yakni", "yaitu", "yang");

	private QueryTerms() {
	}

	/**
	 * The salient terms of {@code question}: lowercased alphanumeric runs of {@value #MIN_TERM_LENGTH}+
	 * characters that are not stopwords, deduplicated in order. Never null; empty when the question is
	 * null, blank, or only stopwords.
	 */
	public static List<String> significant(String question) {
		var terms = new ArrayList<String>();
		if (question == null) {
			return terms;
		}
		for (var raw : NON_ALPHANUMERIC.split(question.toLowerCase(Locale.ROOT))) {
			if (raw.length() < MIN_TERM_LENGTH || STOPWORDS.contains(raw) || terms.contains(raw)) {
				continue;
			}
			terms.add(raw);
		}
		return terms;
	}
}
