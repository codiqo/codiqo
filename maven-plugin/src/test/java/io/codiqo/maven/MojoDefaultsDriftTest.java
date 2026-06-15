package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import io.codiqo.api.RunArgs;

/**
 * Guards against the mojo @Parameter defaults silently diverging from the RunArgs field defaults —
 * the mojo value always overwrites RunArgs for maven runs, so a divergence makes the same commit
 * score differently depending on the entry point. @Parameter has CLASS retention, so the generated
 * plugin descriptor is the only place the defaults are observable at test time.
 */
class MojoDefaultsDriftTest {
    @Test
    void scoreBearingMojoDefaultsMatchRunArgsDefaults() throws Exception {
        Document pluginXml = parsePluginDescriptor();
        RunArgs reference = new RunArgs();

        assertEquals(String.valueOf(reference.getSpotbugsPriorityThreshold()), defaultValueOf(pluginXml, "spotbugsPriorityThreshold"));
        assertEquals(reference.getPmdMinPriority().toUpperCase(), defaultValueOf(pluginXml, "pmdMinPriority").toUpperCase());
        assertEquals(String.valueOf(reference.getCpdMinimumTileSize()), defaultValueOf(pluginXml, "cpdMinimumTileSize"));

        assertEquals(String.valueOf(reference.getMaxRequests()), defaultValueOf(pluginXml, "maxRequests"));
        assertEquals(String.valueOf(reference.getMaxRequestsPerHost()), defaultValueOf(pluginXml, "maxRequestsPerHost"));
    }
    private static Document parsePluginDescriptor() throws Exception {
        try (InputStream in = MojoDefaultsDriftTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            assertNotNull(in, "plugin descriptor must be generated before tests run");
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
    }
    private static String defaultValueOf(Document pluginXml, String fieldName) {
        NodeList elements = pluginXml.getElementsByTagName(fieldName);
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.hasAttribute("default-value")) {
                return element.getAttribute("default-value");
            }
        }
        throw new AssertionError("no default-value found in plugin.xml for field: " + fieldName);
    }
}
