/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;
import java.util.regex.Pattern;

/**
 * A small utility class that I found useful to have between Bootstrap projects
 */
public class Util {
    private static final Name AUTOMATIC_MODULE_NAME = new Name("Automatic-Module-Name");
    private static final Name MULTI_RELEASE         = new Name("Multi-Release");
    private static final Name FORGE_MODULE_LAYER    = new Name("Forge-Module-Layer");
    private static final String META_INF            = "META-INF";
    private static final String MANIFEST            = "MANIFEST.MF";
    private static final String VERSIONS            = "versions";
    private static final String MODULE_INFO         = "module-info.class";

    public record ModuleInfo(String name, String version, String layer) {}

    /**
     * Attempt to get the Module Info for a collection of paths.
     * The first one found will be returned.
     *
     * It will throw any errors that happen when reading the paths.
     *
     * @return Null if no named module is found
     */
    public static ModuleInfo findModule(Collection<Path> paths) {
        for (var path : paths) {
            var ret = findModuleNameImpl(path, false);
            if (ret.name != null)
                return ret;
        }
        return null;
    }

    /**
     * Attempt to get the Module Info for a array of paths.
     * The first one found will be returned
     *
     * It will throw any errors that happen when reading the paths.
     *
     * @return Null if no named module is found
     */
    public static ModuleInfo findModule(Path... paths) {
        for (var path : paths) {
            var ret = findModuleNameImpl(path, false);
            if (ret.name != null)
                return ret;
        }
        return null;
    }

    /**
     * Attempt to get the Module Info for a collection of paths.
     * The first one found will be returned.
     *
     * If no manifest or module-info is found, and this is a single jar path,
     * it will attempt to parse the filename as a module name.
     *
     * It will throw any errors that happen when reading the paths.
     *
     * @return Null if no named module is found
     */
    public static ModuleInfo findAutomaticModule(Collection<Path> paths) {
        var ret = findModule(paths);
        if (ret != null)
            return ret;

        if (paths.size() == 1)
            return findAutomaticModuleImpl(paths.iterator().next());

        return null;
    }

    /**
     * Attempt to get the Module Info for a collection of paths.
     * The first one found will be returned.
     *
     * If no manifest or module-info is found, and this is a single jar path,
     * it will attempt to parse the filename as a module name.
     *
     * It will throw any errors that happen when reading the paths.
     *
     * @return Null if no named module is found
     */
    public static ModuleInfo findAutomaticModule(Path... paths) {
        var ret = findModule(paths);
        if (ret != null)
            return ret;

        if (paths.length == 1)
            return findAutomaticModuleImpl(paths[0]);

        return null;
    }



    /* ======================================================================
     *                               PRIVATE
     * ======================================================================
     */

    private static ModuleInfo findModuleNameImpl(Path path, boolean slow) {
        try {
            Candidates data = null;
            if (Files.isDirectory(path)) {
                data = findCandidatesDirectory(path);
            } else {
                try (FileSystem jarFS = FileSystems.newFileSystem(path)) {
                    var root = jarFS.getPath("/");
                    data = findCandidatesDirectory(root);
                }
            }

            var mf = data.mf;
            var infos = data.modules;

            InfoData info = null;
            if (infos.size() == 1) {
                info = infos.get(0);
            } else if (infos.size() > 1) {
                info = infos.stream()
                    .sorted((a, b) -> b.version - a.version)
                    .findFirst()
                    .orElse(null);
            }

            String name = null;
            String version = null;
            String layer = null;

            if (mf != null) {
                name = mf.getMainAttributes().getValue(AUTOMATIC_MODULE_NAME);
                layer = mf.getMainAttributes().getValue(FORGE_MODULE_LAYER);
            }

            if (info != null) {
                var desc = ModuleDescriptor.read(new ByteArrayInputStream(info.data));
                name = desc.name();
                version = desc.version().map(Object::toString).orElse(null);
            }

            return new ModuleInfo(name, version, layer);
        } catch (IOException e) {
            return sneak(e);
        }
    }

