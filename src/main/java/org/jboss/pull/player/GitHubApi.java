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
    private static final String GITHUB_API_URL = "https://api.github.com";
    private static final int PAGE_LIMIT = 10000;
    private final HttpClient httpClient = new DefaultHttpClient();
    private final String baseUrl;
    private String username;
    private String loginData;

    public GitHubApi(String username, String loginData, String repository) {
        this.username = username;
        this.loginData = loginData;
        this.baseUrl = GITHUB_API_URL + "/repos/" + repository;
    }

    List<Comment> getComments(int number) {
        HttpGet get = null;
        List<Comment> comments = new ArrayList<>();
        int page = 1;
        try {
            for (; page < PAGE_LIMIT; page++) {
                get = new HttpGet(baseUrl + "/issues/" + number + "/comments?page=" + page);
                includeAuthentication(get);
                get.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));

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
            get = new HttpGet(baseUrl + "/pulls?state=open&page=" + page);
            includeAuthentication(get);
            get.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
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

    private void includeAuthentication(HttpRequest request) throws IOException {
        request.addHeader(BasicScheme.authenticate(new UsernamePasswordCredentials(username, loginData), "UTF-8", false));
    }

    public void postComment(int number, String comment) {
        System.out.println("Posting: " + comment);
        final String requestUrl = baseUrl + "/issues/" + number + "/comments";

        final HttpPost post = new HttpPost(requestUrl);
        try {
            post.setEntity(new StringEntity("{\"body\": \"" + comment + "\"}"));
            includeAuthentication(post);
            post.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));

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


}
