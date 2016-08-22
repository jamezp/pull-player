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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Allows for checking if labels should be removed as well as a replacement for the removed label,
 * <p/>
 * The properties pattern expects a key of {@code issues.labels} which is a comma delimited set of labels to me
 * removed.  If a label should be replaced the key pattern is {@code issues.label.$label=$new_label} where {@code
 * $label} is the name of the label previously defined in comma delimited list of the label and the value is the name
 * of
 * the new label.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class Labels {
    private final Map<String, String> replacements;
    private String rebaseThis;
    private String fixMe;

    /**
     * Creates the labels looking up the labels in the properties.
     */
    Labels() {
        final Properties properties = Util.getProperties();
        final String prop = properties.getProperty("issue.labels");
        if (prop == null) {
            replacements = Collections.emptyMap();
            return;
        } else {
            final String[] labels = prop.split(",");
            replacements = new HashMap<>();
            for (String label : labels) {
                final String trimmedLabel = label.trim();
                final String mappedLabel = properties.getProperty("issue.label." + trimmedLabel);
                if (mappedLabel == null || mappedLabel.isEmpty()) {
                    replacements.put(trimmedLabel, null);
                } else {
                    replacements.put(trimmedLabel, mappedLabel.trim());
                }
            }
        }

        this.rebaseThis = properties.getProperty("issue.label.rebase");
        this.fixMe = properties.getProperty("issue.label.fixme");
    }

    /**
     * Checks to see whether the label should be replaced
     *
     * @param label the label to check for removal
     *
     * @return {@code true} if the label is removable, otherwise {@code false}
     */
    boolean isRemovable(final String label) {
        return replacements.containsKey(label);
    }

    /**
     * Gets the optional replacement label.
     *
     * @param label the label to get the replacement label for
     *
     * @return the replacement label or {@code null} if no replacement exists
     */
    String getReplacement(final String label) {
        return replacements.get(label);
    }

    public String getRebaseThis() {
        return rebaseThis;
    }

    public String getFixMe() {
        return fixMe;
    }
}
