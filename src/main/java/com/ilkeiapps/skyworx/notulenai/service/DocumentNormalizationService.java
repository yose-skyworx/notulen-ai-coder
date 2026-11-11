package com.ilkeiapps.skyworx.notulenai.service;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.ilkeiapps.skyworx.notulenai.bean.knowledge.NormalizedElement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Normalization layer between Tika extraction and {@code DocumentElement} persistence. Tika's
 * {@code /rmeta} endpoint returns well-formed XHTML, so it is parsed with the JDK's built-in XML
 * parser (no third-party HTML library). The parse yields an ordered list of {@link NormalizedElement}s
 * that preserve heading hierarchy (parent linkage), paragraph/table/list boundaries, extraction order,
 * and page/slide provenance (Tika wraps PDF pages in {@code div.page} and PPT slides in
 * {@code div.slide-content}). Whitespace is normalized and empty artifacts are dropped.
 * <p>
 * This decouples chunking from raw Tika formats: chunking consumes {@code DocumentElement}s, never
 * the Tika response. If the XHTML cannot be parsed, a best-effort single text element is produced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentNormalizationService {

	/** Mutable traversal state threaded through the recursive walk. */
	private static final class Context {
		final List<NormalizedElement> elements = new ArrayList<>();
		final Deque<HeadingRef> headingStack = new ArrayDeque<>();
		int page = 0;
		int slide = 0;
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
	public List<NormalizedElement> normalize(String xhtml, String documentTitle) {
		var ctx = new Context(0);

		var root = new NormalizedElement();
		root.setElementType("DOCUMENT");
		root.setSequenceNumber(0);
		root.setParentIndex(-1);
		root.setContent(documentTitle);
		root.setElementPath("/");
		ctx.elements.add(root);

		var body = parseBody(xhtml);
		if (body == null) {
			fallback(xhtml, ctx);
			return ctx.elements;
		}
		walk(body, ctx);
		return ctx.elements;
	}

	/** Parses the XHTML and returns the {@code <body>} element, or {@code null} if it cannot be parsed. */
	private Element parseBody(String xhtml) {
		if (xhtml == null || xhtml.isBlank()) {
			return null;
		}
		try {
			var factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			factory.setExpandEntityReferences(false);
			// XXE hardening: Tika's /rmeta output has no DOCTYPE or external entities.
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

			var builder = factory.newDocumentBuilder();
			var doc = builder.parse(new InputSource(new StringReader(xhtml)));
			var bodies = doc.getElementsByTagName("body");
			if (bodies.getLength() > 0 && bodies.item(0) instanceof Element body) {
				return body;
			}
			return doc.getDocumentElement();
		} catch (Exception e) {
			log.warn("unable to parse Tika XHTML, falling back to plain-text normalization: {}", e.getMessage());
			return null;
		}
	}

	private void walk(Element parent, Context ctx) {
		for (Element child : childElements(parent)) {
			var tag = child.getTagName().toLowerCase();
			switch (tag) {
				case "div" -> {
					var cls = child.getAttribute("class");
					if (cls.contains("page")) {
						ctx.page++;
					} else if (cls.contains("slide-content") || cls.contains("slide")) {
						ctx.slide++;
					}
					walk(child, ctx);
				}
				case "h1", "h2", "h3", "h4", "h5", "h6" -> emitHeading(child, tag, ctx);
				case "p" -> emitContent(child, "PARAGRAPH", ctx);
				case "table" -> emitTable(child, ctx);
				case "ul", "ol" -> emitContent(child, "LIST", ctx);
				case "img" -> emitImage(child, ctx);
				case "body", "article", "section" -> walk(child, ctx);
				default -> {
					// Unknown container: recurse if it has element children, otherwise treat text as OTHER.
					if (!childElements(child).isEmpty()) {
						walk(child, ctx);
					} else {
						emitContent(child, "OTHER", ctx);
					}
				}
			}
		}
	}

	private void emitHeading(Element el, String tag, Context ctx) {
		var text = normalizeText(el.getTextContent());
		if (text.isEmpty()) {
			return;
		}
		var level = Integer.parseInt(tag.substring(1));
		while (!ctx.headingStack.isEmpty() && ctx.headingStack.peek().level() >= level) {
			ctx.headingStack.pop();
		}
		var parentIndex = ctx.headingStack.isEmpty() ? ctx.rootIndex : ctx.headingStack.peek().index();

		var ne = base("HEADING", parentIndex, ctx);
		ne.setHeadingLevel(level);
		ne.setContent(text);
		ne.setElementPath(headingPath(ctx, text));
		var index = add(ne, ctx);
		ctx.headingStack.push(new HeadingRef(index, level, text));
	}

	private void emitContent(Element el, String type, Context ctx) {
		var text = normalizeText(el.getTextContent());
		if (text.isEmpty()) {
			return;
		}
		var parentIndex = ctx.headingStack.isEmpty() ? ctx.rootIndex : ctx.headingStack.peek().index();
		var ne = base(type, parentIndex, ctx);
		ne.setContent(text);
		ne.setElementPath(currentPath(ctx));
		add(ne, ctx);
	}

	private void emitTable(Element table, Context ctx) {
		var rows = new ArrayList<List<String>>();
		var trNodes = table.getElementsByTagName("tr");
		for (var i = 0; i < trNodes.getLength(); i++) {
			if (!(trNodes.item(i) instanceof Element tr)) {
				continue;
			}
			var cells = new ArrayList<String>();
			for (Element cell : childElements(tr)) {
				cells.add(normalizeText(cell.getTextContent()));
			}
			if (!cells.isEmpty()) {
				rows.add(cells);
			}
		}
		if (rows.isEmpty()) {
			return;
		}
		var parentIndex = ctx.headingStack.isEmpty() ? ctx.rootIndex : ctx.headingStack.peek().index();

		var tableEl = base("TABLE", parentIndex, ctx);
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
			var rowEl = base("TABLE_ROW", tableIndex, ctx);
			rowEl.setContent(String.join(" | ", row));
			rowEl.setElementPath(currentPath(ctx));
			add(rowEl, ctx);
		}
	}

	private void emitImage(Element img, Context ctx) {
		var alt = normalizeText(img.getAttribute("alt"));
		var parentIndex = ctx.headingStack.isEmpty() ? ctx.rootIndex : ctx.headingStack.peek().index();
		var ne = base("IMAGE", parentIndex, ctx);
		ne.setContent(alt);
		ne.setElementPath(currentPath(ctx));
		var meta = new LinkedHashMap<String, Object>();
		meta.put("src", img.getAttribute("src"));
		ne.setMetadata(meta);
		add(ne, ctx);
	}

	/** Produces a single OTHER element from the raw content when the XHTML could not be parsed. */
	private void fallback(String xhtml, Context ctx) {
		var text = normalizeText(xhtml == null ? "" : xhtml.replaceAll("<[^>]+>", " "));
		if (text.isEmpty()) {
			return;
		}
		var ne = base("OTHER", ctx.rootIndex, ctx);
		ne.setContent(text);
		ne.setElementPath("/");
		add(ne, ctx);
	}

	private NormalizedElement base(String type, int parentIndex, Context ctx) {
		var ne = new NormalizedElement();
		ne.setElementType(type);
		ne.setParentIndex(parentIndex);
		ne.setPageNumber(ctx.page > 0 ? ctx.page : null);
		ne.setSlideNumber(ctx.slide > 0 ? ctx.slide : null);
		return ne;
	}

	private int add(NormalizedElement ne, Context ctx) {
		var index = ctx.elements.size();
		ne.setSequenceNumber(index);
		ctx.elements.add(ne);
		return index;
	}

	/** Direct child element nodes of {@code parent}, in document order. */
	private List<Element> childElements(Element parent) {
		var out = new ArrayList<Element>();
		NodeList children = parent.getChildNodes();
		for (var i = 0; i < children.getLength(); i++) {
			var node = children.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE && node instanceof Element el) {
				out.add(el);
			}
		}
		return out;
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

	private String normalizeText(String text) {
		if (text == null) {
			return "";
		}
		return text.replaceAll("\\s+", " ").strip();
	}
}
