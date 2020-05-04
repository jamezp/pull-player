package org.jboss.pull.player;

import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class TeamCityApi {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Map<String, String> branchMapping = new HashMap<>();
    private final boolean dryRun;
    private final boolean disabled;

    public TeamCityApi(String host, int port, String username, String password, String branchMapping, boolean dryRun, boolean disabled) throws Exception {
        if (port == 443) {
            this.baseUrl = "https://" + host + "/httpAuth";
        } else {
            this.baseUrl = "http://" + host + ":" + port + "/httpAuth";
        }
       /* this.username = username;
        this.password = password;*/
        this.dryRun = dryRun;
        this.disabled = disabled;
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(context, new NoopHostnameVerifier());



        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(host, port),
                new UsernamePasswordCredentials(username, password));
        httpClient = HttpClients.custom()
                .setDefaultCredentialsProvider(credsProvider)
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .setSSLSocketFactory(socketFactory)
                .build();
        parseBranchMapping(branchMapping);
    }

    public TeamCityApi(String host, int port, String username, String password, String branchMapping, boolean dryRun) throws Exception {
        this(host, port, username, password, branchMapping, dryRun, false);
    }

    private void parseBranchMapping(String mappings) {
        for (String mapping : mappings.split(",")) {
            String[] parts = mapping.split("=>");
            branchMapping.put(parts[0].trim(), parts[1].trim());
        }
        System.out.println("branchMapping = " + branchMapping);
    }

    private List<Integer> getQueuedBuildsInternally(String buildTypeId) {
        List<Integer> result = new LinkedList<>();

        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties no queued build information is available.\n");
            return result;
        }
        HttpGet get = null;
        try {
            //get = new HttpGet(baseUrl + "/app/rest/builds?locator=buildType:" + buildTypeId + ",branch:name:pull/" + pull + ",running:any,count:1");
            get = new HttpGet(baseUrl + "/app/rest/buildQueue?locator=buildType:" + buildTypeId);
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
                    if (!branch.contains("pull")) { continue; }
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

    List<Integer> getQueuedBuilds() {
        List<Integer> result = new LinkedList<>();
        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties no queued build information is available.\n");
            return result;
        }
        for (String buildType : branchMapping.values()) {
            result.addAll(getQueuedBuildsInternally(buildType));
        }
        return result;
    }

    protected boolean hasBranchMapping(String branch) {
        return branchMapping.containsKey(branch);
    }

    public TeamCityBuild findBuild(int pull, String hash, String branch) {
        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties, dummy build information will be used.\n");
            return null;
            //return new TeamCityBuild(0, "disabled", false, "20160101T130000+0000");
        }

        String buildTypeId = branchMapping.get(branch);
        HttpGet get = null;
        try {
            get = new HttpGet(baseUrl + "/app/rest/builds?locator=buildType:" + buildTypeId + ",branch:name:pull/" + pull + ",running:any,canceled:any,failedToStart:any,count:1");
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
                String number = build.get("number").asString();
                final int num;
                try {//number can be N/A if it is no longer present on server
                    num = Integer.parseInt(number);
                } catch (NumberFormatException e) {
                    return null;
                }


                /*
                    "queuedDate" => "20150903T162504+0200",
                    "startDate" => "20150903T172007+0200",
                    "finishDate" => "20150903T172008+0200",
                 */

                return new TeamCityBuild(num, build.get("status").asString(),
                        build.hasDefined("running") && build.get("running").asBoolean(),
                        build.get("queuedDate").asString());
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

    void triggerJob(int pull, String sha1, String branch) {
        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties, build will not be triggered.\n");
            return;
        }
        System.out.println("triggering job for pull = " + pull);
        String buildTypeId = branchMapping.get(branch);
        if (dryRun) {
            System.out.printf("DryRun, not triggering for branch: '%s' build type id: '%s'\n", branch, buildTypeId);
            return;
        }

        HttpPost post = null;
        try {
            post = new HttpPost(baseUrl + "/app/rest/buildQueue");
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
            prop = props.add();
            prop.get("name").set("pull");
            prop.get("value").set(pull);
            prop = props.add();
            prop.get("name").set("branch");
            prop.get("value").set(branch);
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
}
