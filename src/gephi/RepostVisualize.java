package gephi;

import agent.Agent;
import network.Network;
import org.gephi.graph.api.*;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.gephi.io.exporter.api.ExportController;
import org.openide.util.Lookup;

import java.io.File;
import java.util.List;
import java.util.Map;

public class RepostVisualize {

    private int n;
    private GraphModel graphModel;
    private Graph graph;
    private Workspace repostWorkspace;
    private ExportController exportController;

    // constructor
    public RepostVisualize(Agent[] agents) {
        this.n = agents.length;

        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
        pc.newProject();
        this.repostWorkspace = pc.newWorkspace(pc.getCurrentProject());
        pc.openWorkspace(repostWorkspace);
        graphModel = Lookup.getDefault().lookup(GraphController.class).getGraphModel();
        graph = graphModel.getDirectedGraph();
        exportController = Lookup.getDefault().lookup(ExportController.class);

        initializeGraph(agents);
    }

    private void initializeGraph(Agent[] agents) {
        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
        pc.openWorkspace(this.repostWorkspace);
        int nodeCount = agents.length;

        Column opinionColumn = graphModel.getNodeTable().getColumn("opinion");
        if (opinionColumn == null) {
            graphModel.getNodeTable().addColumn("opinion", Double.class);
        }

        Column communityColumn = graphModel.getNodeTable().getColumn("community");
        if (communityColumn == null) {
            graphModel.getNodeTable().addColumn("community", Integer.class);
        }

        Column opinionClass = graphModel.getNodeTable().getColumn("opinionClass");
        if (opinionClass == null) {
            graphModel.getNodeTable().addColumn("opinionClass", Integer.class);
        }

        Column boundedConfidence = graphModel.getNodeTable().getColumn("boundedConfidence");
        if (boundedConfidence == null) {
            graphModel.getNodeTable().addColumn("boundedConfidence", Double.class);
        }

        Column postProb = graphModel.getNodeTable().getColumn("postProb");
        if (postProb == null) {
            graphModel.getNodeTable().addColumn("postProb", Double.class);
        }

        Column accessProb = graphModel.getNodeTable().getColumn("accessProb");
        if (accessProb == null) {
            graphModel.getNodeTable().addColumn("accessProb", Double.class);
        }

        Column shiftedOpinion = graphModel.getNodeTable().getColumn("shiftedOpinion");
        if (shiftedOpinion == null) {
            graphModel.getNodeTable().addColumn("shiftedOpinion", Double.class);
        }

        Column intrinsicOpinion = graphModel.getNodeTable().getColumn("intrinsicOpinion");
        if (intrinsicOpinion == null) {
            graphModel.getNodeTable().addColumn("intrinsicOpinion", Double.class);
        }

        Column targetCol = graphModel.getNodeTable().getColumn("target");
        if (targetCol == null) {
            graphModel.getNodeTable().addColumn("target", Boolean.class);
        }

        Column step = graphModel.getNodeTable().getColumn("step");
        if (step == null) {
            graphModel.getNodeTable().addColumn("step", Integer.class);
        }

        for (int i = 0; i < nodeCount; i++) {
            Node node = graphModel.factory().newNode(String.valueOf(i));
            node.setLabel("Node " + i);
            node.setAttribute("opinion", agents[i].getOpinion());
            node.setAttribute("community", -1);
            node.setAttribute("opinionClass", agents[i].getOpinionClass());
            node.setAttribute("boundedConfidence", agents[i].getBc());
            node.setAttribute("postProb", agents[i].getPostProb());
            node.setAttribute("accessProb", agents[i].getAccessProb());
            node.setAttribute("shiftedOpinion", agents[i].getOpinion() - agents[i].getIntrinsicOpinion());
            node.setAttribute("intrinsicOpinion", agents[i].getIntrinsicOpinion());
            node.setAttribute("target", agents[i].getTarget());
            node.setAttribute("step", 0);
            graph.addNode(node);
        }
    }

