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

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.jboss.dmr.ModelNode;

/**
 * Process pull requests to determine if the pull request requires a change to the labels.
 * <p/>
 * This class is not thread safe, but also shouldn't matter for now.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @author Tomaz CErar
 */
public class LabelProcessor {
    private final Path path = Util.BASE_DIR.toPath().resolve("issues.json");
    private final Labels labels;

    private List<ModelNode> pulls;
    private final PrintStream err = System.err;
    private final GitHubApi api;

    LabelProcessor(GitHubApi api) {
        this.api = api;
        labels = new Labels();
       /* // If the file exists, load it
        if (Files.exists(path)) {
            try (final InputStream in = Files.newInputStream(path)) {
                pulls = ModelNode.fromJSONStream(in);
            } catch (IOException e) {
                e.printStackTrace();
                pulls = new ModelNode();
                pulls.setEmptyObject();

            }
        } else {
            // No file exists, create a new model
            pulls = new ModelNode();
        }*/
    }

    void process(final List<ModelNode> pulls) {
        final String rebaseThisLabel = this.labels.getRebaseThis();
        final String fixMeLabel = this.labels.getFixMe();
        for (ModelNode pull : pulls) {
            int number = pull.get("number").asInt();
            if (!pull.hasDefined("mergeable")) {
                System.out.println("missing mergable meta data for " + number);
                continue;
            }
            boolean mergeable = pull.get("mergeable").asBoolean();
            String mergeableState = pull.get("mergeable_state").asString();
            boolean cleanBuild = "clean".equals(mergeableState);
            List<String> labels = getLabels(pull);
            List<String> newLabels = new LinkedList<>();
            List<String> removedLabels = new LinkedList<>();
            System.out.println(String.format("pull=%s, mergable=%s, clean=%s, lables=%s", number, mergeable, cleanBuild, labels));

            if (mergeable) {
                if (labels.contains(rebaseThisLabel)) {
                    removedLabels.add(rebaseThisLabel);
                    System.out.println("rebased, remove label "+ number);
                }
            } else {
                if (!labels.contains(rebaseThisLabel)) {
                    newLabels.add(rebaseThisLabel);
                    System.out.println("we need to rebase "+ number);
                }
            }
            if (cleanBuild && labels.contains(fixMeLabel)){ //we have clean test of the PR
                removedLabels.add(fixMeLabel);
                System.out.println("we need to remove fixme for "+ number);
            }

            if (!newLabels.isEmpty() && removedLabels.isEmpty()){
                final String issueUrl = pull.get("issue_url").asString();
                // Set the new labels
                api.addLabels(issueUrl, newLabels);
            }else if (!removedLabels.isEmpty()){
                final String issueUrl = pull.get("issue_url").asString();
                labels.removeAll(removedLabels);
                // Set the new labels
                api.setLabels(issueUrl, labels);
            }
        }

    }

    /**
     * Adds a pull request to be processed.
     *
     * @param pull the pull request to be processed
     */
    void add(final ModelNode pull) {
        /*final int pullNumber = pull.get("number").asInt();
        final String sha1 = pull.get("head", "sha").asString();
        final String issueUrl = pull.get("issue_url").asString();
        boolean mergeable = pull.get("mergeable").asString().equals("true");

        final String key = "pr-" + pullNumber;

        if (pulls.hasDefined(key)) {
            final ModelNode n = pulls.get(key);
            if (!n.get("sha").asString().equals(sha1)) {
                // Add the new sha key to be checked during processing
                n.get("new-sha").set(sha1);
            }
        } else {
            final ModelNode model = new ModelNode();
            model.get("sha").set(sha1);
            model.get("issue_url").set(issueUrl);
            model.get("pull_request_url").set(pull.get("url"));
            model.get("mergeable").set(mergeable);
            pulls.get(key).set(model);
        }*/
    }

    /**
     * Processes the pull requests {@link #add(org.jboss.dmr.ModelNode) added}.
     * <p/>
     * This should normally only be invoked once as it makes API calls to GitHub.
     */
    void process() {
        /*try {
            // Get all the open issues to lessen the hits to the API
            final List<ModelNode> openIssues = api.getIssuesWithPullRequests();

            // Process each issue in the model
            for (Property property : pulls.asPropertyList()) {
                final ModelNode value = property.getValue();
                boolean mergeable = value.get("mergeable").asBoolean();
                // Get the PR url
                final String prUrl = value.get("pull_request_url").asString();
                if (openIssues.hasDefined(prUrl)) {
                    final ModelNode openIssue = openIssues.get(prUrl);

                    // Get the current labels
                    final List<String> currentLabels = getLabels(openIssue);
                    // If no labels are present, we can delete the issue
                    if (currentLabels.isEmpty()) {
                        pulls.remove(property.getName());
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
                            api.setLabels(issueUrl, newLabels);
                            // Node needs to be removed
                            pulls.remove(property.getName());
                        } else if (!changeRequired) {
                            // No change in labels has been required, remove the issue
                            pulls.remove(property.getName());
                        }
                    }
                } else {
                    // The issue/PR may be closed, we can just delete it
                    pulls.remove(property.getName());
                }
            }

            // Write the issues out to a file
            try (final PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC, StandardOpenOption.CREATE))) {
                pulls.writeJSONString(writer, false);
            }
        } catch (IOException e) {
            e.printStackTrace(err);
        }
        */
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

}
