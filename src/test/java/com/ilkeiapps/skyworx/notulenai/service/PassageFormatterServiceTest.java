package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.bean.knowledge.RerankedContext;

/**
 * Covers the rewrite that makes a table row readable by the deployed chat model, using the exact passage
 * the live run got wrong: {@code {"projectId":1,"question":"apa saja code dari MODEKECOCOKAN"}} retrieved
 * chunk 380, the reranker selected the correct row at score 2.4578963654606456, the answer was in the
 * 247-character prompt, and {@code lfm-1.2b} still returned {@code "Code dari MODEKECOCOKAN adalah dalam
 * Bahasa Indonesia."} — it completed the trailing instruction instead of reading the pipe-delimited row.
 */
class PassageFormatterServiceTest {

	private static final String SECTION_PATH = "[E. MASTER DATA > 10. Token Core]";

	/** The parameter table as {@code /v1/convert} emits it: a header, a GFM delimiter row, then the records. */
	private static final String TABLE_CHUNK = """
			%s
			| No | TX | Code | Penjelasan |
			| --- | --- | --- | --- |
			| 7 | PARAM_TARGE T_FASILITAS | • ALL • ACTIVE | Parameter ini digunakan untuk menentukan target fasilitas. |
			| 8 | MODEKECOCOK AN | • SIMILARITY • EXACT | Parameter ini digunakan untuk menentukan mode atau metode pencocokan data dalam proses IDEB. |
			""".formatted(SECTION_PATH);

	/** The winning row, exactly as {@code PassageRerankerService.passage()} returned it — alone, no neighbours. */
	private static final String MATCHED_ROW = "| 8 | MODEKECOCOK AN | • SIMILARITY • EXACT | "
			+ "Parameter ini digunakan untuk menentukan mode atau metode pencocokan data dalam proses IDEB. |";

	private static final double MATCHED_SCORE = 2.4578963654606456;

	private final PassageFormatterService formatter = new PassageFormatterService();

	@Test
	void labelsARowWithItsOwnTableHeaderAndCarriesItsSectionPathIntoThePrompt() {
		var formatted = formatter.format(context(TABLE_CHUNK, MATCHED_ROW)).getPassages().getFirst();

		assertEquals("""
				[E. MASTER DATA > 10. Token Core]
				No: 8
				TX: MODEKECOCOK AN
				Code: • SIMILARITY • EXACT
				Penjelasan: Parameter ini digunakan untuk menentukan mode atau metode pencocokan data dalam proses IDEB.""",
				formatted.text());
	}

	@Test
	void keepsTheValueTheQuestionAskedForUnderItsRealColumnLabel() {
		var text = formatter.format(context(TABLE_CHUNK, MATCHED_ROW)).getPassages().getFirst().text();

		// The whole point: "apa saja code dari MODEKECOCOKAN" must find its answer on a line labelled Code.
		assertTrue(text.contains("Code: • SIMILARITY • EXACT"), text);
		assertFalse(text.contains("|"), text);
	}

	@Test
	void labelsFromTheTableTheRowIsInNotAnEarlierTableInTheSameChunk() {
		var chunk = """
				%s
				| Menu | Fungsi |
				| --- | --- |
				| Token Core | Mengelola konfigurasi token |
				| No | TX | Code |
				| --- | --- | --- |
				| 8 | MODEKECOCOK AN | SIMILARITY |
				""".formatted(SECTION_PATH);

		var text = formatter.format(context(chunk, "| 8 | MODEKECOCOK AN | SIMILARITY |"))
				.getPassages().getFirst().text();

		assertEquals("""
				[E. MASTER DATA > 10. Token Core]
				No: 8
				TX: MODEKECOCOK AN
				Code: SIMILARITY""", text);
	}

	@Test
	void leavesARowAloneWhenItsHeaderDidNotSurviveTheChunkBoundary() {
		// No delimiter row anywhere in the chunk: the top row is another record, and using its cells as
		// labels would present the explanation as the code. Wrong labels are worse than the pipe row.
		var chunk = """
				%s
				| 7 | PARAM_TARGE T_FASILITAS | • ALL • ACTIVE | Parameter ini digunakan untuk menentukan target fasilitas. |
				%s
				""".formatted(SECTION_PATH, MATCHED_ROW);

		var formatted = formatter.format(context(chunk, MATCHED_ROW)).getPassages().getFirst();

		assertEquals(SECTION_PATH + "\n" + MATCHED_ROW, formatted.text());
	}

	@Test
	void leavesARowAloneWhenTheHeaderDisagreesOnTheCellCount() {
		var chunk = """
				| No | TX | Code |
				| --- | --- | --- |
				%s
				""".formatted(MATCHED_ROW);

		var formatted = formatter.format(context(chunk, MATCHED_ROW)).getPassages().getFirst();

		assertEquals(MATCHED_ROW, formatted.text());
	}

