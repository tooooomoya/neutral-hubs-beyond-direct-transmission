package network;

import agent.Agent;
import gephi.GephiReader;

public class ReadNetwork extends Network {
    private String filePath;

    // Constructor
    public ReadNetwork(int size, String filePath) {
        super(size);
        this.filePath = filePath;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        int size = getSize();
        double[][] W = GephiReader.readGraphEdges(size, this.filePath);
        for(int i = 0; i < size; i++){
            for(int j = 0; j < size ; j ++){
                if(W[i][j] > 0.0){
                    setEdge(i, j, 1.0);
                }
            }
        }
    }    
}
