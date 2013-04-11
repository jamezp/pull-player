package org.jboss.pull.player;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Map;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class TeamCityApi {
    //private static Pattern BRANCH_EXTRACTOR = Pattern.compile("<span class=\"branchName\">pull/(.*?)</span>", Pattern.CASE_INSENSITIVE);
    private final HttpClient httpClient = new DefaultHttpClient();
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String buildTypeId;

    public TeamCityApi(String baseUrl, String username, String password, String buildTypeId) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.buildTypeId = buildTypeId;
    }

    boolean isPending(int pull, PersistentList queue) {
        return queue.contains(String.valueOf(pull));
    }

    private TeamCityBuild findBuild(ModelNode node, Map<String, String> params) {
        int buildNum = node.get("number").asInt();
        ModelNode parameters = node.get("actions").get(0).get("parameters");
        int matches = 0;
        for (ModelNode parameter : parameters.asList()) {
            String value = params.get(parameter.get("name").asString());
            if (value != null && value.equals(parameter.get("value").asString())) {
                if (++matches >= params.size()) {
                    String status = node.hasDefined("result") ? node.get("result").asString() : null;
                    return new TeamCityBuild(buildNum, status);
                }
            }
        }
        return null;
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
            if (builds.asList().isEmpty()) {
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
                return new TeamCityBuild(build.get("number").asInt(), build.get("status").asString());
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

    void triggerJob(int pull, String sha1, PersistentList queue) {
        System.out.println("triggering job for pull = " + pull);
        HttpGet get = null;
        try {
            get = new HttpGet(baseUrl + "/action.html?add2Queue=" + buildTypeId + "&branchName=pull/" + pull + "&name=pull&value=" + pull + "&name=hash&value=" + sha1);
            includeAuthentication(get);
            get.setHeader(new BasicHeader(HttpHeaders.ACCEPT_ENCODING, "UTF-8"));
            final HttpResponse execute = httpClient.execute(get);
            if (execute.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Problem triggering build for pull: %d sha1: %s\n", pull, sha1);
            }
            queue.add(String.valueOf(pull));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (get != null) {
                get.releaseConnection();
            }

        }
    }

    private void includeAuthentication(HttpRequest request) throws IOException {
        request.addHeader(BasicScheme.authenticate(new UsernamePasswordCredentials(username, password), "UTF-8", false));
    }
}