	@Test
	void readsARowWrittenWithoutLeadingAndTrailingPipes() {
		// Both shapes occur in this corpus, and they must produce the same cell count to line up.
		var chunk = """
				No | TX | Code
				--- | --- | ---
				8 | MODEKECOCOK AN | SIMILARITY
				""";

		var text = formatter.format(context(chunk, "8 | MODEKECOCOK AN | SIMILARITY"))
				.getPassages().getFirst().text();

		assertEquals("No: 8\nTX: MODEKECOCOK AN\nCode: SIMILARITY", text);
	}

	@Test
	void keepsABlankColumnFromShiftingTheLabelsAfterIt() {
		var chunk = """
				| No | TX | Code | Penjelasan |
				| --- | --- | --- | --- |
				| 8 |  | • SIMILARITY • EXACT | Parameter ini digunakan untuk menentukan mode pencocokan. |
				""";

		var text = formatter.format(context(chunk, "| 8 |  | • SIMILARITY • EXACT | "
				+ "Parameter ini digunakan untuk menentukan mode pencocokan. |")).getPassages().getFirst().text();

		assertEquals("""
				No: 8
				Code: • SIMILARITY • EXACT
				Penjelasan: Parameter ini digunakan untuk menentukan mode pencocokan.""", text);
	}

	@Test
	void attachesTheSectionPathToProsePassagesToo() {
		// The heading is what finding 4 was about, and it is missing for every passage shape, not only rows.
		var prose = "Token Core digunakan untuk mengelola konfigurasi token autentikasi.";
		var text = formatter.format(context(TABLE_CHUNK, prose)).getPassages().getFirst().text();

		assertEquals(SECTION_PATH + "\n" + prose, text);
	}

	@Test
	void doesNotRepeatASectionPathThePassageAlreadyCarries() {
		// The reranker's line window pulls the prefix in when the match is near the top of the chunk.
		var passage = SECTION_PATH + "\n| No | TX | Code | Penjelasan |";
		var text = formatter.format(context(TABLE_CHUNK, passage)).getPassages().getFirst().text();

		assertEquals(passage, text);
	}

	@Test
	void addsNothingToAChunkTheExpanderDidNotPrefix() {
		var chunk = """
				| No | Code |
				| --- | --- |
				| 8 | SIMILARITY |
				""";

		assertEquals("No: 8\nCode: SIMILARITY",
				formatter.format(context(chunk, "| 8 | SIMILARITY |")).getPassages().getFirst().text());
	}

	@Test
	void leavesAMultiLinePassageExactlyAsTheRerankerBuiltIt() {
		// A section intro with the numbered procedure kept under it: relabelling would discard the steps.
		var prose = "A. LOGIN\nHalaman login berfungsi sebagai pintu pertama user untuk masuk kedalam sistem.\n"
				+ "1. 1. Input Username dan Password";
		var chunk = SECTION_PATH + "\nBerfungsi untuk menolak task\n" + prose + "\n6\nB. DASHBOARD";

		assertEquals(SECTION_PATH + "\n" + prose,
				formatter.format(context(chunk, prose)).getPassages().getFirst().text());
	}

	@Test
	void keepsTheCitationMetadataAndFormatsEveryPassageWhenMoreThanOneIsSelected() {
		var first = chunk(380L, TABLE_CHUNK);
		var second = chunk(420L, TABLE_CHUNK);
		var grounding = new RerankedContext(List.of(
				new RerankedContext.Passage(6, first, MATCHED_ROW, MATCHED_SCORE),
				new RerankedContext.Passage(10, second, MATCHED_ROW, 0.43)));

		var formatted = formatter.format(grounding);

		assertEquals(2, formatted.getPassages().size());
		for (var i = 0; i < 2; i++) {
			var before = grounding.getPassages().get(i);
			var after = formatted.getPassages().get(i);
			// A citation is rebuilt from these, so none of them may move.
			assertSame(before.source(), after.source());
			assertEquals(before.sourceNumber(), after.sourceNumber());
			assertEquals(before.score(), after.score(), 1e-9);
			assertTrue(after.text().contains("Code: • SIMILARITY • EXACT"), after.text());
		}
		// The context passed in is not mutated.
		assertEquals(MATCHED_ROW, grounding.getPassages().getFirst().text());
	}

	private RerankedContext context(String content, String passage) {
		return new RerankedContext(List.of(new RerankedContext.Passage(6, chunk(380L, content), passage,
				MATCHED_SCORE)));
	}

	private KnowledgeSearchResult chunk(Long chunkId, String content) {
		var result = new KnowledgeSearchResult();
		result.setChunkId(chunkId);
		result.setChunkIndex(33);
		result.setContent(content);
		result.setScore(0.4578963654606454);
		result.setDocumentId(19L);
		result.setLogicalDocumentId(19L);
		result.setVersionNumber(1);
		result.setDocumentTitle("User Manual");
		return result;
	}
}
