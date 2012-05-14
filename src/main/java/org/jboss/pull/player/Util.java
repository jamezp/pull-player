/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author Jason T. Greene
 */
class Util {
    static final File BASE_DIR;

    static {
        String home = System.getProperty("user.home");
        if (home != null) {
            BASE_DIR = new File(new File(home), ".pull-player");
            BASE_DIR.mkdirs();
        } else {
            BASE_DIR = new File(".");
        }
    }

    static void dumpInputStream(InputStream stream) {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream));
        String line;
        try {
            while ((line = bufferedReader.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    static void safeClose(Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (Throwable e) {
        }
    }

    static Map<String, String> map(String... args) {
        if (args == null || args.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < args.length;) {
            map.put(args[i++], args[i++]);
        }

        return map;
    }

    static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        props.load(new FileReader(new File(BASE_DIR, "player.properties")));
        return props;
    }

    static String require(Properties props, String name) {
        String ret = (String) props.get(name);
        if (ret == null)
            throw new RuntimeException(name + " must be specified in player.properties");

        return ret;
    }
}
