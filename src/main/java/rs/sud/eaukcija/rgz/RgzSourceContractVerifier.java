package rs.sud.eaukcija.rgz;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Parses only the selected non-personal WFS/schema contract, with external XML access prohibited. */
final class RgzSourceContractVerifier {
    private static final String WFS = "http://www.opengis.net/wfs/2.0";
    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String GML = "http://www.opengis.net/gml/3.2";

    private RgzSourceContractVerifier() { }

    static void verifyCapabilities(byte[] raw, String featureType) throws Exception {
        Element root = parse(raw).getDocumentElement();
        require(WFS.equals(root.getNamespaceURI()) && "WFS_Capabilities".equals(root.getLocalName())
                && "2.0.0".equals(root.getAttribute("version")));
        NodeList features = root.getElementsByTagNameNS(WFS, "FeatureType");
        int matches = 0;
        for (int i = 0; i < features.getLength(); i++) {
            Element feature = (Element) features.item(i);
            if (!featureType.equals(text(feature, WFS, "Name"))) continue;
            matches++;
            String crs = text(feature, WFS, "DefaultCRS");
            require("urn:ogc:def:crs:EPSG::25834".equals(crs) || "EPSG:25834".equals(crs));
        }
        require(matches == 1);
        NodeList formats = root.getElementsByTagNameNS("http://www.opengis.net/ows/1.1", "Value");
        boolean json = false;
        for (int i = 0; i < formats.getLength(); i++) {
            if ("application/json".equals(formats.item(i).getTextContent().trim())) json = true;
        }
        // Some services advertise formats on the feature type instead of the operation.
        NodeList featureFormats = root.getElementsByTagNameNS(WFS, "Format");
        for (int i = 0; i < featureFormats.getLength(); i++) {
            if ("application/json".equals(featureFormats.item(i).getTextContent().trim())) json = true;
        }
        require(json);
    }

    static RgzSourceContract verifySchema(
            RgzParcelProperties properties, String capabilitiesSha, byte[] raw, String schemaSha) throws Exception {
        Element root = parse(raw).getDocumentElement();
        require(XSD.equals(root.getNamespaceURI()) && "schema".equals(root.getLocalName()));
        String localName = properties.getFeatureType().split(":", 2)[1];
        NodeList types = root.getElementsByTagNameNS(XSD, "complexType");
        Element selected = null;
        for (int i = 0; i < types.getLength(); i++) {
            Element type = (Element) types.item(i);
            if ((localName + "Type").equals(type.getAttribute("name"))) {
                require(selected == null);
                selected = type;
            }
        }
        require(selected != null);
        Map<String, String> required = Map.of("cadmun_code", "int", "parcel_num", "string",
                "area", "decimal", "geom", "GeometryPropertyType");
        NodeList fields = selected.getElementsByTagNameNS(XSD, "element");
        for (var entry : required.entrySet()) {
            int found = 0;
            for (int i = 0; i < fields.getLength(); i++) {
                Element field = (Element) fields.item(i);
                if (!entry.getKey().equals(field.getAttribute("name"))) continue;
                found++;
                String[] type = field.getAttribute("type").split(":", 2);
                require(type.length == 2 && entry.getValue().equals(type[1]));
                require((entry.getKey().equals("geom") ? GML : XSD).equals(field.lookupNamespaceURI(type[0])));
            }
            require(found == 1);
        }
        return new RgzSourceContract(properties.sourceContractKey(), capabilitiesSha, schemaSha, Instant.now());
    }

    private static Document parse(byte[] raw) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
        return parser.parse(new ByteArrayInputStream(raw));
    }

    private static String text(Element parent, String namespace, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, name);
        return nodes.getLength() == 1 ? nodes.item(0).getTextContent().trim() : "";
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("RGZ metadata contract mismatch");
    }
}
