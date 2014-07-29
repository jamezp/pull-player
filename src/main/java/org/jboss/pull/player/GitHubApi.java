package org.jboss.pull.player;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class GitHubApi {
    static final String GITHUB_API_URL = "https://api.github.com";
    static final String GITHUB_BASE_URL = GITHUB_API_URL + "/repos/" + Util.require("github.repo");
    static final String USERNAME = Util.require("github.login");
    private static final int PAGE_LIMIT = 10000;
    private static final String LOGIN_DATA = Util.require("github.token");

    private final HttpClient httpClient = new DefaultHttpClient();
    private final boolean dryRun;

    public GitHubApi(boolean dryRun) {
        this.dryRun = dryRun;
    }

    List<Comment> getComments(int number) {
        HttpGet get = null;
        List<Comment> comments = new ArrayList<>();
        int page = 1;
        try {
            for (; page < PAGE_LIMIT; page++) {
                get = new HttpGet(GITHUB_BASE_URL + "/issues/" + number + "/comments?page=" + page);
                addDefaultHeaders(get);

                final HttpResponse execute = httpClient.execute(get);
                if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Failed to complete request to GitHub. Status: " + execute.getStatusLine());
                }

                ModelNode node = ModelNode.fromJSONStream(execute.getEntity().getContent());
                List<ModelNode> modelNodes = node.asList();
                if (modelNodes.size() == 0) {
                    break;
                }

                for (ModelNode comment : modelNodes) {
                    comments.add(new Comment(comment.get("user", "login").asString(), comment.get("body").asString().toLowerCase()));
                }
                get.releaseConnection();
                get = null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }
        if (page == PAGE_LIMIT) {
            throw new IllegalStateException("Exceeded page limit");
        }

        return comments;
    }

    List<ModelNode> getPullRequests(final int page) {
        HttpGet get = null;

        try {
            get = new HttpGet(GITHUB_BASE_URL + "/pulls?state=open&page=" + page);
            addDefaultHeaders(get);
            final HttpResponse execute = httpClient.execute(get);
            if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Failed to complete request to GitHub. Status: " + execute.getStatusLine());
            }
            ModelNode node = ModelNode.fromJSONStream(execute.getEntity().getContent());
            return node.asList();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }
        return null;

    }

    public void postComment(int number, String comment) {
        System.out.println("Posting: " + comment);
        if (this.dryRun){
            System.out.println("Dry run - Not posting to github");
            return;
        }
        final String requestUrl = GITHUB_BASE_URL + "/issues/" + number + "/comments";

        final HttpPost post = new HttpPost(requestUrl);
        try {
            post.setEntity(new StringEntity("{\"body\": \"" + comment + "\"}"));
            addDefaultHeaders(post);

            final HttpResponse execute = httpClient.execute(post);
            if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_CREATED) {
                System.out.println("Could not post comment: " + comment);
                //throw new IOException("Failed to complete request to GitHub. Status: " + execute.getStatusLine());
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            post.abort();
        }
    }


    static void addDefaultHeaders(final HttpRequest request) {
        request.addHeader(BasicScheme.authenticate(new UsernamePasswordCredentials(USERNAME, LOGIN_DATA), "UTF-8", false));
        request.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
    }

}
