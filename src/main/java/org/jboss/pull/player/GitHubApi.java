package org.jboss.pull.player;

import java.io.IOException;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.AuthCache;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
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
import org.jboss.dmr.Property;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class GitHubApi {
    private static final String GITHUB_API_URL = "https://api.github.com";
    private final Path cacheFileName = Util.BASE_DIR.toPath().resolve("github-api.cache");
    private final CloseableHttpClient httpClient;
    private final String baseUrl;
    private final boolean dryRun;
    private final AtomicBoolean cacheDirty = new AtomicBoolean(false);
    private final Properties cache = getCache();

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
        if (p.size() > 250) {
            System.out.println("Size of cache got too big, clearing out cache");
            p.clear();
            cacheDirty.set(true);
        }
        return p;
    }

    List<Comment> getComments(final String commentsUrl) {
        HttpGet get = null;
        List<Comment> comments = new ArrayList<>();
        try {
            String url = commentsUrl;
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
                    comments.add(create(comment));
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
        String lastCheck = cache.getProperty("LAST_CHECK", ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault()).toString());
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

                Instant last = ZonedDateTime.parse(lastCheck).toInstant();
                result.addAll(filterNonModifiedPullRequests(node.asList(), last));
            }
            updateLastCheck();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }
        return result;

    }

    /**
     * @return returns all pull requests that might need processing
     */
    private ModelNode getPullRequest(String url) {
        HttpGet get = null;
        try {
            get = new HttpGet(url);
            final HttpResponse response = execute(get);
            return ModelNode.fromJSONStream(response.getEntity().getContent());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }
        return new ModelNode().setEmptyObject();

    }

    /**
     * @return returns the details of a particular pull request
     */
    ModelNode getPullRequestDetails(final int pullRequest) {
        HttpGet get = null;
        try {
            String url = baseUrl + "/pulls/" + pullRequest;
            get = new HttpGet(url);
            final HttpResponse response = execute(get);
            url = nextLink(response);
            if (notModified(response)) {
                return null;
            }
            ModelNode node = ModelNode.fromJSONStream(response.getEntity().getContent());
                return node;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }
        return null;
    }

    void updateLastCheck() {
        cache.putIfAbsent("LAST_CHECK", ZonedDateTime.now().toString());
    }

    private List<ModelNode> filterNonModifiedPullRequests(List<ModelNode> pulls, Instant lastCheck) {
        final List<ModelNode> res = new LinkedList<>();
        for (ModelNode pull : pulls) {
            String updatedAtString = pull.get("updated_at").asString(); //get timestamp when was PR last updated.
            Instant updatedAt = ZonedDateTime.parse(updatedAtString).toInstant();
            if (updatedAt.isAfter(lastCheck)) {
                res.add(pull);
            }
        }
        return res;
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
            updateLastCheck();
            post.completed();
        } catch (Exception e) {
            e.printStackTrace(System.err);
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


    public List<ModelNode> getIssuesWithPullRequests() throws IOException {
        final List<ModelNode> pulls = new LinkedList<>();
        try {
            // Get all the issues for the repository
            String url = baseUrl + "/issues?state=open&filter=all";
            while (url != null) {
                final HttpGet get = new HttpGet(url);
                final HttpResponse response = execute(get);
                url = nextLink(response);
                if (notModified(response)) { //noting new to do
                    return Collections.emptyList();
                }
                ModelNode responseResult = ModelNode.fromJSONStream(response.getEntity().getContent());
                for (ModelNode node : responseResult.asList()) {
                    // We only want issues with a pull request
                    if (node.hasDefined("pull_request") && node.hasDefined("labels")) {
                        String prUrl = node.get("pull_request","url").asString();
                        ModelNode pr = getPullRequest(prUrl);
                        for (Property p : pr.asPropertyList()){//lets just copy everything
                            node.get(p.getName()).set(p.getValue());
                        }
                        node.remove("user");
                        node.remove("head");
                        node.remove("repo");
                        node.remove("base");
                        node.remove("_links");
                        pulls.add(node);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return pulls;
    }

    private ModelNode getLabelsForIssue(final List<ModelNode> allIssues, int number){
        Optional<ModelNode> res =  allIssues.stream()
                .filter(node -> node.get("number").asInt() == number)
                .findFirst();
        if (res.isPresent()){
            return res.get().get("labels");
        }
        return new ModelNode().setEmptyList();
    }


    public void setLabels(final String issueUrl, final Collection<String> labels) {
        System.out.println("Setting labels for issue: " + issueUrl + ", labels: " + labels);
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        // Build a list of the new labels
        final String sb = getLabelsArray(labels);
        try {
            final HttpPut put = new HttpPut(issueUrl + "/labels");
            put.setEntity(new StringEntity(sb));
            execute(put).close();

        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public void addLabels(final String issueUrl, final Collection<String> labels) {
        System.out.println("adding labels for issue: " + issueUrl + ", labels: " + labels);
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        // Build a list of the new labels
        final String sb = getLabelsArray(labels);
        try {
            final HttpPost put = new HttpPost(issueUrl + "/labels");
            put.setEntity(new StringEntity(sb));
            execute(put).close();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private String getLabelsArray(Collection<String> labels) {
        final StringBuilder sb = new StringBuilder(32).append('[').append('\n');
        final int size = labels.size();
        int counter = 0;
        for (String label : labels) {
            sb.append('"').append(label).append('"');
            if (++counter < size) {
                sb.append(',');
            }
            sb.append("\n");
        }
        sb.append(']');
        return sb.toString();
    }

    private boolean notModified(HttpResponse response) {
        return response.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_NOT_MODIFIED;

    }

    private CloseableHttpResponse execute(final HttpUriRequest request) throws IOException {

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

        final CloseableHttpResponse response = httpClient.execute(request, context);
        int responseStatus = response.getStatusLine().getStatusCode();

        //System.out.println("Next page for "+request.getURI()+": " + nextLink(response));
        if (responseStatus == HttpURLConnection.HTTP_NOT_MODIFIED) {
            System.out.println("url " + request.getURI() + " is not modified");
        } else {
            if (responseStatus != HttpURLConnection.HTTP_CREATED && responseStatus != HttpURLConnection.HTTP_OK && responseStatus != HttpURLConnection.HTTP_NO_CONTENT) {
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

    private Comment create(ModelNode comment) {
        String createdAt = comment.get("created_at").asString();
        return new Comment(comment.get("user", "login").asString(), comment.get("body").asString(), createdAt, comment.get("id").asString());
    }


    List<Comment> getCommentsForIssue(final int issueId) {
        HttpGet get = null;
        List<Comment> comments = new ArrayList<>();
        try {
            String url = baseUrl + "/issues/" + issueId + "/comments";
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
                for (ModelNode comment : modelNodes) {
                    //comments.add(new Comment(comment.get("user", "login").asString(), comment.get("body").asString(), createdAt, id));
                    comments.add(create(comment));
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

    public void deleteComment(Comment comment) {
        System.out.println(String.format("Deleting comment id: '%s' ",comment.id));
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        //final String requestUrl = baseUrl + "/issues/" + issue + "/comments/" + comment.id;
        final String requestUrl = baseUrl + "/issues/comments/" + comment.id;

        final HttpDelete delete = new HttpDelete(requestUrl);
        try {
            execute(delete);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        } finally {
            delete.abort();
        }
    }


    public List<ModelNode> getAllIssues() {
        final List<ModelNode> resultIssues = new LinkedList<>();
        try {
            // Get all the issues for the repository
            String url = baseUrl + "/issues?state=open&filter=all";
            while (url != null) {
                final HttpGet get = new HttpGet(url);
                final HttpResponse response = execute(get);
                url = nextLink(response);
                if (notModified(response)) { //noting new to do
                    return Collections.emptyList();
                }
                ModelNode responseResult = ModelNode.fromJSONStream(response.getEntity().getContent());
                resultIssues.addAll(responseResult.asList());
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return resultIssues;
    }
}
