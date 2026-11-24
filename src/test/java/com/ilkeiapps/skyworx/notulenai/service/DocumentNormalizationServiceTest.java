package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.TikaExtraction;
import com.ilkeiapps.skyworx.notulenai.config.TikaProperties;

/**
 * Covers the mapping from the converter's {@code elements} array onto {@code NormalizedElement}s, which
 * is what {@code document_elements} rows and therefore every chunk are built from. The three things that
 * break RAG if they are wrong: sequence numbers must equal list positions (persistence resolves a parent
 * by index), {@code parent_id} must become that index, and cleaning must collapse the dot leaders a table
 * of contents is full of without flattening the line breaks a list and a table carry their structure in.
 */
class DocumentNormalizationServiceTest {

	private static final String TITLE = "User Guide CBAS";

	private final DocumentNormalizationService service = new DocumentNormalizationService();
	private final TikaExtractionService extractionService = new TikaExtractionService(null, new TikaProperties());

	@Test
	void mapsConverterElementsOntoTheNormalizedSequence() {
		var normalized = service.normalize(extraction(), TITLE);

		// Synthetic root + the 7 converted elements.
		assertEquals(8, normalized.size());
		for (var i = 0; i < normalized.size(); i++) {
			assertEquals(i, normalized.get(i).getSequenceNumber(),
					"sequence numbers must equal list positions or parent resolution breaks");
		}

		var root = normalized.getFirst();
		assertEquals("DOCUMENT", root.getElementType());
		assertEquals(-1, root.getParentIndex());
		assertEquals(TITLE, root.getContent());
		assertEquals("/", root.getElementPath());
	}

	@Test
	void resolvesParentIdToTheParentSequenceIndex() {
		var normalized = service.normalize(extraction(), TITLE);

		// el-000001 is a top-level heading -> the root; el-000004 nests under it.
		assertEquals(0, normalized.get(1).getParentIndex());
		assertEquals(1, normalized.get(4).getParentIndex());
		// The list and the table both sit under the level-2 heading at index 4.
		assertEquals(4, normalized.get(5).getParentIndex());
		assertEquals(4, normalized.get(6).getParentIndex());
		// A parent_id the converter never emitted falls back to the root instead of failing the document.
		assertEquals(0, normalized.get(7).getParentIndex());
	}

	@Test
	void derivesTypeLevelPageAndHeadingPath() {
		var normalized = service.normalize(extraction(), TITLE);

		var heading = normalized.get(4);
		assertEquals("HEADING", heading.getElementType());
		assertEquals(Integer.valueOf(2), heading.getHeadingLevel());
		assertEquals(Integer.valueOf(7), heading.getPageNumber());
		assertEquals("/User Guide CBAS/Menu Branch", heading.getElementPath());
		assertNull(heading.getSlideNumber());

		var list = normalized.get(5);
		assertEquals("LIST", list.getElementType());
		assertNull(list.getHeadingLevel());
		assertEquals("/User Guide CBAS/Menu Branch", list.getElementPath());

		assertEquals("PARAGRAPH", normalized.get(3).getElementType());
		assertEquals("TABLE", normalized.get(6).getElementType());
		// An empty heading_path and an unnamed type both degrade instead of producing a null column.
		assertEquals("/", normalized.get(7).getElementPath());
	}

	@Test
	void cleansTextWithoutFlatteningLineBreaks() {
		var normalized = service.normalize(extraction(), TITLE);

		// Dot leaders collapse; they carry no meaning and inflate the token estimate fourfold.
		assertEquals("Objectives. 4", normalized.get(3).getContent());
		// A list keeps one line per item, and a table keeps its GFM rows and delimiter.
		assertEquals("1. LOGIN. 6\n2. DASHBOARD. 7", normalized.get(5).getContent());
		assertEquals("| No | Code |\n| --- | --- |\n| 7 | PARAM |", normalized.get(6).getContent());
		// Backslash escapes are undone and horizontal whitespace runs collapse.
		assertEquals("Escaped _underscore_ and spaced text.", normalized.get(7).getContent());
	}

	@Test
	void keepsTypeSpecificFieldsAsStructuredContent() {
		var normalized = service.normalize(extraction(), TITLE);

		assertTrue(normalized.get(1).getStructuredContent() == null
				|| normalized.get(1).getStructuredContent().isEmpty(), "a heading has no extras to keep");

		var list = normalized.get(5).getStructuredContent();
		assertEquals(3, list.size());
		assertEquals(Boolean.TRUE, list.get("ordered"));
		assertEquals(List.of("LOGIN................ 6", "DASHBOARD................ 7"), list.get("items"));

		var table = normalized.get(6).getStructuredContent();
		assertEquals(List.of("No", "Code"), table.get("headers"));
		assertEquals(List.of(List.of("7", "PARAM")), table.get("rows"));
	}

	@Test
	void producesOnlyTheRootWhenTheConverterReturnedNoElements() {
		var normalized = service.normalize(new TikaExtraction(List.of(), Map.of()), TITLE);

		assertEquals(1, normalized.size());
		assertEquals("DOCUMENT", normalized.getFirst().getElementType());
	}

	private TikaExtraction extraction() {
		return extractionService.parse("""
				{
				  "metadata": {"filename": "mundur.pdf", "format": "pdf", "page_count": 148},
				  "elements": [
				    {"id":"el-000001","type":"heading","text":"User Guide CBAS","level":1,"page":null,
				     "parent_id":null,"heading_path":["User Guide CBAS"]},
				    {"id":"el-000002","type":"heading","text":"DAFTAR ISI","level":1,"page":null,
				     "parent_id":null,"heading_path":["DAFTAR ISI"]},
				    {"id":"el-000003","type":"paragraph","text":"Objectives................ 4","page":null,
				     "parent_id":"el-000002","heading_path":["DAFTAR ISI"],"images":null},
				    {"id":"el-000004","type":"heading","text":"Menu Branch","level":2,"page":7,
				     "parent_id":"el-000001","heading_path":["User Guide CBAS","Menu Branch"]},
				    {"id":"el-000005","type":"list","text":"1. LOGIN................ 6\\n2. DASHBOARD................ 7",
				     "page":null,"parent_id":"el-000004","heading_path":["User Guide CBAS","Menu Branch"],
				     "ordered":true,"items":["LOGIN................ 6","DASHBOARD................ 7"],"start":1},
				    {"id":"el-000006","type":"table","text":"| No | Code |\\n| --- | --- |\\n| 7 | PARAM |",
				     "page":null,"parent_id":"el-000004","heading_path":["User Guide CBAS","Menu Branch"],
				     "headers":["No","Code"],"rows":[["7","PARAM"]]},
				    {"id":"el-000007","type":"paragraph","text":"Escaped \\\\_underscore\\\\_ and   spaced   text.",
				     "page":null,"parent_id":"el-000099","heading_path":[],"images":null}
				  ],
				  "element_count": 7,
				  "schema_version": "1.0"
				}
				""");
	}
}
