/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.player;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Jason T. Greene
 * @author Tomaz Cerar
 */
public class PersistentList {
    private final Set<String> list;
    private final File file;

    private PersistentList(Set<String> list, File file) {
        this.list = list;
        this.file = file;
    }

    public static PersistentList loadList(String fileName) {
        BufferedReader reader = null;
        try {
            File file = new File(Util.BASE_DIR, fileName);
            file.createNewFile();
            reader = new BufferedReader(new FileReader(file));
            HashSet<String> list = new HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                list.add(line);
            }
            return new PersistentList(list, file);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            Util.safeClose(reader);
        }
    }

    public boolean has(String name) {
        return list.contains(name);
    }

    public void add(String user) {
        list.add(user);
        PrintWriter stream = null;
        try {
            stream = new PrintWriter(new FileOutputStream(file, true));
            stream.println(user);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        } finally {
            Util.safeClose(stream);
        }
    }

    public boolean remove(String name) {
        return list.remove(name);
    }

    public boolean contains(String o) {
        return list.contains(o);
    }

    public void saveAll() {
        PrintWriter stream = null;
        try {
            stream = new PrintWriter(new FileOutputStream(file, false));
            for (String el : list) {
                stream.println(el);
            }
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        } finally {
            Util.safeClose(stream);
        }
    }
}
