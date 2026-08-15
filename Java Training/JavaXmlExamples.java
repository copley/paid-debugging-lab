import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class JavaXmlExamples {

    public static void main(String[] args) throws Exception {
        String xml = "<orders><order id=\"1\">Coffee</order><order id=\"2\">Tea</order></orders>";

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        NodeList orders = document.getElementsByTagName("order");
        for (int i = 0; i < orders.getLength(); i++) {
            Element order = (Element) orders.item(i);
            System.out.println("order " + order.getAttribute("id") + ": " + order.getTextContent());
        }

        XPath xpath = XPathFactory.newInstance().newXPath();
        Double count = (Double) xpath.evaluate("count(/orders/order)", document, XPathConstants.NUMBER);
        System.out.println("xpath count: " + count.intValue());

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        java.io.StringWriter output = new java.io.StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        System.out.println("serialized XML: " + output.toString());
    }
}
