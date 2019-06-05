package org.jboss.pull.player;

public enum Command {

    HELP("/help", "Display this help text."),
    OK_TO_TEST ("/ok-to-test", "Add the user to the approved list of testers and run tests."),
    RETEST_FAILED("/retest-failed", "Rerun only the failed tests.", false),
    RETEST("/retest", "Rerun all tests.");

    private boolean enabled = false;
    private final String command;
    private final String description;

    Command(final String command, final String description) {
        this(command, description, true);
    }
    Command(final String command, final String description, final boolean enabled) {
        this.command = command;
        this.description = description;
        this.enabled = enabled;
    }
    public final String getCommand() {
        return command;
    }
    public final String getDescription() {
        return description;
    }
    public final boolean enabled() {
        return enabled;
    }
}
