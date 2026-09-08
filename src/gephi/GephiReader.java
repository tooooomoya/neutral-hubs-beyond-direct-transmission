package gephi;

import agent.Agent;
import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public class GephiReader {

    public static double[][] readGraphEdges(int size, String filePath) {
        double[][] W = new double[size][size];
        try {
            File inputFile = new File(filePath);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(true);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(inputFile);
            doc.getDocumentElement().normalize();

            // --- Read edges ---
            NodeList edgeList = doc.getElementsByTagNameNS("http://gexf.net/1.3", "edge");
            for (int i = 0; i < edgeList.getLength(); i++) {
                Element edgeElem = (Element) edgeList.item(i);
                int source = Integer.parseInt(edgeElem.getAttribute("source"));
                int target = Integer.parseInt(edgeElem.getAttribute("target"));

                W[source][target] = 1.0;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return W;
    }

    public static void readGraphNodes(Agent[] agentSet, String filePath) {
        try {
            File inputFile = new File(filePath);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(true);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(inputFile);
            doc.getDocumentElement().normalize();

            // read nodes
            NodeList nodeList = doc.getElementsByTagNameNS("http://gexf.net/1.3", "node");
            for (int i = 0; i < nodeList.getLength(); i++) {
                Element nodeElem = (Element) nodeList.item(i);
                int id = Integer.parseInt(nodeElem.getAttribute("id"));

                NodeList attvaluesList = nodeElem.getElementsByTagNameNS("http://gexf.net/1.3", "attvalues");
                if (attvaluesList.getLength() > 0) {
                    Element attvaluesElem = (Element) attvaluesList.item(0);
                    NodeList attvalueList = attvaluesElem.getElementsByTagNameNS("http://gexf.net/1.3", "attvalue");

                    for (int j = 0; j < attvalueList.getLength(); j++) {
                        Element attvalueElem = (Element) attvalueList.item(j);
                        String attrFor = attvalueElem.getAttribute("for");
                        String value = attvalueElem.getAttribute("value");

                        switch (attrFor) {
                            case "opinion":
                                agentSet[id].setOpinion(Double.parseDouble(value));
                                break;
                            case "boundedconfidence":
                                agentSet[id].setBoundedConfidence(Double.parseDouble(value));
                                break;
                            case "postprob":
                                agentSet[id].setPostProb(Double.parseDouble(value));
                                break;
                            case "accessProb":
                                agentSet[id].setAccessProb(Double.parseDouble(value));
                                break;
                            case "intrinsicopinion":
                                agentSet[id].setIntrinsicOpinion(Double.parseDouble(value));
                                break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