    record InfoData(int version, byte[] data) {}
    record Candidates(Manifest mf, List<InfoData> modules) {}
    private static Candidates findCandidatesDirectory(Path path) {
        try {
            var infos = new ArrayList<InfoData>();
            var meta_inf = findInsensitive(path, META_INF);
            var manifest = findInsensitive(meta_inf, MANIFEST);
            Manifest mf = null;

            if (manifest != null && Files.exists(manifest)) {
                try (var is = Files.newInputStream(manifest)) {
                    mf = new Manifest(is);
                }
            }

            var module_info = path.resolve(MODULE_INFO);
            if (Files.exists(module_info)) {
                infos.add(new InfoData(0, Files.readAllBytes(module_info)));
            }

            if (isMultiRelease(mf)) {
                var versions = findInsensitive(meta_inf, VERSIONS);
                if (versions != null) {
                    Files.list(versions)
                    .forEach(v -> {
                        try {
                            int version = Integer.parseInt(v.getFileName().toString());
                            var mod_info = v.resolve(MODULE_INFO);
                            if (version <= Runtime.version().feature() && Files.exists(mod_info))
                                infos.add(new InfoData(version, Files.readAllBytes(mod_info)));
                        } catch (NumberFormatException e) {
                            // If its not a numerical directory we don't care
                        } catch (IOException e) {
                            sneak(e);
                        }
                    });
                }
            }
            return new Candidates(mf, infos);
        } catch (IOException e) {
            return sneak(e);
        }
    }

    private static Path findInsensitive(Path root, String name) {
        if (root == null)
            return null;

        // Fast path, its the correct case or on a file system that doesn't care about case
        var child = root.resolve(name);
        if (Files.exists(child))
           return child;

        try {
            // If we can't find it fall back to listing all files and manually check, its slow but whatever
            return Files.list(root)
                .filter(p -> name.equalsIgnoreCase(p.getFileName().toString()))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return sneak(e);
        }
    }

    private static boolean isMultiRelease(Manifest mf) {
        return mf != null && Boolean.parseBoolean(mf.getMainAttributes().getValue(MULTI_RELEASE));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, R> R sneak(Exception exception) throws E {
        throw (E)exception;
    }

    private static ModuleInfo findAutomaticModuleImpl(Path path) {
        var filename = path.getFileName();
        if (filename == null)
            return null;

        var name = path.getFileName().toString();
        if (!name.endsWith(".jar"))
            return null;

        String version = null;
        var matcher = Patterns.DASH_VERSION.matcher(name);
        if (matcher.find()) {
            int start = matcher.start();

            // attempt to parse the tail as a version string
            try {
                String tail = name.substring(start + 1);
                ModuleDescriptor.Version.parse(tail);
                version = tail;
            } catch (IllegalArgumentException ignore) { }

            name = name.substring(0, start);
        }

        return new ModuleInfo(cleanModuleName(name), version, null);
    }

    // Stolen from jdk.internal.module.ModulePath
    private static class Patterns {
        static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");
        static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");
        static final Pattern REPEATING_DOTS = Pattern.compile("(\\.)(\\1)+");
        static final Pattern LEADING_DOTS = Pattern.compile("^\\.");
        static final Pattern TRAILING_DOTS = Pattern.compile("\\.$");
    }

    // Stolen from jdk.internal.module.ModulePath
    private static String cleanModuleName(String mn) {
        // replace non-alphanumeric
        mn = Patterns.NON_ALPHANUM.matcher(mn).replaceAll(".");

        // collapse repeating dots
        mn = Patterns.REPEATING_DOTS.matcher(mn).replaceAll(".");

        // drop leading dots
        if (!mn.isEmpty() && mn.charAt(0) == '.')
            mn = Patterns.LEADING_DOTS.matcher(mn).replaceAll("");

        // drop trailing dots
        int len = mn.length();
        if (len > 0 && mn.charAt(len-1) == '.')
            mn = Patterns.TRAILING_DOTS.matcher(mn).replaceAll("");

        return mn;
    }
}
