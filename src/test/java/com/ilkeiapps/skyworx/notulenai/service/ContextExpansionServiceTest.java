package com.ilkeiapps.skyworx.notulenai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.KnowledgeSearchResult;
import com.ilkeiapps.skyworx.notulenai.config.KnowledgeProperties;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Covers the read-time enrichment that decides what the reranker gets to choose from. The behaviour that
 * breaks RAG if it is wrong: a heading must be prepended exactly once, so a chunk that already names its own
 * section must not gain a duplicate line; siblings must reach only the hits too short to be self-contained;
 * and the sibling budget must bound what is appended rather than the whole rewritten chunk.
 * <p>
 * The two queries are stubbed, not executed — running them would need a Spring context and the shared
 * database, and neither is required to check the assembly logic.
 */
class ContextExpansionServiceTest {

	private static final String PATH_MARKER = "e.element_path";
	private static final String SIBLING_MARKER = "sib.sequence_number";

	private EntityManager em;
	private KnowledgeProperties properties;
	private ContextExpansionService service;

	private List<Object[]> pathRows;
	private List<Object[]> siblingRows;

	@BeforeEach
	void setUp() {
		em = mock(EntityManager.class);
		properties = new KnowledgeProperties();
		service = new ContextExpansionService(em, properties);
		pathRows = new ArrayList<>();
		siblingRows = new ArrayList<>();
		stubQuery(PATH_MARKER, pathRows);
		stubQuery(SIBLING_MARKER, siblingRows);
	}

	@Test
	void doesNothingWhenDisabled() {
		properties.getExpansion().setEnabled(false);
		var hits = new ArrayList<>(List.of(hit(1L, "| Klik | button |")));

		service.enrich(7L, hits);

		verifyNoInteractions(em);
		assertEquals("| Klik | button |", hits.getFirst().getContent());
	}

	@Test
	void prependsTheAncestorPathAsOneLine() {
		pathRows.add(new Object[] { 1L, "/E. MASTER DATA/10. Token Core" });
		var hits = new ArrayList<>(List.of(hit(1L, "| Edit data parameter |")));

		service.enrich(7L, hits);

		assertEquals("[E. MASTER DATA > 10. Token Core]\n| Edit data parameter |", hits.getFirst().getContent());
	}

	@Test
	void doesNotRepeatAHeadingTheChunkAlreadyCarries() {
		pathRows.add(new Object[] { 1L, "/E. MASTER DATA/10. Token Core" });
		var original = "10. Token Core\n| Edit data parameter |";
		var hits = new ArrayList<>(List.of(hit(1L, original)));

		service.enrich(7L, hits);

		assertEquals(original, hits.getFirst().getContent());
	}

	@Test
	void completesAShortHitWithItsSiblingsInOrderAndWithoutDuplicates() {
		pathRows.add(new Object[] { 1L, "/E. MASTER DATA/10. Token Core" });
		siblingRows.add(new Object[] { 1L, 11, "row A" });
		siblingRows.add(new Object[] { 1L, 12, "row B" });
		// A chunk whose source elements share a parent yields each sibling once per element.
		siblingRows.add(new Object[] { 1L, 11, "row A" });
		var hits = new ArrayList<>(List.of(hit(1L, "row 0")));

		service.enrich(7L, hits);

		assertEquals("[E. MASTER DATA > 10. Token Core]\nrow 0\nrow A\nrow B", hits.getFirst().getContent());
	}

	@Test
	void budgetCountsTheSiblingsNotTheChunkTheyAreAddedTo() {
		properties.getExpansion().setMaxSiblingChars(20);
		pathRows.add(new Object[] { 1L, "/A VERY LONG SECTION NAME THAT ALONE EXCEEDS THE BUDGET" });
		siblingRows.add(new Object[] { 1L, 11, "aaaaaaa" });
		siblingRows.add(new Object[] { 1L, 12, "bbbbbbb" });
		siblingRows.add(new Object[] { 1L, 13, "ccccccc" });
		var hits = new ArrayList<>(List.of(hit(1L, "base")));

		service.enrich(7L, hits);

		assertEquals("[A VERY LONG SECTION NAME THAT ALONE EXCEEDS THE BUDGET]\nbase\naaaaaaa\nbbbbbbb",
				hits.getFirst().getContent());
	}

	@Test
	void leavesASelfContainedHitAloneAndNeverAsksForSiblings() {
		pathRows.add(new Object[] { 1L, "/E. MASTER DATA" });
		var longContent = "x".repeat(800);
		var hits = new ArrayList<>(List.of(hit(1L, longContent)));

		service.enrich(7L, hits);

		assertEquals("[E. MASTER DATA]\n" + longContent, hits.getFirst().getContent());
		verify(em, never()).createNativeQuery(contains(SIBLING_MARKER));
	}

	@Test
	void toleratesAMissingPathAndAMissingContent() {
		pathRows.add(new Object[] { 2L, null });
		var hits = new ArrayList<>(List.of(hit(1L, null), hit(2L, "row 0")));

		service.enrich(7L, hits);

		assertNull(hits.get(0).getContent());
		assertEquals("row 0", hits.get(1).getContent());
	}

	private void stubQuery(String sqlMarker, List<Object[]> rows) {
		var query = mock(Query.class);
		when(query.setParameter(anyString(), any())).thenReturn(query);
		doReturn(rows).when(query).getResultList();
		when(em.createNativeQuery(contains(sqlMarker))).thenReturn(query);
	}

	private KnowledgeSearchResult hit(Long chunkId, String content) {
		var hit = new KnowledgeSearchResult();
		hit.setChunkId(chunkId);
		hit.setContent(content);
		return hit;
	}
}
