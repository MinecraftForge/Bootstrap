/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap.dev;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraftforge.bootstrap.api.BootstrapClasspathModifier;
import net.minecraftforge.bootstrap.api.Util;

public class BootstrapDevClasspathFixer implements BootstrapClasspathModifier {
    private static final boolean DEBUG    = Boolean.parseBoolean(System.getProperty("bsl.debug",        "false"));
    private static final boolean AUTO     = Boolean.parseBoolean(System.getProperty("bsl.dev.auto",     "true" ));
    private static final boolean EXPLICIT = Boolean.parseBoolean(System.getProperty("bsl.dev.explicit", "true" ));

    static void log(String message) {
        System.out.println(message);
    }

    @Override
    public String name() {
        return "Dev";
    }

    @Override
    public boolean process(List<Path[]> classpath) {
        var ret = false;

        if (EXPLICIT)
            ret |= processExplicit(classpath);

        if (AUTO)
            ret |= processAuto(classpath);

        return ret;
    }

    /**
     * Uses the old MOD_CLASSES environment variable to attempt merge paths together.
     * This is really hackey and relies on pre-launch things to build the correct mappings for this.
     * The basic format is a list of strings separated by File.pathSeparator
     * If can optionally have a group identifier in each entry by using the format: "identifier%%path"
     * Example:
     *   "path1;path2"
     *   "modA%%path1;modA%%path2;modB%%path3"
     */
    private boolean processExplicit(List<Path[]> classpath) {
        // First, lets check if explicit paths are specified and build the list of things to merge
        var mod_classes = System.getenv("MOD_CLASSES");
        if (mod_classes == null || mod_classes.isBlank())
            return false;

        var map = new HashMap<String, List<Path>>();
        var claimed = new HashSet<Path>();
        for (var entry : mod_classes.split(File.pathSeparator)) {
            if (entry.isBlank())
                continue;

            int idx = entry.indexOf("%%");
            var id = "defaultmodid"; // Use the same magic string so that outside tools don't need to care.
            var path = entry;
            if (idx != -1) {
                id   = entry.substring(0, idx);
                path = entry.substring(idx + 2);
            }

            var absolute = Path.of(path).toAbsolutePath();
            var list = map.computeIfAbsent(id, k -> new ArrayList<>());
            // Prevent duplicate paths for some setups because cpw forced the MOD_CLASSES to have two entries for each
            if (!list.contains(absolute))
                list.add(absolute);
            claimed.add(absolute);
        }

        // Remove any entries that are only a single path, no need to merge them
        map.values().removeIf(paths -> paths.size() <= 1);
        /*
        for (var itr  = map.values().iterator(); itr.hasNext(); ) {
            if (itr.next().size() <= 1)
                itr.remove();
        }
        */

        // No explicit paths set, so nope out
        if (map.isEmpty())
            return false;

        if (DEBUG) {
            log("MOD_CLASSES:");
            map.forEach((id, paths) -> {
                log("    " + id + ':');
                for (var path : paths)
                    log("        " + path);
            });
        }

        // Remove anything from the path that we need to merge
        var modified = new ArrayList<Path[]>();
        for (var paths : classpath) {
            var hit = false;
            var miss = false;
            for (int x = 0; x < paths.length; x++) {
                if (claimed.contains(paths[x].toAbsolutePath())) {
                    paths[x] = null;
                    hit = true;
                } else
                    miss = true;
            }

            if (hit && miss) // We removed some but some were left over, so remove the nulls
                modified.add(Arrays.stream(paths).filter(p -> p != null).toArray(Path[]::new));
            else if (miss) // We didn't claim anything
                modified.add(paths);
            // else we either hit everything, or there was nothing to hit, so skip
        }

        // Now add our merged modules
        for (var paths : map.values())
            modified.add(paths.toArray(Path[]::new));

        classpath.clear();
        classpath.addAll(modified);

        return true;
    }

