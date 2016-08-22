package org.jboss.pull.player;

/**
 * @author Tomaz Cerar (c) 2016 Red Hat Inc.
 */
public class Cleaner {

    public static void main(String[] args) throws Exception {
        boolean dry = args.length == 1 && args[0].equals("--dry");
        PullPlayer player = new PullPlayer(dry);
        System.out.println("Starting at: " + PullPlayer.getTime());
        try {
            //player.cleanupComments(8852);
            player.cleanupComments();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            player.cleanup();
        }
        System.out.println("Completed at: " + PullPlayer.getTime());


    }
}