    public void assignCommunities(Map<Integer, List<Integer>> communityGroups) {
        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
        pc.openWorkspace(this.repostWorkspace);
        Column communityColumn = graphModel.getNodeTable().getColumn("community");
        if (communityColumn == null) {
            graphModel.getNodeTable().addColumn("community", Integer.class);
        }

        for (Map.Entry<Integer, List<Integer>> entry : communityGroups.entrySet()) {
            int communityId = entry.getKey();
            List<Integer> nodes = entry.getValue();

            for (int nodeId : nodes) {
                Node node = graph.getNode(String.valueOf(nodeId));
                if (node != null) {
                    node.setAttribute("community", communityId);
                }
            }
        }
    }

    // Homophily/hostile repost-edge split: homophilyW/hostileW are the two
    // mechanism-tagged accumulators (repostProb-gated in-bc relays vs outOfBCRepostProb-gated
    // out-of-bc relays; see OpinionDynamics.repostNetworkHomophily/Hostile and
    // Post.isOutOfBcRelay()). A single directed edge per (i,j) still carries the combined weight
    // (unchanged downstream semantics for anything reading edge weight), plus two new Integer
    // columns with the per-type counts and a String column giving the dominant type for quick
    // categorical coloring/filtering in Gephi -- "mixed" when a pair carried both types in the
    // same snapshot window.
    public void updateGraph(Agent[] agents, int[][] homophilyW, int[][] hostileW, int step) {
        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
        pc.openWorkspace(this.repostWorkspace);

        Column homophilyCol = graphModel.getEdgeTable().getColumn("homophilyReposts");
        if (homophilyCol == null) {
            graphModel.getEdgeTable().addColumn("homophilyReposts", Integer.class);
        }
        Column hostileCol = graphModel.getEdgeTable().getColumn("hostileReposts");
        if (hostileCol == null) {
            graphModel.getEdgeTable().addColumn("hostileReposts", Integer.class);
        }
        Column edgeTypeCol = graphModel.getEdgeTable().getColumn("dominantEdgeType");
        if (edgeTypeCol == null) {
            graphModel.getEdgeTable().addColumn("dominantEdgeType", String.class);
        }

        for (int i = 0; i < agents.length; i++) {
            Node node = graph.getNode(String.valueOf(i));
            if (node != null) {
                node.setAttribute("opinion", agents[i].getOpinion());
                node.setAttribute("opinionClass", agents[i].getOpinionClass());
                node.setAttribute("boundedConfidence", agents[i].getBc());
                node.setAttribute("postProb", agents[i].getPostProb());
                node.setAttribute("accessProb", agents[i].getAccessProb());
                node.setAttribute("shiftedOpinion", agents[i].getOpinion() - agents[i].getIntrinsicOpinion());
                node.setAttribute("intrinsicOpinion", agents[i].getIntrinsicOpinion());
                node.setAttribute("target", agents[i].getTarget());
                node.setAttribute("step", step);
            }
        }

        for (int i = 0; i < homophilyW.length; i++) {
            for (int j = 0; j < homophilyW[i].length; j++) {
                int hom = homophilyW[i][j];
                int hos = hostileW[i][j];
                int total = hom + hos;
                Node source = graph.getNode(String.valueOf(i));
                Node target = graph.getNode(String.valueOf(j));
                Edge edge = graph.getEdge(source, target);

                if (total == 0) {
                    if (edge != null) {
                        graph.removeEdge(edge);
                    }
                } else {
                    if (edge == null) {
                        edge = graphModel.factory().newEdge(source, target, true);
                        graph.addEdge(edge);
                    }
                    edge.setWeight(total);
                    edge.setAttribute("homophilyReposts", hom);
                    edge.setAttribute("hostileReposts", hos);
                    String dominant = (hom > 0 && hos > 0) ? "mixed" : (hos > 0 ? "hostile" : "homophily");
                    edge.setAttribute("dominantEdgeType", dominant);
                }
            }
        }
    }

    public void exportGraph(int step, String folderPath) {
        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
        pc.openWorkspace(this.repostWorkspace);
        try {
            String lambdaFolder = folderPath + "/GEXF/repostNW";
            File lambdaDir = new File(lambdaFolder);
            if (!lambdaDir.exists()) {
                lambdaDir.mkdirs();
            }

            String fileName = lambdaFolder + "/repost_step_" + step + ".gexf";
            File file = new File(fileName);

            exportController.exportFile(file);
            // System.out.println("Graph exported to " + fileName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
