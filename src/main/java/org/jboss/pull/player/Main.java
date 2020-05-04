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
        // quick hack to dump gh data
        boolean dumpPr = args.length == 2 && args[0].equals("--dump");
        PullPlayer player = new PullPlayer(dry);
        System.out.println("Starting at: " + PullPlayer.getTime());
        if (dumpPr) {
            String pr = args[1];
            if (pr == null || "".equals(pr)) {
                System.out.println("PR number is required for --dump <pr_number>");
                return;
            }
            int pri = Integer.parseInt(pr);
            player.dumpPullRequestData(pri);
            return;
        }
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
