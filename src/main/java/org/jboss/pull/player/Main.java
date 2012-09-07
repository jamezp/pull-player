/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.jboss.dmr.ModelNode;


/**
 * Yes, this is a very very ugly hackjob!
 *
 * @author Jason T. Greene
 */
public class Main {

    private static final String GITHUB_API_URL = "https://api.github.com";
    private static final Pattern okToTest = Pattern.compile(".*ok\\W+to\\W+test.*");
    private static final Pattern retest = Pattern.compile(".*retest\\W+this\\W+please.*");
    private static final int PAGE_LIMIT = 10000;

    private static String BASE_HOST;
    private static String BASE_PORT;

    private static String BASE_URL;
    private static String BASE_JOB_URL;
    private static String PUBLISH_JOB_URL;
    private static String JENKINS_JOB_TOKEN;
    private static String JENKINS_JOB_NAME;
    private static String GITHUB_REPO;
    private static String GITHUB_API_JBOSS_REPO_URL;
    private static String GITHUB_LOGIN;
    private static String GITHUB_TOKEN;
    private static String PUBLISH_PUBLIC_JOB_URL;
    private static String COMMENT_PRIVATE_LINK = "Private: " + PUBLISH_JOB_URL + "/" + JENKINS_JOB_NAME + "/";
    private static String COMMENT_PUBLIC_LINK = "Public: " + PUBLISH_JOB_URL + "/" + JENKINS_JOB_NAME + "/";


    static {
        Properties props;
        try {
            props = Util.loadProperties();

            BASE_HOST = Util.require(props, "jenkins.host");
            BASE_PORT = Util.require(props, "jenkins.port");
            PUBLISH_JOB_URL = Util.require(props, "jenkins.publish.url");
            PUBLISH_PUBLIC_JOB_URL = Util.require(props, "jenkins.publish.public.url");
            JENKINS_JOB_NAME = Util.require(props, "jenkins.job.name");
            JENKINS_JOB_TOKEN = Util.require(props, "jenkins.job.token");
            GITHUB_LOGIN = Util.require(props, "github.login");
            GITHUB_TOKEN = Util.require(props, "github.token");
            GITHUB_REPO = Util.require(props, "github.repo");
            GITHUB_API_JBOSS_REPO_URL = GITHUB_API_URL + "/repos/" + GITHUB_REPO;
            BASE_URL = "http://" + BASE_HOST + ":" + BASE_PORT + "/jenkins";
            BASE_JOB_URL = BASE_URL + "/job";
            COMMENT_PRIVATE_LINK = ":\\n Private:" + PUBLISH_JOB_URL + "/" + JENKINS_JOB_NAME + "/";
            COMMENT_PUBLIC_LINK = ":\\n Public:" + PUBLISH_PUBLIC_JOB_URL + "/" + JENKINS_JOB_NAME + "/";

        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }


    public static void main(String[] args) throws Exception {
        System.out.println("Starting at: " + getTime());
        UserList whiteList = UserList.loadUserList("white-list");
        UserList adminList = UserList.loadUserList("admin-list");

        for (int page = 1; ; page++) {
            URL url = new URL(GITHUB_API_JBOSS_REPO_URL + "/pulls?state=open&page=" + page);
            InputStream stream = url.openStream();
            ModelNode node = ModelNode.fromJSONStream(stream);
            stream.close();

            List<ModelNode> nodes = node.asList();
            if (nodes.size() == 0)
                break;

            System.out.println(nodes.size());

            processPulls(whiteList, adminList, nodes);
            if (page > PAGE_LIMIT) {
                throw new IllegalStateException("Exceeded page limit");
            }
        }

        System.out.println("Completed at: " + getTime());
    }

    private static void processPulls(UserList whiteList, UserList adminList, List<ModelNode> nodes) {
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
            for (Comment comment : getComments(pullNumber)) {
                if (GITHUB_LOGIN.equals(comment.user) && comment.comment.contains("triggering")) {
                    retrigger = false;
                    running = false;
                    continue;
                }

                if (GITHUB_LOGIN.equals(comment.user) && comment.comment.contains("running")) {
                    retrigger = false;
                    running = true;
                    continue;
                }

                if (GITHUB_LOGIN.equals(comment.user) && comment.comment.contains("verify this patch")) {
                    whitelistNotify = false;
                    continue;
                }

                if (whiteList.has(user) && whiteList.has(comment.user) && job != null && retest.matcher(comment.comment).matches()) {
                    retrigger = true;
                    continue;
                }

                if (! whiteList.has(user) && adminList.has(comment.user) && okToTest.matcher(comment.comment).matches()) {
                    whiteList.add(user);
                    continue;
                }
            }

            if (job == null & !verifyWhitelist(whiteList, user, pullNumber, whitelistNotify)) {
                continue;
            }

            if (retrigger) {
                Jobs.remove(sha1);
                triggerJob(pullNumber, sha1, branch);
                continue;
            }

            if (job != null) {
                System.out.println("Already done: " + pullNumber);

                continue;
            }

            long cur = System.currentTimeMillis();
            JenkinsBuild build = JenkinsBuild.findBuild(BASE_URL, JENKINS_JOB_NAME, Util.map("sha1", sha1, "branch", branch));
            System.out.println("\tTime to find build: " + (System.currentTimeMillis() - cur));
            if (build != null) {
                if (build.getStatus() != null) {
                    Jobs.storeCompletedJob(sha1, pullNumber, build.getBuild());
                    notifyBuildCompleted(sha1, branch, pullNumber, build.getBuild(), build.getStatus());
                } else {
                    System.out.println("In progress, skipping: " + pullNumber);
                    if (!running) {
                        notifyBuildRunning(sha1, branch, pullNumber, build.getBuild());
                    }
                }
            } else if (noBuildPending(sha1, branch)) {
                triggerJob(pullNumber, sha1, branch);
            } else {
                System.out.println("Pending build, skipping: " + pullNumber);
            }
        }
    }

