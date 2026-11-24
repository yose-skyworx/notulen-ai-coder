package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

/**
 * Covers the passage selection that makes RAG work on a 1.2B chat model, using the shapes of three real
 * production failures. First: the question's term appears in the document as {@code MODEKECOCOK AN}
 * because the PDF wrapped the table cell mid-word, and the chunk holding it ranks below a large distractor
 * chunk. Second: a chunk boundary cut the sentence that answers the question in half, so the chunk holding
 * only its tail ranked higher by cosine, and a boilerplate line mentioning two of the three question terms
 * beat the sentence holding all of them plus the user's own phrase. Third: the line that wins lexically is
 * a section intro, and the answer is the numbered procedure listed underneath it.
 */
class PassageRerankerServiceTest {

	private static final String QUESTION = "coba jelaskan apa yang dimaksud modekecocokan";

	/** Closest by cosine similarity, but about button functions — it produced the wrong answer in production. */
	private static final String BUTTON_CHUNK = """
			Fungsi Tombol
			Tombol
			Fungsi
			Berfungsi untuk melakukan autentikasi pengguna agar dapat masuk dan mengakses sistem.
			Berfungsi untuk mereset data list yang sedang ditampilkan
			Berfungsi untuk melakukan pencarian
			Befungsi untuk mengubah status data
			Berfungsi untuk mengekspor data
			""";

	/** Third by cosine similarity, but holds the row the question is about, mid-way through a long table. */
	private static final String PARAMETER_CHUNK = """
			No
			TX
			Code
			Penjelasan
			7
			PARAM_TARGE T_FASILITAS
			- ALL
			- ACTIVE
			Parameter ini digunakan untuk menentukan target fasilitas yang akan digunakan dalam IDEB.
			8
			MODEKECOCOK AN
			- SIMILARITY
			- EXACT
			Parameter ini digunakan untuk menentukan mode atau metode pencocokan data dalam proses IDEB.
			9
			CUSTOMERTYP ECOMPANY
			- PMG
			- PEM
			Parameter ini digunakan untuk mengelola data jenis nasabah berbentuk badan usaha.
			""";

	private static final String CBAS_QUESTION = "apa saja komponen utama cbas";

	/**
	 * Second by cosine similarity, and the chunk boundary cut the defining sentence in half: only its tail
	 * survived here. Its boilerplate line mentions {@code fungsi utama aplikasi CBAS} — two of the three
	 * question terms — which is enough to win on term count alone.
	 */
	private static final String TAIL_CHUNK = """
			otomatis (Robot). Setiap komponen saling terintegrasi untuk memastikan proses berjalan sesuai dengan kebutuhan organisasi.
			Dokumen ini disusun sebagai panduan bagi pengguna dalam memahami fungsi utama aplikasi CBAS serta cara penggunaannya secara efektif. Diharapkan dengan adanya panduan ini, pengguna dapat mengoperasikan aplikasi dengan baik dan memaksimalkan manfaat yang diberikan oleh sistem.
			Objectives
			Dengan adanya panduan ini, pengguna dapat menjalankan setiap fitur sesuai dengan
			prosedur yang benar sehingga meminimalisir kesalahan.
			Fungsi Tombol
			Berfungsi untuk mereset data list yang sedang ditampilkan
			""";

	/**
	 * Third by cosine similarity, 0.0017 behind {@code TAIL_CHUNK}, but it holds the whole sentence that
	 * answers the question — including the phrase {@code komponen utama} exactly as the user typed it.
	 */
	private static final String KNOW_YOUR_APP_CHUNK = """
			- 13.3 Rekap CBAS . 142
			3
			Know Your App
			CBAS (Credit Bureau Automation System) merupakan aplikasi yang dirancang untuk membantu proses integrasi dan otomatisasi pengambilan data dari sistem OJK (SLIK).
			Melalui CBAS, pengguna dapat mengelola akun akses ke SLIK, mengatur struktur organisasi kerja, serta menjalankan robot otomatis.
			Aplikasi ini terdiri dari beberapa komponen utama, yaitu pengelolaan akun SLIK (Users SLIK), pengaturan kelompok atau struktur kerja (Org SLIK), serta pembuatan dan pengelolaan robot otomatis (Robot). Setiap komponen saling terintegrasi untuk memastikan proses berjalan sesuai dengan kebutuhan organisasi.
			Dokumen ini disusun sebagai panduan bagi pengguna dalam memahami fungsi utama aplikasi CBAS serta cara penggunaannya secara efektif. Diharapkan dengan adanya panduan ini, pengguna dapat mengoperasikan aplikasi dengan baik dan memaksimalkan manfaat yang diberikan oleh sistem.
			""";

