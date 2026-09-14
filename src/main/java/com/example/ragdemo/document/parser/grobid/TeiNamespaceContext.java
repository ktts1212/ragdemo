package com.example.ragdemo.document.parser.grobid;

import java.util.Iterator;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;

/**
 * XPath 求值用的 TEI namespace context。
 * <p>
 * GROBID TEI 使用默认 namespace（xmlns="http://www.tei-c.org/ns/1.0"），
 * 所有元素都在该 namespace 下，XPath 必须带 prefix 才能命中。
 */
public class TeiNamespaceContext implements NamespaceContext {

    @Override
    public String getNamespaceURI(String prefix) {
        if (GrobidTeiConstants.TEI_PREFIX.equals(prefix)) {
            return GrobidTeiConstants.TEI_NAMESPACE_URI;
        }
        if (XMLConstants.XML_NS_PREFIX.equals(prefix)) {
            return XMLConstants.XML_NS_URI;
        }
        return XMLConstants.NULL_NS_URI;
    }

    @Override
    public String getPrefix(String namespaceURI) {
        if (GrobidTeiConstants.TEI_NAMESPACE_URI.equals(namespaceURI)) {
            return GrobidTeiConstants.TEI_PREFIX;
        }
        return null;
    }

    @Override
    public Iterator<String> getPrefixes(String namespaceURI) {
        if (GrobidTeiConstants.TEI_NAMESPACE_URI.equals(namespaceURI)) {
            return List.of(GrobidTeiConstants.TEI_PREFIX).iterator();
        }
        return List.<String>of().iterator();
    }
}
