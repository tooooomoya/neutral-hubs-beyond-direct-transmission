package network;

import agent.Agent;
import rand.randomGenerator;

public class WattsStrogatzNetwork extends Network {
    private int K; // out-degree of each node (must be even)
    private double beta; // rewiring probability, 0 <= beta <= 1

    /**
     * @param size number of nodes
     * @param K out-degree of each node; K=4 gives directed edges to the 4 nearest nodes clockwise
     * @param beta rewiring probability; 0 leaves a regular lattice, 1 approaches a random graph
     */
    public WattsStrogatzNetwork(int size, int K, double beta) {
        super(size);
        this.K = K;
        this.beta = beta;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        System.out.println("start making Watts-Strogatz network");

        int n = getSize();
        int halfK = this.K / 2;

        // Step 1: build the directed ring lattice, giving each node i directed edges to the
        // halfK nodes clockwise from it.
        for (int i = 0; i < n; i++) {
            for (int j = 1; j <= halfK; j++) {
                int neighbor = (i + j) % n;
                setEdge(i, neighbor, 1.0);
            }
        }

        // Step 2: rewire. Each directed edge (i -> originalNeighbor) has its head moved with
        // probability beta.
        for (int i = 0; i < n; i++) {
            for (int j = 1; j <= halfK; j++) {
                if (randomGenerator.network().nextDouble() < this.beta) {
                    int originalNeighbor = (i + j) % n;

                    // Remove only the directed edge (i -> originalNeighbor).
                    removeEdge(i, originalNeighbor);

                    // Find a new target, rejecting self-loops and multi-edges.
                    int newNeighbor = -1;
                    boolean found = false;
                    int attempts = 0;
                    while (attempts < n) {
                        int candidate = randomGenerator.network().nextInt(n);
                        if (candidate != i && adjacencyMatrix[i][candidate] == 0) {
                            newNeighbor = candidate;
                            found = true;
                            break;
                        }
                        attempts++;
                    }

                    if (found) {
                        setEdge(i, newNeighbor, 1.0);
                    } else {
                        // No target found: restore the original edge.
                        setEdge(i, originalNeighbor, 1.0);
                    }
                }
            }
        }
    }
}