package analysis;

import admin.*;
import agent.Agent;
import network.*;

public class AssertionCheck {
    private Agent[] agentSet;
    private Network network;
    private int n;
    private int[] numOfError;
    private int maxStep;

    public AssertionCheck(Agent[] agentSet, Network network, int agentNum, int maxStep) {
        this.agentSet = agentSet;
        this.network = network;
        this.n = agentNum;
        this.numOfError = new int[maxStep + 1];
        this.maxStep = maxStep;
    }

    public void assertionChecker(Agent[] agentSet, AdminOptim admin, int agentNum, int step) {
        // Run-time invariants. The follow graph has a single owner (AdminOptim), so there is no
        // second copy that could desynchronize from it.

        // A follow edge must never coexist with a block of the same target (unfollow blocks + drops).
        for (int i = 0; i < n; i++) {
            boolean[] blocked = agentSet[i].getBlockedUserList();
            for (int j : admin.getFollowees(i)) {
                if (blocked[j]) {
                    System.out.println("AC Error: agent " + i + " follows blocked user " + j);
                    numOfError[step]++;
                }
            }
        }

        // opinion should be in [-1, 1].
        for (int i = 0; i < n; i++) {
            double o = agentSet[i].getOpinion();
            if (o < -1 || o > 1) {
                System.out.println("AC Error: opinion is out of the range in user " + i);
                numOfError[step]++;
            }
        }
    }

    public void reportASError(){
        int sumError = 0;
        for(int i = 0; i < maxStep ; i++){
                sumError += numOfError[i];
        }
        System.out.println("the sum of error reported : " + sumError);
    }
}