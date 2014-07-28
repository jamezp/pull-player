package org.jboss.pull.player;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.LinkedList;
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
public class TeamCityApi {
    private final HttpClient httpClient = new DefaultHttpClient();
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String buildTypeId;
    private final boolean dryRun;

    public TeamCityApi(String baseUrl, String username, String password, String buildTypeId, boolean dryRun) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.buildTypeId = buildTypeId;
        this.dryRun = dryRun;
    }

    List<Integer> getQueuedBuilds() {
        List<Integer> result = new LinkedList<>();
        HttpGet get = null;
        try {
            //get = new HttpGet(baseUrl + "/app/rest/builds?locator=buildType:" + buildTypeId + ",branch:name:pull/" + pull + ",running:any,count:1");
            get = new HttpGet(baseUrl + "/app/rest/buildQueue?locator=buildType:" + buildTypeId);
            includeAuthentication(get);
            get.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
            get.addHeader("Accept", "application/json");
            final HttpResponse execute = httpClient.execute(get);
            if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Could not queued builds");
            }

            ModelNode node = ModelNode.fromJSONStream(execute.getEntity().getContent());
            ModelNode builds = node.get("build");
            if (!builds.isDefined() || builds.asList().isEmpty()) {
                return result;
            } else {
                for (ModelNode build : builds.asList()) {
                    if (!build.hasDefined("branchName")) {
                        continue;
                    }
                    String branch = build.get("branchName").asString();
                    int pull = Integer.parseInt(branch.substring(branch.indexOf("/") + 1));
                    result.add(pull);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain build list", e);
        } finally {
            if (get != null) {
                get.releaseConnection();
            }

        }
        return result;
    }

    public TeamCityBuild findBuild(int pull, String hash) {
        HttpGet get = null;
        try {
            get = new HttpGet(baseUrl + "/app/rest/builds?locator=buildType:" + buildTypeId + ",branch:name:pull/" + pull + ",running:any,count:1");
            includeAuthentication(get);
            get.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
            get.addHeader("Accept", "application/json");
            final HttpResponse execute = httpClient.execute(get);
            if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Could not find build, for pull: %s\n", pull);
            }

            ModelNode node = ModelNode.fromJSONStream(execute.getEntity().getContent());
            ModelNode builds = node.get("build");
            if (!builds.isDefined() || builds.asList().isEmpty()) {
                return null;
            } else {
                ModelNode buildNode = builds.asList().get(0);
                String buildId = buildNode.get("id").asString();
                return getBuildById(buildId, hash);
            }


        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain build list", e);
        } finally {
            if (get != null) {
                get.releaseConnection();
            }

        }
    }

    private TeamCityBuild getBuildById(String id, String hash) {
        HttpGet get = null;
        try {
            get = new HttpGet(baseUrl + "/app/rest/builds/id:" + id);
            includeAuthentication(get);
            get.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
            get.addHeader("Accept", "application/json");
            final HttpResponse execute = httpClient.execute(get);
            if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Could not find build, for id: %s\n", id);
            }

            ModelNode build = ModelNode.fromJSONStream(execute.getEntity().getContent());
            boolean found = false;
            for (ModelNode prop : build.get("properties", "property").asList()) {
                if ("hash".equals(prop.get("name").asString())) {
                    String value = prop.get("value").asString();
                    found = hash.equals(value);
                    break;
                }
            }
            System.out.println("Hash for last build matches: " + found);
            if (found) {
                return new TeamCityBuild(build.get("number").asInt(), build.get("status").asString(), build.hasDefined("running") && build.get("running").asBoolean());
            } else {
                return null;
            }


        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain build list", e);
        } finally {
            if (get != null) {
                get.releaseConnection();
            }

        }
    }

    void triggerJob(int pull, String sha1) {
        System.out.println("triggering job for pull = " + pull);
        if (dryRun) {
            System.out.println("DryRun, not triggering");
            return;
        }
        HttpPost post = null;
        try {
            post = new HttpPost(baseUrl + "/app/rest/buildQueue");
            includeAuthentication(post);
            post.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
            post.setHeader(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"));
            ModelNode build = new ModelNode();
            build.get("branchName").set("pull/" + pull);
            ModelNode buildType = build.get("buildType");
            buildType.get("id").set(buildTypeId);
            ModelNode props = build.get("properties", "property");
            ModelNode prop = props.add();
            prop.get("name").set("hash");
            prop.get("value").set(sha1);
            props.add(prop);
            prop = props.add();
            prop.get("name").set("pull");
            prop.get("value").set(pull);

            post.setEntity(new StringEntity(build.toJSONString(false)));
            final HttpResponse execute = httpClient.execute(post);
            if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Problem triggering build for pull: %d sha1: %s\nResponse: %s \n", pull, sha1, execute.getStatusLine().toString());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (post != null) {
                post.releaseConnection();
            }

        }
    }

    private void includeAuthentication(HttpRequest request) throws IOException {
        request.addHeader(BasicScheme.authenticate(new UsernamePasswordCredentials(username, password), "UTF-8", false));
    }
}
