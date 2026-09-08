package network;

import agent.Agent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class CustomNetwork extends Network {
    private String edgesFilePath;

    public CustomNetwork(int size, String edgesFilePath) {
        super(size);
        this.edgesFilePath = edgesFilePath;
    }

    @Override
    public void makeNetwork(Agent[] agentSet) {
        try (BufferedReader br = new BufferedReader(new FileReader(edgesFilePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 2) {
                    try {
                        int u = Integer.parseInt(parts[0].trim()) - 1;
                        int v = Integer.parseInt(parts[1].trim()) - 1;

                        if (u >= 0 && u < getSize() && v >= 0 && v < getSize() && u != v) {
                            setEdge(u, v, 1);
                        }

                    } catch (NumberFormatException e) {
                        System.err.println("Number format error in line: " + line);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading edge file: " + e.getMessage());
        }

        double[][] tempMatrix = getAdjacencyMatrix();
        int size = getSize();

        // Row normalization.
        for (int i = 0; i < size; i++) {
            double rowSum = 0.0;
            for (int j = 0; j < size; j++) {
                rowSum += tempMatrix[i][j];
            }
            if (rowSum > 0) {
                for (int j = 0; j < size; j++) {
                    setEdge(i, j, tempMatrix[i][j] / rowSum);
                }
            }
        }
    }
}
