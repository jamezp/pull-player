/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

/**
 * @author Tomaz Cerar
 */
public class TeamCityBuild {

    private final String status;
    private final int build;
    private final boolean running;

    TeamCityBuild(int build, String status, boolean running) {
        this.build = build;
        this.status = status;
        this.running = running;
    }

    public String getStatus() {
        return status;
    }

    public int getBuild() {
        return build;
    }

    public boolean isRunning() {
        return running;
    }
}
