package org.jboss.pull.player;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class PullPlayer {
    private static final Pattern okToTest = Pattern.compile(".*ok\\W+to\\W+test.*", Pattern.DOTALL);
    private static final Pattern retest = Pattern.compile(".*retest\\W+this\\W+please.*", Pattern.DOTALL);
    private static final int PAGE_LIMIT = 10000;
    private final GitHubApi gitHubApi;
    private final TeamCityApi teamCityApi;
    private final PersistentList queue = PersistentList.loadList("queue");
    String teamcityBuildType;
    private String githubLogin;

    protected PullPlayer() throws Exception {
        Properties props = Util.loadProperties();
        String teamcityHost = Util.require(props, "teamcity.host");
        String teamcityPort = Util.require(props, "teamcity.port");
        teamcityBuildType = Util.require(props, "teamcity.build.type");
        githubLogin = Util.require(props, "github.login");
        String githubToken = Util.require(props, "github.token");
        String githubRepo = Util.require(props, "github.repo");
        gitHubApi = new GitHubApi(githubLogin, githubToken, githubRepo);
        String user = Util.require(props, "teamcity.user");
        String password = Util.require(props, "teamcity.password");
        teamCityApi = new TeamCityApi("http://" + teamcityHost + ":" + teamcityPort + "/httpAuth", user, password, teamcityBuildType);
    }

    static String getTime() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        return dateFormat.format(date);
    }

    boolean noBuildPending(int pull, PersistentList queue) {
        return !teamCityApi.isPending(pull, queue);
    }

    void notifyBuildTriggered(String sha1, String branch, int pull) {
        String comment = "Triggering build using a merge of " + sha1;
        //comment += COMMENT_PRIVATE_LINK + "\\n";
        System.out.println("not sending build triggered mail");
        //gitHubApi.postComment(pull, comment);
    }

    private void processPulls(PersistentList whiteList, PersistentList adminList, List<ModelNode> nodes) {
        for (ModelNode pull : nodes) {
            int pullNumber = pull.get("number").asInt();

            String user = pull.get("user", "login").asString();
            String sha1 = pull.get("head", "sha").asString();
            String branch = pull.get("base", "ref").asString();

            if (sha1 == null) {
                System.err.println("Could not get sha1 for pull: " + pullNumber);
                continue;
            }

            System.out.printf("number: %d login: %s sha1: %s\n", pullNumber, user, sha1);
            String job = Jobs.getCompletedJob(sha1);

            boolean retrigger = false;
            boolean whitelistNotify = true;
            boolean running = false;
            for (Comment comment : gitHubApi.getComments(pullNumber)) {
                if (githubLogin.equals(comment.user) && comment.comment.contains("triggering")) {
                    retrigger = false;
                    running = false;
                    continue;
                }

                if (githubLogin.equals(comment.user) && comment.comment.contains("running")) {
                    retrigger = false;
                    running = true;
                    continue;
                }

                if (githubLogin.equals(comment.user) && comment.comment.contains("verify this patch")) {
                    whitelistNotify = false;
                    continue;
                }

                if (whiteList.has(user) && whiteList.has(comment.user) && job != null && retest.matcher(comment.comment).matches()) {
                    retrigger = true;
                    //System.out.println(" we need to retrigger");
                    continue;
                }

                if (!whiteList.has(user) && adminList.has(comment.user) && okToTest.matcher(comment.comment).matches()) {
                    whiteList.add(user);
                    retrigger = true;
                    continue;
                }
            }
            System.out.println("retrigger = " + retrigger);
            if (job == null & !verifyWhitelist(whiteList, user, pullNumber, whitelistNotify)) {
                System.out.println("User not whitelisted, user: " + user);
                continue;
            }

            if (retrigger) {
                if (!queue.contains(String.valueOf(pullNumber))) {
                    Jobs.remove(sha1);
                    teamCityApi.triggerJob(pullNumber, sha1, queue);
                } else {
                    System.out.println("Build already queued");
                }
                continue;
            }

            if (job != null) {
                queue.remove(String.valueOf(pullNumber));
                System.out.println("Already done: " + pullNumber);
                continue;
            }

            long cur = System.currentTimeMillis();
            TeamCityBuild build = teamCityApi.findBuild(pullNumber,sha1);
            System.out.println("\tTime to find build: " + (System.currentTimeMillis() - cur));
            if (build != null) {
                if (build.getStatus() != null) {
                    Jobs.storeCompletedJob(sha1, pullNumber, build.getBuild());
                    queue.remove(String.valueOf(pullNumber));
                } else {
                    System.out.println("In progress, skipping: " + pullNumber);
                }
            } else if (noBuildPending(pullNumber, queue)) {
                teamCityApi.triggerJob(pullNumber, sha1, queue);
                notifyBuildTriggered(sha1, branch, pullNumber);
            } else {
                System.out.println("Pending build, skipping: " + pullNumber);
            }
        }
        queue.saveAll();
    }

    private boolean verifyWhitelist(PersistentList whiteList, String user, int pullNumber, boolean notify) {
        if (!whiteList.has(user)) {
            System.out.printf("Skipping %s\n", user);
            if (notify) {
                gitHubApi.postComment(pullNumber, "Can one of the admins verify this patch?");
            }
            return false;
        }

        return true;
    }

    protected void checkPullRequests() {
        final PersistentList whiteList = PersistentList.loadList("white-list");
        final PersistentList adminList = PersistentList.loadList("admin-list");

        for (int page = 1; ; page++) {


            List<ModelNode> nodes = gitHubApi.getPullRequests(page);
            if (nodes.size() == 0) { break; }

            System.out.println(nodes.size());

            processPulls(whiteList, adminList, nodes);
            if (page > PAGE_LIMIT) {
                throw new IllegalStateException("Exceeded page limit");
            }
        }

    }
}