    private static boolean verifyWhitelist(UserList whiteList, String user, int pullNumber, boolean notify) {
        if (! whiteList.has(user)) {
            System.out.printf("Skipping %s\n", user);
            if (notify) {
                postComment(pullNumber, "Can one of the admins verify this patch?");
            }
            return false;
        }

        return true;
    }

    private static String getTime() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        return dateFormat.format(date);
    }

    private static boolean noBuildPending(String sha1, String branch) {
        return !JenkinsBuild.isPending(BASE_URL, JENKINS_JOB_NAME, Util.map("sha1", sha1, "branch", branch));
    }

    private static void notifyBuildCompleted(String sha1, String branch, int pull, int buildNumber, String status) {
        String comment = "Build " + buildNumber + " outcome was " + status + " using a merge of " + sha1 + " on branch " + branch + ":\\n";
        comment += COMMENT_PRIVATE_LINK + buildNumber + "\\n";
        comment += COMMENT_PUBLIC_LINK + buildNumber + "\\n";

        postComment(pull, comment);
        postStatus(buildNumber, convertJenkinsStatus(status), sha1);
    }

    private static String convertJenkinsStatus(String status) {
        if (status.equalsIgnoreCase("UNSTABLE") || status.equalsIgnoreCase("FAILURE")) {
            return "failure";
        } else if (status.equalsIgnoreCase("SUCCESS")) {
            return "success";
        }

        return "error";
    }

    private static void notifyBuildRunning(String sha1, String branch,  int pull, int buildNumber) {
        String comment = "Build " + buildNumber + " is now running using a merge of " + sha1 + " on branch " + branch + ":\\n";
        comment += COMMENT_PRIVATE_LINK + buildNumber + "\\n";

        postComment(pull, comment);
        postStatus(buildNumber, "pending", sha1);
    }

    private static void notifyBuildTriggered(String sha1, String branch, int pull) {
        String comment = "Triggering build using a merge of " + sha1 + " on branch " + branch +  ":\\n";;
        comment += COMMENT_PRIVATE_LINK +"\\n";

        postComment(pull, comment);
    }

    private static void triggerJob(int pull, String sha1, String branch) {
        notifyBuildTriggered(sha1, branch, pull);
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(BASE_JOB_URL + "/" + JENKINS_JOB_NAME + "/buildWithParameters?token=" + JENKINS_JOB_TOKEN + "&pull=" + pull +"&sha1=" + sha1 + "&branch=" + branch);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.connect();
            if (urlConnection.getResponseCode() != 200) {
                System.err.printf("Problem triggering build for pull: %d sha1: %s\n", pull, sha1);
            }

        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                if (urlConnection != null)
                    urlConnection.disconnect();
            } catch (Throwable t) {
            }
        }
    }

    private static class Comment {
        final String user;
        final String comment;

        Comment(String user, String comment) {
            this.user = user;
            this.comment = comment;
        }
    }

    private static List<Comment> getComments(int number) {
        List<Comment> comments = new ArrayList<Comment>();
        HttpURLConnection urlConnection = null;
        int page = 1;
        try {
            for (; page < PAGE_LIMIT; page++) {
                URL url = new URL(GITHUB_API_JBOSS_REPO_URL + "/issues/" + number + "/comments?page=" + page);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
                urlConnection.connect();
                ModelNode node = ModelNode.fromJSONStream(urlConnection.getInputStream());
                List<ModelNode> modelNodes = node.asList();
                if (modelNodes.size() == 0) {
                    break;
                }

                for (ModelNode comment : modelNodes) {
                    comments.add(new Comment(comment.get("user", "login").asString(), comment.get("body").asString().toLowerCase()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        if (page == PAGE_LIMIT) {
            throw new IllegalStateException("Exceeded page limit");
        }

        return comments;
    }

    private static void postStatus(int number, String status, String sha) {
        System.out.println("Setting status: " + status + " on sha " + sha);
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(GITHUB_API_JBOSS_REPO_URL + "/statuses/" + sha);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
            urlConnection.setDoOutput(true);
            urlConnection.connect();
            PrintWriter printWriter = new PrintWriter(urlConnection.getOutputStream());
            printWriter.println("{\"state\": \"" + status + "\", \"target_url\": \"" + PUBLISH_PUBLIC_JOB_URL + "/" + JENKINS_JOB_NAME + "/" + number + "\"}");
            printWriter.close();
            if (urlConnection.getResponseCode() < 200 || urlConnection.getResponseCode() > 299) {
                System.err.printf("Problem [%d] posting a status build for sha: %s\n", urlConnection.getResponseCode(), sha);
                Util.dumpInputStream(urlConnection.getErrorStream());
            }

        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                if (urlConnection != null)
                    urlConnection.disconnect();
            } catch (Throwable t) {
            }
        }
    }

    private static void postComment(int number, String comment) {
        System.out.println("Posting: " + comment);
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(GITHUB_API_JBOSS_REPO_URL + "/issues/" + number + "/comments");
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
            urlConnection.setDoOutput(true);
            urlConnection.connect();
            PrintWriter printWriter = new PrintWriter(urlConnection.getOutputStream());
            printWriter.println("{\"body\": \"" + comment + "\"}");
            printWriter.close();
            if (urlConnection.getResponseCode() < 200 || urlConnection.getResponseCode() > 299) {
                System.err.printf("Problem [%d] posting a comment build for pull: %d\n", urlConnection.getResponseCode(), number);
                Util.dumpInputStream(urlConnection.getErrorStream());
            }

        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                if (urlConnection != null)
                    urlConnection.disconnect();
            } catch (Throwable t) {
            }
        }
    }
}
