package com.atlas.enterprise.report.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class OoxmlDocxSupport {
    private static final int MAX_ZIP_ENTRIES = 512;
    private static final int MAX_PART_BYTES = 20 * 1024 * 1024;
    private static final int MAX_TOTAL_UNCOMPRESSED_BYTES = 100 * 1024 * 1024;
    static final String W =
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String XML = XMLConstants.XML_NS_URI;

    private OoxmlDocxSupport() {
    }

    static Map<String, byte[]> unzip(byte[] content) {
        try {
            Map<String, byte[]> parts = new LinkedHashMap<>();
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content))) {
                ZipEntry entry;
                int entries = 0;
                int totalBytes = 0;
                while ((entry = input.getNextEntry()) != null) {
                    if (++entries > MAX_ZIP_ENTRIES) {
                        throw new IllegalArgumentException("DOCX contains too many package parts");
                    }
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("../")) {
                        throw new IllegalArgumentException("DOCX contains an invalid package part path");
                    }
                    ByteArrayOutputStream part = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (part.size() + count > MAX_PART_BYTES
                            || totalBytes + count > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            throw new IllegalArgumentException("DOCX expanded content exceeds safety limits");
                        }
                        part.write(buffer, 0, count);
                        totalBytes += count;
                    }
                    if (!entry.isDirectory()) parts.put(name, part.toByteArray());
                }
            }
            if (!parts.containsKey("word/document.xml")) {
                throw new IllegalArgumentException("DOCX has no word/document.xml");
            }
            return parts;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read DOCX package", exception);
        }
    }

    static byte[] zip(Map<String, byte[]> parts) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                    ZipEntry entry = new ZipEntry(part.getKey());
                    zip.putNextEntry(entry);
                    zip.write(part.getValue());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write DOCX package", exception);
        }
    }

    static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not parse WordprocessingML", exception);
        }
    }

    static byte[] serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize WordprocessingML", exception);
        }
    }

    static List<Element> bodyParagraphs(Document document) {
        Element body = (Element) document.getElementsByTagNameNS(W, "body").item(0);
        List<Element> result = new ArrayList<>();
        for (Node child = body.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && W.equals(element.getNamespaceURI())
                && "p".equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    static List<Element> bodyTables(Document document) {
        Element body = (Element) document.getElementsByTagNameNS(W, "body").item(0);
        List<Element> result = new ArrayList<>();
        for (Node child = body.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && W.equals(element.getNamespaceURI())
                && "tbl".equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    static List<Element> rows(Element table) {
        return directChildren(table, "tr");
    }

    static List<Element> cells(Element row) {
        return directChildren(row, "tc");
    }

    static List<Element> directChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && W.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    static String text(Element element) {
        StringBuilder value = new StringBuilder();
        NodeList texts = element.getElementsByTagNameNS(W, "t");
        for (int index = 0; index < texts.getLength(); index++) {
            value.append(texts.item(index).getTextContent());
        }
        return value.toString();
    }

    static void setText(Element element, String value) {
        removeStaleTextControls(element);
        NodeList texts = element.getElementsByTagNameNS(W, "t");
        if (texts.getLength() == 0) {
            Element paragraph = "p".equals(element.getLocalName())
                ? element
                : firstDescendant(element, "p");
            if (paragraph == null) {
                paragraph = element.getOwnerDocument().createElementNS(W, "w:p");
                element.appendChild(paragraph);
            }
            Element run = element.getOwnerDocument().createElementNS(W, "w:r");
            Element text = element.getOwnerDocument().createElementNS(W, "w:t");
            run.appendChild(text);
            paragraph.appendChild(run);
            texts = element.getElementsByTagNameNS(W, "t");
        }
        Node first = texts.item(0);
        first.setTextContent(value == null ? "" : value);
        if (first instanceof Element firstText) {
            firstText.setAttributeNS(XML, "xml:space", "preserve");
        }
        for (int index = 1; index < texts.getLength(); index++) {
            texts.item(index).setTextContent("");
        }
    }

    private static void removeStaleTextControls(Element element) {
        for (String localName : List.of("br", "cr", "tab")) {
            NodeList controls = element.getElementsByTagNameNS(W, localName);
            for (int index = controls.getLength() - 1; index >= 0; index--) {
                Node control = controls.item(index);
                if (control.getParentNode() != null) {
                    control.getParentNode().removeChild(control);
                }
            }
        }
    }

    static String paragraphStyle(Element paragraph) {
        NodeList styles = paragraph.getElementsByTagNameNS(W, "pStyle");
        if (styles.getLength() == 0) {
            return "";
        }
        Element style = (Element) styles.item(0);
        return style.getAttributeNS(W, "val");
    }

    static Element cloneElement(Element element) {
        return (Element) element.cloneNode(true);
    }

    static void setTableColumnWidths(Element table, List<Integer> widths) {
        List<Element> grids = directChildren(table, "tblGrid");
        if (grids.isEmpty()) {
            throw new IllegalArgumentException("Table has no tblGrid definition");
        }
        List<Element> columns = directChildren(grids.getFirst(), "gridCol");
        if (columns.size() != widths.size()) {
            throw new IllegalArgumentException("Table grid does not match requested widths");
        }
        for (int index = 0; index < columns.size(); index++) {
            columns.get(index).setAttributeNS(W, "w:w", Integer.toString(widths.get(index)));
        }
        for (Element row : rows(table)) {
            List<Element> rowCells = cells(row);
            if (rowCells.size() != widths.size()) {
                throw new IllegalArgumentException("Table row does not match requested widths");
            }
            for (int index = 0; index < rowCells.size(); index++) {
                Element cell = rowCells.get(index);
                List<Element> properties = directChildren(cell, "tcPr");
                Element cellProperties;
                if (properties.isEmpty()) {
                    cellProperties = cell.getOwnerDocument().createElementNS(W, "w:tcPr");
                    cell.insertBefore(cellProperties, cell.getFirstChild());
                } else {
                    cellProperties = properties.getFirst();
                }
                List<Element> existingWidths = directChildren(cellProperties, "tcW");
                Element cellWidth;
                if (existingWidths.isEmpty()) {
                    cellWidth = cell.getOwnerDocument().createElementNS(W, "w:tcW");
                    cellProperties.insertBefore(cellWidth, cellProperties.getFirstChild());
                } else {
                    cellWidth = existingWidths.getFirst();
                }
                cellWidth.setAttributeNS(W, "w:w", Integer.toString(widths.get(index)));
                cellWidth.setAttributeNS(W, "w:type", "dxa");
            }
        }
    }

    static void repeatTableHeader(Element row) {
        List<Element> properties = directChildren(row, "trPr");
        Element rowProperties;
        if (properties.isEmpty()) {
            rowProperties = row.getOwnerDocument().createElementNS(W, "w:trPr");
            row.insertBefore(rowProperties, row.getFirstChild());
        } else {
            rowProperties = properties.getFirst();
        }
        List<Element> existing = directChildren(rowProperties, "tblHeader");
        Element header = existing.isEmpty()
            ? row.getOwnerDocument().createElementNS(W, "w:tblHeader")
            : existing.getFirst();
        header.setAttributeNS(W, "w:val", "true");
        if (existing.isEmpty()) {
            rowProperties.appendChild(header);
        }
    }

    static void keepTableRowTogether(Element row) {
        List<Element> properties = directChildren(row, "trPr");
        Element rowProperties;
        if (properties.isEmpty()) {
            rowProperties = row.getOwnerDocument().createElementNS(W, "w:trPr");
            row.insertBefore(rowProperties, row.getFirstChild());
        } else {
            rowProperties = properties.getFirst();
        }
        if (directChildren(rowProperties, "cantSplit").isEmpty()) {
            rowProperties.appendChild(
                row.getOwnerDocument().createElementNS(W, "w:cantSplit")
            );
        }
    }

    static Element firstDescendant(Element element, String localName) {
        NodeList values = element.getElementsByTagNameNS(W, localName);
        return values.getLength() == 0 ? null : (Element) values.item(0);
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
