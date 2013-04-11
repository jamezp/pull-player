package org.jboss.pull.player;

/**
* @author Tomaz Cerar (c) 2013 Red Hat Inc.
*/
class Comment {
    final String user;
    final String comment;

    Comment(String user, String comment) {
        this.user = user;
        this.comment = comment;
    }
}
