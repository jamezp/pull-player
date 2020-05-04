/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;


/**
 * Yes, this is a very very ugly hackjob!
 *
 * @author Jason T. Greene
 * @author Tomaz Cerar
 */
public class Main {

    public static void main(String[] args) throws Exception {

        boolean dry = args.length == 1 && args[0].equals("--dry");
        PullPlayer player = new PullPlayer(dry);
        System.out.println("Starting at: " + PullPlayer.getTime());
        try {
            player.checkPullRequests();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            //System.exit(1);
        } finally {
            player.cleanup();
        }
        System.out.println("Completed at: " + PullPlayer.getTime());
    }


}
