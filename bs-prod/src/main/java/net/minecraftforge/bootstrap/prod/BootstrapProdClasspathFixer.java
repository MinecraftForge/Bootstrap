/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap.prod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraftforge.bootstrap.api.BootstrapClasspathModifier;
import net.minecraftforge.bootstrap.api.Util;

public class BootstrapProdClasspathFixer implements BootstrapClasspathModifier {
    private static final boolean DEBUG    = Boolean.parseBoolean(System.getProperty("bsl.debug",        "false"));
    private static final boolean IGNORE   = Boolean.parseBoolean(System.getProperty("bsl.dev.ignore",   "true" ));
    private static final Path    IGNORE_FILE = Path.of("META-INF/forge-bootstrap-ignore");

    static void log(String message) {
        System.out.println(message);
    }

    @Override
    public String name() {
        return "Production";
    }

    @Override
    public boolean process(List<Path[]> classpath) {
        var ret = false;

        if (IGNORE)
            ret |= processIgnore(classpath);

        return ret;
    }

    /* A system to tell us to ignore certain modules completely.
     * This is used by ForgeDev tests because we essentially have our own locator for those.
     * We filter out mod files so *most* things should be fine. ForgeDev Tests are just weird.
     */
    private boolean processIgnore(List<Path[]> classpath) {
        var toIgnore = new HashSet<String>();
        for (var paths : classpath) {
            var ignoreSelf = false;
            for (var path : paths) {
                if (!Files.isDirectory(path))
                    continue;

                var ignore = path.resolve(IGNORE_FILE);
                if (Files.exists(ignore)) {
                    if (DEBUG) log("Ingore File: " + ignore);
                    var ignores = new ArrayList<String>();
                    try {
                        for (var line : Files.readAllLines(ignore, StandardCharsets.UTF_8)) {
                            int idx = line.indexOf('#');
                            if (idx != -1)
                                line = line.substring(0, idx);
                            line = line.trim();
                            if (!line.isEmpty())
                                ignores.add(line);
                        }
                    } catch (IOException e) {
                        return sneak(e);
                    }

                    if (ignores.isEmpty())
                        ignoreSelf = true;

                    for (var line : ignores) {
                        if (DEBUG) log("\tIgnoring: " + line);
                        toIgnore.add(line);
                    }
                }
            }

            if (ignoreSelf) {
                var module = Util.findModule(paths);
                if (module == null) {
                    log("\tInvalid Ignore File, could not find module name:");
                    for (var path : paths)
                        log("\t\t" + path);
                } else {
                    toIgnore.add(module.name());
                }
            }
        }

        if (toIgnore.isEmpty())
            return false;

        var ret = false;
        for (var itr = classpath.iterator(); itr.hasNext(); ) {
            var paths = itr.next();
            var module = Util.findAutomaticModule(paths);

            if (module != null && toIgnore.contains(module.name())) {
                itr.remove();
                ret = true;
            }
        }

        return ret;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, R> R sneak(Exception exception) throws E {
        throw (E)exception;
    }
}