    /**
     * Attempts to automatically merge sourcesets following the default directory structure created by eclipse and intellij.
     * This is basically what FG does to build the MOD_LIST variables.
     */
    private boolean processAuto(List<Path[]> classpath) {
        var sourcesets = new HashMap<String, Map<String, List<Path>>>();
        var modules = new TreeMap<String, ModuleInfo>();
        var ret = false;
        var modified = new ArrayList<Path[]>();

        for (var paths : classpath) {
            if (paths.length != 1) {
                modified.add(paths);
                continue;
            }

            var path = paths[0];
            if (Files.isDirectory(path)) {
                String prj = null;
                String sourceset = path.getFileName().toString();

                // Old value from eclipse, isnt actually a thing anymore.
                if ("default".equals(sourceset))
                    continue;

                if ("bin".equals(getParent(1, path))) {
                    /*
                     * Eclipse shoves both classes and resources into the same folder
                     *   forge\bin\main
                     *   forge\bin\test
                     *   forge\bin\default
                     */
                    prj = getProjectName(2, path);
                } else if ("resources".equals(getParent(1, path))) {
                    /*
                     * IntelliJ/Gradle resources:
                     *   forge\build\resources\main
                     *   forge\build\resources\test
                     */
                    prj = getProjectName(3, path);
                } else if ("classes".equals(getParent(2, path))){
                    /*
                     * IntelliJ/Gradle classes:
                     *   forge\build\classes\java\main
                     *   forge\build\classes\java\test
                     */
                    prj = getProjectName(4, path);
                }

                if (prj != null && sourceset != null) {
                    //if (DEBUG) log("Found Project `" + prj + "` SourceSet `" + sourceset + "` Directory: " + path);
                    var lst = sourcesets
                        .computeIfAbsent(prj, n -> new HashMap<>())
                        .computeIfAbsent(sourceset, n -> new ArrayList<>());
                    if (!lst.contains(path))
                        lst.add(path);
                } //else if (DEBUG) log("Unknown directory format: " + path);
            } else {
                var module = Util.findModule(path);
                if (module.name() == null) {
                    //var meta = JarMetadata.fromFileName(path, Set.of(), List.of());
                    //module = new ModuleVersion(meta.name(), meta.version(), module.layer());
                    if (DEBUG)
                        log("Non Module: " + path);
                    modified.add(new Path[] { path });
                    continue;
                }

                //if (DEBUG) log("\tModule: " + module.name());

                var info = modules.computeIfAbsent(module.name(), k -> new ModuleInfo());
                info.paths.add(path);
                if (info.version == null)
                    info.version = module.version();
            }
        }

        for (var prj : sourcesets.keySet()) {
            var sources = sourcesets.get(prj);
            for (var source : sources.keySet()) {
                var dirs = sources.get(source);
                var module = Util.findModule(dirs);
                if (module == null) {
                    if (DEBUG) {
                        for (int x = 0; x < dirs.size(); x++) {
                            if (x == 0) log("Non Module: " + dirs.get(x));
                            else        log("            " + dirs.get(x));
                        }
                    }
                    // Non-module, just shove it on the path as it was before
                    for (var path : dirs)
                        modified.add(new Path[] { path });
                    continue;
                }

                var info = modules.get(module.name());
                if (info != null) {
                    // Existing module, but we also found an exploded directory, so assume we are developing that module and replace it.
                    if (DEBUG) {
                        log("Overriding: " + module.name());
                        for (int x = 0; x < info.paths.size(); x++) {
                            if (x == 0) log("       Old: " + info.paths.get(x));
                            else        log("            " + info.paths.get(x));
                        }
                        for (int x = 0; x < dirs.size(); x++) {
                            if (x == 0) log("       New: " + dirs.get(x));
                            else        log("            " + dirs.get(x));
                        }
                    }

                    info.paths.clear();
                    info.paths.addAll(dirs);
                    if (info.version == null)
                        info.version = module.version();
                    ret = true;
                } else if ("boot".equals(module.layer())) { // Allow directories to opt-into being on the boot layer.
                    if (DEBUG) {
                        log("Forced:     " + module.name());
                        dirs.forEach(path -> log("            " + path));
                    }
                    info = new ModuleInfo();
                    info.paths.addAll(dirs);
                    info.version = module.version();
                    modules.put(module.name(), info);
                    ret = true;
                } else {
                    // Non-module, just shove it on the path as it was before
                    for (var path : dirs)
                        modified.add(new Path[] { path });
                }
            }
        }

        // TODO: Merge modules by package names

        if (ret) {
            for (var module : modules.values())
                modified.add(module.paths.toArray(Path[]::new));
            classpath.clear();
            classpath.addAll(modified);
        }

        return ret;
    }

    private static String getParent(int levels, Path path) {
        var parent = path;
        for (int x = 0; x < levels; x++) {
            parent = parent.getParent();
            if (parent == null)
                return null;
        }

        if (parent == null) return null;
        var fileName = parent.getFileName();
        if (fileName == null) return null;
        return fileName.toString();
    }

    private static String getProjectName(int levels, Path path) {
        var name = getParent(levels, path);
        if (name != null)
            return name;
        if (DEBUG)
            log("Invalid directory path: " + path);
        return "default";
    }

    private static class ModuleInfo {
        private final List<Path> paths = new ArrayList<>();
        private String version;
    }
}
