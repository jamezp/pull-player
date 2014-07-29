/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.pull.player;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

/**
 * Process pull requests to determine if the pull request requires a change to the labels.
 * <p/>
 * This class is not thread safe, but also shouldn't matter for now.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class LabelProcessor {
    private final Path path = Util.BASE_DIR.toPath().resolve("issues.json");
    private final Labels labels;

    private final ModelNode issuesModel;
    private final HttpClient client = new DefaultHttpClient();
    private final PrintStream err = System.err;

    LabelProcessor() {
        labels = new Labels();
        // If the file exists, load it
        if (Files.exists(path)) {
            try (final InputStream in = Files.newInputStream(path)) {
                issuesModel = ModelNode.fromJSONStream(in);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            // No file exists, create a new model
            issuesModel = new ModelNode();
        }
    }

    void process(final List<ModelNode> pulls) {
        for (ModelNode pull : pulls) {
            add(pull);
        }
        process();
    }

    /**
     * Adds a pull request to be processed.
     *
     * @param pull the pull request to be processed
     */
    void add(final ModelNode pull) {
        final int pullNumber = pull.get("number").asInt();
        final String sha1 = pull.get("head", "sha").asString();
        final String issueUrl = pull.get("issue_url").asString();

        final String key = "pr-" + pullNumber;

        if (issuesModel.hasDefined(key)) {
            final ModelNode n = issuesModel.get(key);
            if (!n.get("sha").asString().equals(sha1)) {
                // Add the new sha key to be checked during processing
                n.get("new-sha").set(sha1);
            }
        } else {
            final ModelNode model = new ModelNode();
            model.get("sha").set(sha1);
            model.get("issue_url").set(issueUrl);
            model.get("pull_request_url").set(pull.get("url"));
            issuesModel.get(key).set(model);
        }
    }

    /**
     * Processes the pull requests {@link #add(org.jboss.dmr.ModelNode) added}.
     * <p/>
     * This should normally only be invoked once as it makes API calls to GitHub.
     */
    void process() {
        try {
            // Get all the open issues to lessen the hits to the API
            final ModelNode openIssues = getIssues();

            // Process each issue in the model
            for (Property property : issuesModel.asPropertyList()) {
                final ModelNode value = property.getValue();
                // Get the PR url
                final String prUrl = value.get("pull_request_url").asString();
                if (openIssues.hasDefined(prUrl)) {
                    final ModelNode openIssue = openIssues.get(prUrl);

                    // Get the current labels
                    final List<String> currentLabels = getLabels(openIssue);
                    // If no labels are present, we can delete the issue
                    if (currentLabels.isEmpty()) {
                        issuesModel.remove(property.getName());
                    } else {
                        boolean changeRequired = false;

                        // Process the labels only requiring a change if the label was defined in the configuration
                        final List<String> newLabels = new ArrayList<>();
                        for (String label : currentLabels) {
                            if (labels.isRemovable(label)) {
                                final String newLabel = labels.getReplacement(label);
                                if (newLabel != null) {
                                    newLabels.add(newLabel);
                                }
                                changeRequired = true;
                            } else {
                                newLabels.add(label);
                            }
                        }
                        // Check that the PR has been changed and a change is required
                        if (changeRequired && value.hasDefined("new-sha")) {
                            final String issueUrl = value.get("issue_url").asString();
                            // Set the new labels
                            setLabels(issueUrl, newLabels);
                            // Node needs to be removed
                            issuesModel.remove(property.getName());
                        } else if (!changeRequired) {
                            // No change in labels has been required, remove the issue
                            issuesModel.remove(property.getName());
                        }
                    }
                } else {
                    // The issue/PR may be closed, we can just delete it
                    issuesModel.remove(property.getName());
                }
            }

            // Write the issues out to a file
            try (final PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC, StandardOpenOption.CREATE))) {
                issuesModel.writeJSONString(writer, false);
            }
        } catch (IOException e) {
            e.printStackTrace(err);
        }
    }

    private ModelNode getIssues() throws IOException {
        final ModelNode resultIssues = new ModelNode();
        try {
            // Get all the issues for the repository
            final HttpGet get = new HttpGet(GitHubApi.GITHUB_BASE_URL + "/issues");
            GitHubApi.addDefaultHeaders(get);
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
        } catch (Exception e){
            e.printStackTrace(err);
        }
        return resultIssues;
    }


    private List<String> getLabels(final ModelNode issue) {
        final List<String> result = new ArrayList<>();
        final ModelNode node = issue.get("labels");
        if (node.isDefined()) {
            final List<ModelNode> labels = node.asList();
            for (ModelNode label : labels) {
                result.add(label.get("name").asString());
            }
        }
        return result;
    }

    private void setLabels(final String issueUrl, final Collection<String> labels) {
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
            GitHubApi.addDefaultHeaders(put);
            put.setEntity(new StringEntity(sb.toString()));
            execute(put, HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            e.printStackTrace(err);
        }

    }

    private HttpResponse execute(final HttpUriRequest request, final int status) throws IOException {
        final HttpResponse response = client.execute(request);
        if (response.getStatusLine().getStatusCode() != status) {
            err.printf("Could not %s to %s %n\t%s%n", request.getMethod(), request.getURI(), response.getStatusLine());
        }
        return response;
    }
}
