/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import com.sun.xml.internal.ws.util.UtilException;

/**
 * @author Jason T. Greene
 */
public class Jobs {
    private static final File jobDir = new File(Util.BASE_DIR, "completed-jobs");

    public static String getCompletedJob(String sha1) {
        File job = new File(jobDir, sha1);
        BufferedReader reader;
        try {
            reader = new BufferedReader(new FileReader(job));
        } catch (FileNotFoundException e) {
            return null;
        }

        try {
            reader.readLine();
            return reader.readLine().trim();
        } catch (IOException e) {
            return null;
        } finally {
            Util.safeClose(reader);
        }
    }

    public static void storeCompletedJob(String sha1, int pull, int build) {
        jobDir.mkdir();
        File file = new File(jobDir, sha1);
        PrintWriter writer = null;
        try {
            file.createNewFile();
            writer = new PrintWriter(new FileWriter(file));
            writer.println(String.valueOf(pull));
            writer.println(String.valueOf(build));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            Util.safeClose(writer);
        }
    }

    public static void remove(String sha1) {
        new File(jobDir, sha1).delete();
    }
}
