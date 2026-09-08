package network;

import agent.Agent;
import java.util.*;
import rand.randomGenerator;

public class ConnectingNearestNeighborNetwork extends Network {
    private double p; // prob of setting potential edge as actual edge
    private Set<Edge> potentialEdges;

    private static class Edge {
        int from, to;

        Edge(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Edge))
                return false;
            Edge other = (Edge) o;
            return this.from == other.from && this.to == other.to;
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to);
        }
    }

    public ConnectingNearestNeighborNetwork(int size, double p) {
        super(size);
        this.p = p;
        this.potentialEdges = new HashSet<>();
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        System.out.println("start making network");

        double r = 0.01;

        int currentSize = 2;
        
        setEdge(0, 1, 1);
        setEdge(1, 0, 1);

        while (currentSize < getSize()) {
            if (randomGenerator.network().nextDouble() < 1 - this.p) {
                // add new node and connect to a random existing node with probability 1-p
                int newNode = currentSize++;
                int v = randomGenerator.network().nextInt(newNode);
                setEdge(newNode, v, 1);

                for (int neighbor = 0; neighbor < newNode; neighbor++) {
                    if (adjacencyMatrix[v][neighbor] > 0 && neighbor != newNode) {
                        potentialEdges.add(new Edge(newNode, neighbor));
                    }
                }
            } else {
                // with probability p, convert a potential edge to an actual edge (or add a random edge)
                if (randomGenerator.network().nextDouble() < 1 - r) { // CNN with random links (CNNR)
                    // convert potential edge to actual edge
                    if (!potentialEdges.isEmpty()) {
                        List<Edge> list = new ArrayList<>(potentialEdges);

                        list.sort(Comparator.comparingInt((Edge e) -> e.from).thenComparingInt(e -> e.to));
                        Edge edge = list.get(randomGenerator.network().nextInt(list.size()));
                        setEdge(edge.from, edge.to, 1);
                        potentialEdges.remove(edge);
                    }
                } else {
                    // add link randomly
                    int a = randomGenerator.network().nextInt(currentSize);
                    int b;
                    do {
                        b = randomGenerator.network().nextInt(currentSize);
                    } while (a == b || adjacencyMatrix[a][b] > 0);

                    setEdge(a, b, 1);
                }
            }
        }
    }

    private int chooseNodeByDegree(int maxIndex) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < maxIndex; i++) {
            int degree = 0;
            for (int j = 0; j < maxIndex; j++) {
                if (adjacencyMatrix[i][j] > 0 || adjacencyMatrix[j][i] > 0) {
                    degree++;
                }
            }

            int weight = (int) Math.floor(Math.log(degree + 1));
            for (int k = 0; k < weight; k++) {
                candidates.add(i);
            }
        }

        if (candidates.isEmpty()) {
            return randomGenerator.network().nextInt(maxIndex);
        }

        return candidates.get(randomGenerator.network().nextInt(candidates.size()));
    }

}
