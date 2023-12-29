/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap.shim;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class Main {
    private static final boolean DEBUG = Boolean.getBoolean("bss.debug");
    public static void main(String[] args) throws Exception {
        boolean failed = false;
        List<URL> urls = new ArrayList<>();
        StringBuilder classpath = new StringBuilder(System.getProperty("java.class.path"));

        try (
            InputStream stream = getStream("bootstrap-shim.list");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
        ) {
            if (DEBUG)
                System.out.println("Loading classpath: ");
            String line = reader.readLine();
            while (line != null) {
                ListEntry entry = ListEntry.from(line);
                //System.out.println(entry);
                File target = new File("libraries/"+ entry.path);
                if (!target.exists()) {
                    System.out.println("Missing required library: " + entry.path);
                    failed = true;
                }
                classpath.append(File.pathSeparator).append(target.getAbsolutePath());
                URL url = target.toURI().toURL();
                if (DEBUG)
                    System.out.println(url);
                urls.add(url);
                line = reader.readLine();
            }
        }

        if (failed)
            throw new IllegalStateException("Missing required libraries! Check log");

        Properties props = new Properties();
        try (InputStream stream = getStream("bootstrap-shim.properties")) {
            props.load(stream);
        }

        int wantedJavaVersion = Integer.parseInt(props.getProperty("Java-Version", "0"));
        int currentJavaVersion = getJavaVersion();
        if (wantedJavaVersion > currentJavaVersion)
            throw new IllegalStateException("Current java is " + System.getProperty("java.version") + " but we require atleast " + wantedJavaVersion);

        String mainClass = props.getProperty("Main-Class");
        if (mainClass == null)
            throw new IllegalStateException("Could not find \"Main-Class\" in \"server-shim.properties\"");

        if (DEBUG)
            System.out.println("Detected Main Class: " + mainClass);

        String extraArgs = props.getProperty("Arguments");
        if (extraArgs != null) {
            if (DEBUG)
                System.out.println("Detected extra arguments: " + extraArgs);

            String[] pts = extraArgs.split(" ");
            String[] joined = new String[pts.length + args.length];
            System.arraycopy(pts, 0, joined, 0, pts.length);
            System.arraycopy(args, 0, joined, pts.length, args.length);
            args = joined;
        }

        System.setProperty("java.class.path", classpath.toString());

        ClassLoader parent = Main.class.getClassLoader();
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);

        ClassLoader oldCL = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> cls = Class.forName(mainClass, false, loader);
            Method main = cls.getDeclaredMethod("main", String[].class);
            main.invoke(null, (Object)args);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCL);
        }
    }

    private static InputStream getStream(String path) throws IOException {
        InputStream stream = Main.class.getResourceAsStream("/" + path);
        if (stream != null)
            return stream;
        File file = new File(path);
        if (!file.exists())
            throw new IllegalStateException("Missing resource: " + path);
        return new FileInputStream(file);
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1."))  // Pre-Java 9 they all started with "1."
            version = version.substring(2);

        int dot = version.indexOf(".");
        if (dot != -1) // I only care about the major version
            version = version.substring(0, dot);

        return Integer.parseInt(version);
    }

    private static final class ListEntry {
        private final String sha256;
        //private final String id;
        private final String path;

        private static ListEntry from(String line) {
            String[] parts = line.split("\t", 3);
            return new ListEntry(parts[0], parts[1], parts[2]);
        }

        private ListEntry(String sha256, String id, String path) {
            this.sha256 = sha256;
            //this.id = id;
            this.path = path;
        }

        @Override
        public String toString() {
            return "Entry[" + sha256 + " " + path + "]";
        }
    }
}
