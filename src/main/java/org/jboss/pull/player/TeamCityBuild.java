/**
 * Internal Use Only
 * <p>
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author Tomaz Cerar
 */
public class TeamCityBuild {

    private final String status;
    private final int build;
    private final boolean running;
    private final Instant queuedDate;

    TeamCityBuild(int build, String status, boolean running, String queuedDate) {
        this.build = build;
        this.status = status;
        this.running = running;
        this.queuedDate = ZonedDateTime.parse(queuedDate, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssZ")).toInstant();
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

    public Instant getQueuedDate() {
        return queuedDate;
    }
}
