package network;

import agent.Agent;

public abstract class Network {
    double[][] adjacencyMatrix;
    private int size;

    // Constructor
    public Network(int size) {
        this.size = size;
        this.adjacencyMatrix = new double[size][size];
    }

    // getter methods

    public double[][] getAdjacencyMatrix() {
        double[][] copy = new double[size][size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(this.adjacencyMatrix[i], 0, copy[i], 0, size);
        }
        return copy;
    }

    public double getEdge(int from, int to) {
        return this.adjacencyMatrix[from][to];
    }

    // protected getter
    protected int getSize() {
        return this.size;
    }

    // setter methods

    /** Set an unweighted follow edge i -> j (weight 1.0). */
    public void setEdge(int i, int j) {
        if (i >= 0 && i < size && j >= 0 && j < size) {
            adjacencyMatrix[i][j] = 1.0;
        }
    }

    /** Set a weighted edge i -> j. Unweighted generators use setEdge(i, j), which writes 1.0. */
    public void setEdge(int i, int j, double weight) {
        if (i >= 0 && i < size && j >= 0 && j < size) {
            adjacencyMatrix[i][j] = weight;
        }
    }

    public void setAdjacencyMatrix(double[][] newMatrix) {
        if (newMatrix.length != size || newMatrix[0].length != size) {
            throw new IllegalArgumentException("Adjacency matrix must be of size " + size + "x" + size);
        }
        for (int i = 0; i < size; i++) {
            System.arraycopy(newMatrix[i], 0, this.adjacencyMatrix[i], 0, size);
        }
    }

    /** Remove the edge i -> j: agent i no longer sees agent j's posts. */
    public void removeEdge(int i, int j) {
        if (i >= 0 && i < size && j >= 0 && j < size) {
            adjacencyMatrix[i][j] = 0;
        }
    }

    public double[] getNeighbors(int node) {
        if (node >= 0 && node < size) {
            return adjacencyMatrix[node].clone();
        }
        return new double[0];
    }

    // abstract methods

    public abstract void makeNetwork(Agent[] agentSet);

}