	/** Cosine scores exactly as production returned them: the two competing lines tie on term count. */
	private static final double TAIL_SCORE = 0.5354265730210177;
	private static final double KNOW_YOUR_APP_SCORE = 0.5337731201919174;

	private static final String WHAT_IS_CBAS_QUESTION = "apa itu CBAS";

	/**
	 * Closest by cosine similarity for {@link #WHAT_IS_CBAS_QUESTION}: a short, keyword-dense navigation
	 * table where {@code CBAS} appears in a menu label. After the move to OCR-based extraction these table
	 * rows out-cosine the prose definition, and because {@code apa itu CBAS} leaves the single term
	 * {@code cbas}, every "cbas" line ties on term count — so without the definition bonus this row's cosine
	 * wins and the model is handed {@code Rekap CBAS} instead of the sentence that defines CBAS. The cosine
	 * is the exact value production returned for this row.
	 */
	private static final String NAV_CHUNK = """
			Klik Report > Rekap CBAS |
			Klik Search untuk mencari data |
			Filter data 1. Klik button filter 2. Input data yang ingin dicari 3. Klik button search untuk mencari data, atau klik tombol reset untuk menghilangkan data di form advance search |
			""";
	private static final double NAV_SCORE = 0.7229047582802436;

	private static final String LOGIN_QUESTION = "apa yang diinput di halaman login";

	/**
	 * Closest by cosine similarity for {@link #LOGIN_QUESTION}. The line that wins lexically is the section
	 * intro, and the answer is the numbered procedure directly beneath it. The {@code diinput} the user typed
	 * matches nothing — the document says {@code Input}, and the Indonesian passive prefix puts it out of
	 * reach of a shared-prefix match — so the intro wins on the phrase {@code halaman login} alone.
	 */
	private static final String LOGIN_CHUNK = """
			Berfungsi untuk menolak task atau pengajuan yang tidak dapat disetujui
			Berfungsi untuk menyetujui task atau pengajuan sehingga dapat dilanjutkan ke proses berikutnya.
			A. LOGIN
			Halaman login berfungsi sebagai pintu pertama user untuk masuk kedalam sistem.
			1. 1. Input Username dan Password
			2. 2. Inputkan Captcha
			3. 3. Klik button Login
			6
			B. DASHBOARD
			Halaman Dashboard menyajikan ringkasan kinerja dan status sistem secara real-time.
			Klik rentang waktu untuk menampilkan data berdasarkan rentang waktu
			""";

	private static final double LOGIN_SCORE = 0.5671027551744536;

	private final KnowledgeProperties properties = new KnowledgeProperties();
	private final PassageRerankerService reranker = new PassageRerankerService(properties);

	@Test
	void selectsThePassageHoldingTheWrappedTermFromALowerRankedChunk() {
		var context = reranker.select(QUESTION, List.of(
				chunk(11L, BUTTON_CHUNK, 0.62),
				chunk(33L, PARAMETER_CHUNK, 0.48)));

		assertEquals(1, context.getPassages().size());
		var passage = context.getPassages().getFirst();
		// The passage came from the second chunk in the list, which the prompt numbers [2].
		assertEquals(33L, passage.source().getChunkId());
		assertEquals(2, passage.sourceNumber());
		// The matched row plus its preceding row number, its allowed values, and its explanation.
		assertTrue(passage.text().contains("MODEKECOCOK AN"), passage.text());
		assertTrue(passage.text().contains("SIMILARITY"), passage.text());
		assertTrue(passage.text().contains("Parameter ini digunakan untuk menentukan mode atau metode"),
				passage.text());
		assertTrue(passage.text().startsWith("8\n"), passage.text());
	}

