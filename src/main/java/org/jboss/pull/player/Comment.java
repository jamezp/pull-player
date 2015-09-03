package org.jboss.pull.player;

import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
class Comment {
    //private static final SimpleDateFormat DATE_FORMAT = SimpleDateFormat.
    final String user;
    final String comment;
    final Instant created;

    Comment(String user, String comment, String createdAt) {
        this.user = user;
        this.comment = comment;
        this.created = ZonedDateTime.parse(createdAt).toInstant();
    }
}
