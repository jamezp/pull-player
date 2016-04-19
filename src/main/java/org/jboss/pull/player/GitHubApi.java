package org.jboss.pull.player;

import java.io.IOException;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.AuthCache;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class GitHubApi {
    private static final String GITHUB_API_URL = "https://api.github.com";
    private final Path cacheFileName = Util.BASE_DIR.toPath().resolve("github-api.cache");
    private final CloseableHttpClient httpClient;
    private final String baseUrl;
    private final boolean dryRun;
    private final Properties cache = getCache();
    private final AtomicBoolean cacheDirty = new AtomicBoolean(false);

    public GitHubApi(String authToken, String repository, boolean dryRun) {
        this.dryRun = dryRun;
        this.baseUrl = GITHUB_API_URL + "/repos/" + repository;
        this.httpClient = createHttpClient(authToken);
    }

    private Properties getCache() {
        Properties p = new Properties();
        if (Files.exists(cacheFileName)) {
            try (Reader r = Files.newBufferedReader(cacheFileName, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.out.println("could not load cache");
                e.printStackTrace(System.out);
            }
        }
        if (p.size()> 200){
            System.out.println("Size of cache got too big, clearing out cache");
            p.clear();
            cacheDirty.set(true);
        }
        return p;
    }

    List<Comment> getComments(int number) {
        HttpGet get = null;
        List<Comment> comments = new ArrayList<>();
        int page = 1;
        try {
            String url = baseUrl + "/issues/" + number + "/comments?page=" + page;
            while (url != null) {
                get = new HttpGet(url);
                final HttpResponse response = execute(get);
                url = nextLink(response);
                if (notModified(response)) {
                    return null;
                }
                ModelNode node = ModelNode.fromJSONStream(response.getEntity().getContent());
                List<ModelNode> modelNodes = node.asList();
                if (modelNodes.size() == 0) {
                    continue;
                }
                /*
                   "created_at" => "2015-09-01T15:35:01Z",
                    "updated_at" => "2015-09-01T15:35:01Z",
                 */
                for (ModelNode comment : modelNodes) {
                    String createdAt = comment.get("created_at").asString();
                    comments.add(new Comment(comment.get("user", "login").asString(), comment.get("body").asString(), createdAt));
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

        return comments;
    }

    /**
     * @return returns all pull requests that might need processing
     */
    List<ModelNode> getPullRequests() {
        HttpGet get = null;
        List<ModelNode> result = new ArrayList<>();
        try {
            String url = baseUrl + "/pulls?state=open";
            while (url != null) {
                get = new HttpGet(url);
                final HttpResponse response = execute(get);
                url = nextLink(response);
                if (notModified(response)) {
                    continue;
                }
                ModelNode node = ModelNode.fromJSONStream(response.getEntity().getContent());
                result.addAll(node.asList());
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }
        return result;

    }

    private String nextLink(HttpResponse response) {
        Header header = response.getFirstHeader("Link");
        if (header == null) { // no pagination
            return null;
        }
        for (HeaderElement el : header.getElements()) {
            if ("next".equals(el.getParameterByName("rel").getValue())) {
                String link = el.getName() + "=" + el.getValue();
                return link.substring(1, link.length() - 1);
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
            execute(post);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            post.abort();
        }
    }

    CloseableHttpClient createHttpClient(String authToken) {

        return HttpClients
                .custom()
                .setDefaultHeaders(Arrays.asList(new BasicHeader("Authorization", "token " + authToken),
                        new BasicHeader("User-Agent", "WildFly-Pull-Player")))
                .build();
    }


    public ModelNode getIssuesWithPullRequests() throws IOException {
        final ModelNode resultIssues = new ModelNode();
        try {
            // Get all the issues for the repository
            String url = baseUrl + "/issues?state=open&filter=all";
            while (url != null) {
                final HttpGet get = new HttpGet(url);
                final HttpResponse response = execute(get);
                url = nextLink(response);
                if (notModified(response)) { //noting new to do
                    return new ModelNode();
                }
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
            final HttpPut put = new HttpPut(issueUrl + "/labels");
            put.setEntity(new StringEntity(sb.toString()));
            execute(put);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

    }

    private boolean notModified(HttpResponse response) {
        return response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_NOT_MODIFIED;

    }

    private HttpResponse execute(final HttpUriRequest request) throws IOException {

        AuthCache authCache = new BasicAuthCache();
        authCache.put(HttpHost.create(GITHUB_API_URL), new BasicScheme());
        // Add AuthCache to the execution context
        final HttpClientContext context = HttpClientContext.create();
        request.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));

        String cacheKey = request.getURI().toString();
        String value = cache.getProperty(cacheKey);
        if (request.getMethod() == HttpGet.METHOD_NAME && value != null) {
            request.addHeader("If-None-Match", value);
        }

        final HttpResponse response = httpClient.execute(request, context);
        int responseStatus = response.getStatusLine().getStatusCode();

        //System.out.println("Next page for "+request.getURI()+": " + nextLink(response));
        if (responseStatus == HttpURLConnection.HTTP_NOT_MODIFIED) {
            System.out.println("url " + request.getURI() + " is not modified");
        } else {
            if (responseStatus != HttpURLConnection.HTTP_OK) {
                System.err.printf("Could not %s to %s %n\t%s%n", request.getMethod(), request.getURI(), response.getStatusLine());
            }
            if (request.getMethod() == HttpGet.METHOD_NAME) {
                String eTag = response.getFirstHeader("ETag").getValue();
                if (eTag == null) {
                    System.out.println("ETag is not defined for uri: " + request.getURI());
                } else {
                    cache.put(cacheKey, eTag);
                    cacheDirty.compareAndSet(false, true);
                }
            }
        }

        String remaining = response.getFirstHeader("X-RateLimit-Remaining").getValue();
        System.out.println("X-RateLimit-Remaining: " + remaining);

        /*for (Header h : response.getAllHeaders()){
            System.out.println(String.format("Header: %s",h));
        }*/
        return response;
    }

    public void close() throws IOException {
        if (cacheDirty.get()) {
            cache.store(Files.newBufferedWriter(cacheFileName, StandardCharsets.UTF_8, StandardOpenOption.CREATE), null);
        }
        httpClient.close();
    }

}