	@Test
	void numbersPassagesWithTheirPositionInThePromptAndTheDocumentTitle() {
		var context = reranker.select(QUESTION, List.of(
				chunk(11L, BUTTON_CHUNK, 0.62),
				chunk(33L, PARAMETER_CHUNK, 0.48)));

		assertTrue(context.toPromptContext().startsWith("[2] FDS\n"), context.toPromptContext());
	}

	@Test
	void returnsEmptyWhenNoLineMatchesSoTheCallerSkipsTheLlm() {
		var context = reranker.select("apa itu whitelist", List.of(
				chunk(11L, BUTTON_CHUNK, 0.62),
				chunk(33L, PARAMETER_CHUNK, 0.48)));

		assertTrue(context.isEmpty());
	}

	@Test
	void returnsEmptyWhenTheQuestionIsOnlyStopwords() {
		assertTrue(reranker.select("coba jelaskan apa yang dimaksud", List.of(chunk(33L, PARAMETER_CHUNK, 0.48)))
				.isEmpty());
	}

	@Test
	void keepsThePassageInsideTheWindowAndBudget() {
		properties.getRag().setPassageWindowLines(1);
		properties.getRag().setPassageBudgetChars(80);

		var passage = reranker.select(QUESTION, List.of(chunk(33L, PARAMETER_CHUNK, 0.48)))
				.getPassages().getFirst();

		assertTrue(passage.text().length() <= 80, passage.text());
		assertFalse(passage.text().contains("Parameter ini digunakan"), passage.text());
	}

	@Test
	void returnsOnePassagePerDistinctChunkWhenMoreAreAllowed() {
		properties.getRag().setMaxPassages(2);

		var context = reranker.select(QUESTION, List.of(
				chunk(11L, PARAMETER_CHUNK, 0.48),
				chunk(22L, PARAMETER_CHUNK, 0.40)));

		assertEquals(2, context.getPassages().size());
		assertEquals(1, context.getPassages().get(0).sourceNumber());
		assertEquals(2, context.getPassages().get(1).sourceNumber());
	}

	@Test
	void prefersTheSentenceHoldingTheQuestionsOwnPhraseOverBoilerplateHoldingTheSameTerms() {
		var context = reranker.select(CBAS_QUESTION, List.of(
				chunk(99L, TAIL_CHUNK, TAIL_SCORE),
				chunk(98L, KNOW_YOUR_APP_CHUNK, KNOW_YOUR_APP_SCORE)));

		assertEquals(1, context.getPassages().size());
		var passage = context.getPassages().getFirst();
		// The higher-cosine chunk only holds the sentence's tail, so its boilerplate line must not win.
		assertEquals(98L, passage.source().getChunkId());
		assertEquals(2, passage.sourceNumber());
		assertTrue(passage.text().startsWith("Aplikasi ini terdiri dari beberapa komponen utama"), passage.text());
		assertTrue(passage.text().contains("Users SLIK"), passage.text());
		assertTrue(passage.text().contains("Org SLIK"), passage.text());
		assertTrue(passage.text().contains("robot otomatis (Robot)"), passage.text());
	}

	@Test
	void returnsACompleteSentenceWithoutTheSentencesAroundIt() {
		var passage = reranker.select(CBAS_QUESTION, List.of(chunk(98L, KNOW_YOUR_APP_CHUNK, KNOW_YOUR_APP_SCORE)))
				.getPassages().getFirst();

		// The matched line is a whole sentence, so neither neighbour is appended: both are about something
		// else, and the model answers from a distractor when one is present.
		assertFalse(passage.text().contains("\n"), passage.text());
		assertFalse(passage.text().contains("Dokumen ini disusun sebagai panduan"), passage.text());
		assertFalse(passage.text().contains("Melalui CBAS"), passage.text());
	}

