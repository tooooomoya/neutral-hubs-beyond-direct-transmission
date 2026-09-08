package network;

import agent.Agent;
import java.util.*;
import rand.randomGenerator;

public class BarabasiAlbertNetwork extends Network {
    private int m; // links each new node attaches

    /**
     * @param size total number of nodes
     * @param m links each new node attaches to existing nodes (typically 2-4)
     */
    public BarabasiAlbertNetwork(int size, int m) {
        super(size);
        this.m = m;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        System.out.println("start making Barabasi-Albert network");

        // Preferential-attachment pool: each node id appears once per unit of its degree, so a
        // uniform draw from the pool selects a node with probability proportional to its degree.
        List<Integer> degreePool = new ArrayList<>();

        int n = getSize();
        
        // Seed network: a complete graph on the first m nodes, so later arrivals have targets.
        int initialNodes = m + 1;
        if (initialNodes > n) initialNodes = n;

        for (int i = 0; i < initialNodes; i++) {
            for (int j = i + 1; j < initialNodes; j++) {
                // Undirected seed: set links in both directions.
                addEdge(i, j); 
                addEdge(j, i);

                // Both endpoints gained a degree.
                degreePool.add(i);
                degreePool.add(j);
            }
        }

        // Add the remaining nodes one at a time, attaching each to existing nodes.
        for (int newNode = initialNodes; newNode < n; newNode++) {
            Set<Integer> targets = new HashSet<>();

            // Pick m distinct targets.
            while (targets.size() < this.m) {
                if (degreePool.isEmpty()) break;

                // Drawing from degreePool makes high-degree nodes more likely to be chosen.
                int candidate = degreePool.get(randomGenerator.network().nextInt(degreePool.size()));

                // Reject self-loops and multi-edges.
                if (candidate != newNode && !targets.contains(candidate)) {
                    targets.add(candidate);
                }
            }

            // Attach to the chosen targets.
            for (int target : targets) {
                addEdge(newNode, target);
                //addEdge(target, newNode);

                // Update the degree pool.
                degreePool.add(newNode);
                degreePool.add(target);
            }
        }
    }

    /**
     * Helper wrapping the superclass setEdge.
     */
    private void addEdge(int u, int v) {
        // Unweighted: weight 1.
        setEdge(u, v, 1);
    }
}