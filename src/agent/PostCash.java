package agent;

import rand.randomGenerator;

public class PostCash {
    private int maxNumOfPostCash;
    private Post[] postQueue;
    private int size;

    public PostCash(int maxNumOfPostCash){
        this.maxNumOfPostCash = maxNumOfPostCash;
        this.postQueue = new Post[maxNumOfPostCash];
        this.size = 0;
    }

    public void reset() {
        for (int i = 0; i < size; i++) {
            postQueue[i] = null;
        }
        size = 0;
    }

    public void shuffle() {
        for (int i = size - 1; i > 0; i--) {
            int j = randomGenerator.dynamics().nextInt(i + 1);
            Post temp = postQueue[i];
            postQueue[i] = postQueue[j];
            postQueue[j] = temp;
        }
    }
    

    public void addPost(Post post) {
        if (size < maxNumOfPostCash) {
            postQueue[size] = post;
            size++;
        } else {
            // FIFO: drop the oldest (index 0), shift left, append new at the end.
            for (int i = 1; i < maxNumOfPostCash; i++) {
                postQueue[i - 1] = postQueue[i];
            }
            postQueue[maxNumOfPostCash - 1] = post;
        }
    }

    public Post getPost(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index out of range.");
        }
        return postQueue[index];
    }

    public int getSize() {
        return size;
    }

    public Post[] getAllPosts() {
        Post[] currentPosts = new Post[size];
        System.arraycopy(postQueue, 0, currentPosts, 0, size);
        return currentPosts;
    }
}
