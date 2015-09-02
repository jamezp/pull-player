package org.jboss.pull.player;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.jboss.dmr.ModelNode;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class GitHubApi {
    private static final String GITHUB_API_URL = "https://api.github.com";
    private static final int PAGE_LIMIT = 10000;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final boolean dryRun;
    private final BasicCredentialsProvider credentialsProvider;

    public GitHubApi(String username, String loginData, String repository, boolean dryRun) {
        this.dryRun = dryRun;
        this.baseUrl = GITHUB_API_URL + "/repos/" + repository;
        this.httpClient = createHttpClient();
        credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, loginData));

    }

    List<Comment> getComments(int number) {
        HttpGet get = null;
        List<Comment> comments = new ArrayList<>();
        int page = 1;
        try {
            for (; page < PAGE_LIMIT; page++) {
                get = new HttpGet(baseUrl + "/issues/" + number + "/comments?page=" + page);

                final HttpResponse execute = execute(get, HttpURLConnection.HTTP_OK);

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
            final HttpResponse execute = execute(get, HttpURLConnection.HTTP_OK);
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
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        final String requestUrl = baseUrl + "/issues/" + number + "/comments";

        final HttpPost post = new HttpPost(requestUrl);
        try {
            post.setEntity(new StringEntity("{\"body\": \"" + comment + "\"}"));
            final HttpResponse execute = execute(post, HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            post.abort();
        }
    }

    HttpClient createHttpClient() {

        return HttpClients
                .custom()
                .setDefaultCredentialsProvider(credentialsProvider)
                .build();
    }
  /*  void addDefaultHeaders(final HttpRequest request) {
        BasicScheme basicScheme = new BasicScheme(StandardCharsets.UTF_8);
           request.addHeader(basicScheme.authenticate(new UsernamePasswordCredentials(username, loginData), request, new BasicHttpContext()));
           request.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
    }*/


    public ModelNode getIssues() throws IOException {
        final ModelNode resultIssues = new ModelNode();
        try {
            // Get all the issues for the repository
            final HttpGet get = new HttpGet(baseUrl + "/issues");
            final HttpResponse response = execute(get, HttpURLConnection.HTTP_OK);
            ModelNode responseResult = ModelNode.fromJSONStream(response.getEntity().getContent());
            for (ModelNode node : responseResult.asList()) {
                // We only want issues with a pull request
                if (node.hasDefined("pull_request") && node.hasDefined("labels")) {
                    // Verify the labels aren't empty
                    if (!node.get("labels").asList().isEmpty()) {
                        // The key will be the PR URL
                        resultIssues.get(node.get("pull_request", "url").asString()).set(node);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return resultIssues;
    }


    public void setLabels(final String issueUrl, final Collection<String> labels) {
        System.out.println("Setting labels for issue: " + issueUrl + ", labels: " + labels);
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        // Build a list of the new labels
        final StringBuilder sb = new StringBuilder(32).append('[');
        final int size = labels.size();
        int counter = 0;
        for (String label : labels) {
            sb.append('"').append(label).append('"');
            if (++counter < size) {
                sb.append(',');
            }
        }
        sb.append(']');
        try {
            final HttpPut put = new HttpPut(issueUrl + "/labels?state=open");
            put.setEntity(new StringEntity(sb.toString()));
            execute(put, HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

    }

    private HttpResponse execute(final HttpUriRequest request, final int status) throws IOException {

        AuthCache authCache = new BasicAuthCache();
        authCache.put(HttpHost.create(GITHUB_API_URL), new BasicScheme());
        // Add AuthCache to the execution context
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credentialsProvider);
        context.setAuthCache(authCache);
        request.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
        final HttpResponse response = httpClient.execute(request, context);
        if (response.getStatusLine().getStatusCode() != status) {
            System.err.printf("Could not %s to %s %n\t%s%n", request.getMethod(), request.getURI(), response.getStatusLine());
        }
        return response;
    }

}
