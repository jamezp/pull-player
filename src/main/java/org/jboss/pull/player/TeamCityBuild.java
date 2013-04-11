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

    private String status;
    private int build;

    TeamCityBuild(int build, String status) {
        this.build = build;
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public int getBuild() {
        return build;
    }

}