	@Test
	void stillExtendsALineThatEndsWithAFullStopButOpensMidSentence() {
		var passage = reranker.select("apa saja komponen saling terintegrasi",
				List.of(chunk(99L, TAIL_CHUNK, TAIL_SCORE))).getPassages().getFirst();

		// Lowercase opening means the PDF wrapped this sentence across a chunk boundary, so it is a fragment
		// and the window still applies.
		assertTrue(passage.text().startsWith("otomatis (Robot)."), passage.text());
		assertTrue(passage.text().contains("Dokumen ini disusun sebagai panduan"), passage.text());
	}

	@Test
	void keepsTheNumberedProcedureListedUnderAMatchedSectionIntro() {
		var passage = reranker.select(LOGIN_QUESTION, List.of(chunk(100L, LOGIN_CHUNK, LOGIN_SCORE)))
				.getPassages().getFirst();

		// The intro scored halaman(2) + login(2) + the phrase "halamanlogin"(3); diinput scored nothing.
		assertEquals(7.0 + LOGIN_SCORE, passage.score(), 1e-9);
		assertTrue(passage.text().startsWith("A. LOGIN\nHalaman login berfungsi sebagai pintu pertama"),
				passage.text());
		assertTrue(passage.text().contains("Input Username dan Password"), passage.text());
		assertTrue(passage.text().contains("Inputkan Captcha"), passage.text());
		assertTrue(passage.text().contains("Klik button Login"), passage.text());
		// The steps are numbered fragments, not prose sentences, so they are kept and the next section is not.
		assertFalse(passage.text().contains("DASHBOARD"), passage.text());
		assertFalse(passage.text().contains("Berfungsi untuk menyetujui"), passage.text());
	}

	@Test
	void definesTheTermFromAProseSentenceOverAHigherCosineNavigationRow() {
		// The nav row is closest by cosine and mentions CBAS; the definition sentence sits in a
		// lower-cosine chunk. "apa itu CBAS" reduces to the single term "cbas", so both lines tie on term
		// count and only the definition bonus can lift the real definition above the nav row's cosine.
		var context = reranker.select(WHAT_IS_CBAS_QUESTION, List.of(
				chunk(1L, NAV_CHUNK, NAV_SCORE),
				chunk(98L, KNOW_YOUR_APP_CHUNK, KNOW_YOUR_APP_SCORE)));

		assertEquals(1, context.getPassages().size());
		var passage = context.getPassages().getFirst();
		assertEquals(98L, passage.source().getChunkId());
		assertEquals(2, passage.sourceNumber());
		// The defining sentence, carrying its "Know Your App" section heading as context.
		assertTrue(passage.text().contains("CBAS (Credit Bureau Automation System) merupakan"), passage.text());
		assertTrue(passage.text().startsWith("Know Your App"), passage.text());
		// The nav row must not be what the model is handed.
		assertFalse(passage.text().contains("Rekap CBAS"), passage.text());
		// Base term match (2.0) + definition bonus (3.0) + the chunk's cosine, clear of the nav row's score.
		assertEquals(5.0 + KNOW_YOUR_APP_SCORE, passage.score(), 1e-9);
	}

	@Test
	void answersApaItuForATermThePdfWrappedMidWord() {
		// "apa itu modekecocokan" -> single term "modekecocokan". The document holds it as the wrapped cell
		// "MODEKECOCOK AN", which normalizes back to the term; the row has no copula, so the definition bonus
		// stays out of it and the wrapped-term match alone selects the parameter's row and its explanation.
		var context = reranker.select("apa itu modekecocokan", List.of(chunk(33L, PARAMETER_CHUNK, 0.48)));

		assertEquals(1, context.getPassages().size());
		var passage = context.getPassages().getFirst();
		System.out.println("[apa itu modekecocokan] selected passage:\n" + passage.text());
		assertTrue(passage.text().contains("MODEKECOCOK AN"), passage.text());
		assertTrue(passage.text().contains("Parameter ini digunakan untuk menentukan mode atau metode"),
				passage.text());
		// Base wrapped-term match only; no copula follows the term, so no definition bonus is added.
		assertEquals(2.0 + 0.48, passage.score(), 1e-9);
	}

