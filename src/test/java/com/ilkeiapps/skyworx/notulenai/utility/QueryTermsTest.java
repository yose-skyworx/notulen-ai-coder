package com.ilkeiapps.skyworx.notulenai.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class QueryTermsTest {

	@Test
	void keepsOnlyTheSalientTermFromADefinitionQuestion() {
		// The whole point of the lexical fallback: this reduces to the one term the embedding search missed.
		assertEquals(List.of("modekecocokan"),
				QueryTerms.significant("coba jelaskan apa yang dimaksud dengan MODEKECOCOKAN"));
	}

	@Test
	void lowercasesDeduplicatesAndKeepsOrder() {
		assertEquals(List.of("komponen", "utama", "cbas"),
				QueryTerms.significant("apa saja Komponen utama CBAS, komponen cbas"));
	}

	@Test
	void dropsStopwordsAndSubFourCharacterTokens() {
		// apa/itu are stopwords; a 3-char acronym falls under the length floor.
		assertTrue(QueryTerms.significant("apa itu abc").isEmpty());
	}

	@Test
	void returnsEmptyForNullOrBlank() {
		assertTrue(QueryTerms.significant(null).isEmpty());
		assertTrue(QueryTerms.significant("   ").isEmpty());
	}
}
