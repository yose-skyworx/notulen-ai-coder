package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.config.TikaProperties;

/**
 * Covers the binding of the {@code POST /v1/convert} response ({@code schema_version} 1.0), which is the
 * only thing standing between the converter's wire format and everything downstream. The fixture mirrors
 * the real payload's shape: a {@code metadata} object, an ordered {@code elements} array mixing the four
 * element types, explicit {@code null}s for the fields an element does not have, and type-specific fields
 * ({@code items}/{@code ordered}/{@code start}, {@code headers}/{@code rows}, {@code images}) that must
 * survive as extras rather than being dropped.
 */
class TikaExtractionServiceTest {

	private static final String RESPONSE = """
			{
			  "metadata": {
			    "filename": "mundur.pdf",
			    "format": "pdf",
			    "bytes": 19545849,
			    "page_count": 148,
			    "ocr_pages": [],
			    "ocr_engine": null,
			    "duration_ms": 1600.1
			  },
			  "elements": [
			    {
			      "id": "el-000001",
			      "type": "heading",
			      "text": "User Guide CBAS",
			      "level": 1,
			      "page": null,
			      "parent_id": null,
			      "heading_path": ["User Guide CBAS"]
			    },
			    {
			      "id": "el-000003",
			      "type": "paragraph",
			      "text": "Objectives................ 4",
			      "page": null,
			      "parent_id": "el-000002",
			      "heading_path": ["DAFTAR ISI"],
			      "images": null
			    },
			    {
			      "id": "el-000005",
			      "type": "list",
			      "text": "1. LOGIN................ 6\\n2. DASHBOARD................ 7",
			      "page": null,
			      "parent_id": "el-000004",
			      "heading_path": ["User Guide CBAS", "Menu Branch"],
			      "ordered": true,
			      "items": ["LOGIN................ 6", "DASHBOARD................ 7"],
			      "start": 1
			    },
			    {
			      "id": "el-000006",
			      "type": "table",
			      "text": "| No | Code |\\n| --- | --- |\\n| 7 | PARAM |",
			      "page": 7,
			      "parent_id": "el-000004",
			      "heading_path": ["User Guide CBAS", "Menu Branch"],
			      "headers": ["No", "Code"],
			      "rows": [["7", "PARAM"]]
			    }
			  ],
			  "element_count": 4,
			  "schema_version": "1.0"
			}
			""";

	private final TikaExtractionService service = new TikaExtractionService(null, new TikaProperties());

	@Test
	void parsesMetadataAndTheOrderedElementArray() {
		var extraction = service.parse(RESPONSE);

		assertEquals(4, extraction.elements().size());
		assertEquals(148, extraction.metadata().get("page_count"));
		assertEquals("pdf", extraction.metadata().get("format"));

		var heading = extraction.elements().getFirst();
		assertEquals("el-000001", heading.getId());
		assertEquals("heading", heading.getType());
		assertEquals("User Guide CBAS", heading.getText());
		assertEquals(Integer.valueOf(1), heading.getLevel());
		assertNull(heading.getPage());
		assertNull(heading.getParentId());
		assertEquals(List.of("User Guide CBAS"), heading.getHeadingPath());
		assertTrue(heading.getExtras().isEmpty(), "a heading carries no type-specific fields");
	}

	@Test
	void collectsTypeSpecificFieldsAsExtrasAndDropsNulls() {
		var elements = service.parse(RESPONSE).elements();

		var paragraph = elements.get(1);
		assertEquals("el-000002", paragraph.getParentId());
		assertTrue(paragraph.getExtras().isEmpty(), "images: null must not become an extra");

		var list = elements.get(2);
		assertEquals(3, list.getExtras().size());
		assertEquals(Boolean.TRUE, list.getExtras().get("ordered"));
		assertEquals(1, list.getExtras().get("start"));
		assertEquals(List.of("LOGIN................ 6", "DASHBOARD................ 7"), list.getExtras().get("items"));

		var table = elements.get(3);
		assertEquals(Integer.valueOf(7), table.getPage());
		assertEquals(List.of("No", "Code"), table.getExtras().get("headers"));
		assertEquals(List.of(List.of("7", "PARAM")), table.getExtras().get("rows"));
	}

	@Test
	void rejectsAResponseWithoutAnElementsArray() {
		var message = assertThrows(IllegalStateException.class,
				() -> service.parse("{\"metadata\":{},\"markdown\":\"# still the old shape\"}")).getMessage();
		assertEquals("Document conversion returned no elements", message);
	}
}