	/**
	 * The parameter table as the OCR-based extractor emits it: one GFM row per line, each carrying the whole
	 * record. The matched row must be returned on its own — the row above it is a different parameter
	 * (PARAM_TARGET_FASILITAS) that the model otherwise conflates into the answer.
	 */
	private static final String GFM_PARAMETER_CHUNK = """
			No | TX | Code | Penjelasan
			7 | PARAM_TARGE T_FASILITAS | • ALL • ACTIVE • CLOSED | Parameter ini digunakan untuk menentukan target fasilitas yang akan digunakan atau diproses dalam IDEB.
			8 | MODEKECOCOK AN | • SIMILARITY • EXACT | Parameter ini digunakan untuk menentukan mode atau metode pencocokan data dalam proses IDEB.
			9 | CUSTOMERTYP ECOMPANY | • PMG • PEM | Parameter ini digunakan untuk mengelola data jenis nasabah berbentuk badan usaha.
			""";

	@Test
	void prefersTheRowMatchingTheSpecificTermOverAHeaderMatchingAGenericColumnWord() {
		// "apa saja code dari MODEKECOCOKAN" -> terms [code, modekecocokan]. The header row matches the
		// generic "code" and the data row matches "modekecocokan"; both tie on score (same single-term match
		// + same chunk cosine). The specific subject term must win, or the model is handed only the header.
		var passage = reranker.select("apa saja code dari MODEKECOCOKAN",
				List.of(chunk(180L, GFM_PARAMETER_CHUNK, 0.47))).getPassages().getFirst();

		System.out.println("[code modekecocokan] selected passage:\n" + passage.text());
		assertTrue(passage.text().contains("MODEKECOCOK AN"), passage.text());
		assertTrue(passage.text().contains("SIMILARITY"), passage.text());
		assertTrue(passage.text().contains("EXACT"), passage.text());
		// Not the bare header row, which carries no codes.
		assertFalse(passage.text().equals("No | TX | Code | Penjelasan"), passage.text());
	}

	@Test
	void returnsASingleGfmRowWithoutItsNeighbouringParameterRows() {
		var passage = reranker.select("coba jelaskan apa yang dimaksud dengan MODEKECOCOKAN",
				List.of(chunk(180L, GFM_PARAMETER_CHUNK, 0.45))).getPassages().getFirst();

		System.out.println("[gfm modekecocokan] selected passage:\n" + passage.text());
		// The matched row, whole and on its own line.
		assertFalse(passage.text().contains("\n"), passage.text());
		assertTrue(passage.text().contains("MODEKECOCOK AN"), passage.text());
		assertTrue(passage.text().contains("menentukan mode atau metode pencocokan data"), passage.text());
		// The neighbouring parameter rows must not leak in as distractors.
		assertFalse(passage.text().contains("PARAM_TARGE"), passage.text());
		assertFalse(passage.text().contains("target fasilitas"), passage.text());
		assertFalse(passage.text().contains("CUSTOMERTYP"), passage.text());
	}

	@Test
	void doesNotRewardAMereMentionThatIsNotADefinition() {
		// Only the nav row is available: "cbas" still matches (2.0) so an answer is returned, but no copula
		// follows the term, so the definition bonus must not fire.
		var passage = reranker.select(WHAT_IS_CBAS_QUESTION, List.of(chunk(1L, NAV_CHUNK, NAV_SCORE)))
				.getPassages().getFirst();

		assertEquals(2.0 + NAV_SCORE, passage.score(), 1e-9);
	}

	private KnowledgeSearchResult chunk(Long chunkId, String content, double score) {
		var result = new KnowledgeSearchResult();
		result.setChunkId(chunkId);
		result.setChunkIndex(0);
		result.setContent(content);
		result.setScore(score);
		result.setDocumentId(7L);
		result.setLogicalDocumentId(7L);
		result.setVersionNumber(1);
		result.setDocumentTitle("FDS");
		return result;
	}
}
