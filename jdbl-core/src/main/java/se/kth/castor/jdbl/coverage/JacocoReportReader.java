package se.kth.castor.jdbl.coverage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class JacocoReportReader
{
    private UsageAnalysis usageAnalysis;
    private final DocumentBuilder dBuilder;

    public JacocoReportReader() throws ParserConfigurationException
    {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();

        dBuilder = dbFactory.newDocumentBuilder();
        // Ignore the lack of DTD
        dBuilder.setEntityResolver((publicId, systemId) -> {
            if (systemId.contains(".dtd")) {
                return new InputSource(new StringReader(""));
            } else {
                return null;
            }
        });
    }

    /**
     * Return a collection of used classes and used methods organized as following:
     * Map: class fullyQualifiedName -> Set< used method qualifier > if the class contains covered methods
     * method qualifier = methodSimpleName + descriptor
     * descriptor = (paramTypes;*)returnType
     * ex: method "contains(Ljava/lang/Object;)Z" is named contains and take an object as parameter and return a boolean
     */
    public UsageAnalysis getUsedClassesAndMethods(File xmlJacocoReport) throws IOException, SAXException
    {
        usageAnalysis = new UsageAnalysis();
        Document doc = dBuilder.parse(xmlJacocoReport);
        doc.getDocumentElement().normalize();

        NodeList packages = doc.getElementsByTagName("package");
        for (int i = 0; i < packages.getLength(); i++) {
            visitPackage(packages.item(i));
        }

        if (new File("agentCoverage.csv").exists()) {
            writeAgentCoverage();
        }

        // Remove all classes that do not contain any covered method
        usageAnalysis.removeUncoveredClasses();

        return usageAnalysis;
    }

    private void visitPackage(Node p)
    {
        NodeList classes = p.getChildNodes();
        for (int i = 0; i < classes.getLength(); i++) {
            Node n = classes.item(i);
            if (n.getNodeName().equals("class")) {
                visitClass(n);
            }
        }
    }

    private void visitClass(Node c)
    {
        NodeList methods = c.getChildNodes();

        // interface have no child nodes, and we ignore them (coverage does not make sense)
        if (methods.getLength() == 0) {
            return;
        }
        usageAnalysis.addEntry(c.getAttributes().getNamedItem("name").getNodeValue(), new HashSet<>());
        for (int i = 0; i < methods.getLength(); i++) {
            Node n = methods.item(i);
            if (!n.getNodeName().equals("method")) {
                continue;
            }
            visitMethod(n);
        }
    }

    private void visitMethod(Node m)
    {
        if (!isCovered(m, "METHOD")) {
            return;
        }
        String desc = m.getAttributes().getNamedItem("name").getNodeValue() +
            m.getAttributes().getNamedItem("desc").getNodeValue();

        // we add the method only if it is covered
        usageAnalysis.methods(m.getParentNode().getAttributes().getNamedItem("name").getNodeValue()).add(desc);
    }

    private boolean isCovered(Node c, String entity)
    {
        // we look for a child node like <counter type="entity" ... covered="?"> if ? equals "0" it is not covered,
        // otherwise it is
        NodeList counters = c.getChildNodes();
        for (int i = 0; i < counters.getLength(); i++) {
            Node n = counters.item(i);
            if (!n.getNodeName().equals("counter")) {
                continue;
            }
            Node type = n.getAttributes().getNamedItem("type");
            if (type == null) {
                continue;
            } else if (!type.getNodeValue().equals(entity)) {
                continue;
            } else {
                return !n.getAttributes().getNamedItem("covered").getNodeValue().equals("0");
            }
        }
        return true;
    }

    private void writeAgentCoverage() throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File("agentCoverage.csv")))) {
            String line = reader.readLine();
            while (line != null) {
                String[] splitLine = line.split(",");
                String type = splitLine[0].replace(".", "/");
                String method = splitLine[1] + splitLine[2];
                if (usageAnalysis.containsClazz(type)) {
                    if (usageAnalysis.containsClazz(type)) {
                        Set<String> methods = usageAnalysis.getAnalysis().computeIfAbsent(type, s -> new HashSet<>());
                        methods.add(method);
                    }
                }
                line = reader.readLine();
            }
        }
    }
}
