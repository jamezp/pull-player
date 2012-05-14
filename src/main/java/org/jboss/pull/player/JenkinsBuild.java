/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.Set;

import org.jboss.dmr.ModelNode;

/**
 * @author Jason T. Greene
 */
public class JenkinsBuild {
    private String status;
    private int build;

    private JenkinsBuild(int build, String status) {
        this.build = build;
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public int getBuild() {
        return build;
    }

    private static JenkinsBuild findBuild(int build, String base, String job,  Map<String, String> params) {
       InputStream stream = null;
        try {
            URL url = new URL(base + "/job/" + job + "/" + build + "/api/json");
            URLConnection urlConnection = url.openConnection();
            stream = urlConnection.getInputStream();
            ModelNode node = ModelNode.fromJSONStream(stream);
            ModelNode parameters = node.get("actions").get(0).get("parameters");
            int matches = 0;
            for (ModelNode parameter : parameters.asList()) {
                String value = params.get(parameter.get("name").asString());
                if (value != null && value.equals(parameter.get("value").asString())) {
                    if (++matches >= params.size()) {
                        String status = node.hasDefined("result") ? node.get("result").asString() : null;
                        return new JenkinsBuild(build, status);
                    }
                }
            }
        } catch (Exception e) {
            System.err.printf("Could not process build: %d on job: %s: %s", build, job, e.getMessage());
        } finally {
            Util.safeClose(stream);
        }

        return null;
    }

     public static JenkinsBuild findBuild(String base, String job, Map<String, String> params) {
         InputStream stream = null;
         try {
             URL url = new URL(base + "/job/" + job + "/api/json");
             URLConnection urlConnection = url.openConnection();
             stream = urlConnection.getInputStream();

             ModelNode node = ModelNode.fromJSONStream(stream);
             ModelNode builds = node.get("builds");
             for (ModelNode buildNode : builds.asList()) {
                 int buildNum = buildNode.get("number").asInt();
                 JenkinsBuild build  = findBuild(buildNum, base, job, params);
                 if (build != null)
                     return build;
             }
        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain build list", e);
        } finally {
            Util.safeClose(stream);
        }

        return null;
    }

    public static boolean isPending(String base, String job, Map<String, String> params) {
        InputStream stream = null;
        try {
            URL url = new URL(base + "/queue/api/json");
            stream = url.openStream();
            ModelNode node = ModelNode.fromJSONStream(stream);
            ModelNode builds = node.get("items");
            for (ModelNode buildNode : builds.asList()) {
                if (job.equals(buildNode.get("task", "name").asString())) {
                    ModelNode parameters = buildNode.get("actions").get(0).get("parameters");
                    int matches = 0;
                    for (ModelNode parameter : parameters.asList()) {
                        String value = params.get(parameter.get("name").asString());
                        if (value != null && value.equals(parameter.get("value").asString())) {
                            if (++matches >= params.size()) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain pending list", e);
        } finally {
            Util.safeClose(stream);
        }

        return false;
    }

}
