package network;

import agent.Agent;
import rand.randomGenerator;

public class RandomNetwork extends Network {
    private double connectionProbability;

    // Constructor
    public RandomNetwork(int size, double connectionProbability) {
        super(size);
        this.connectionProbability = connectionProbability;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        int size = getSize();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i != j && randomGenerator.network().nextDouble() < connectionProbability) {
                    setEdge(i, j, 1.0);
                }
            }
        }
    }
}
