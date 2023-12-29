/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;

import static net.minecraftforge.bootstrap.Bootstrap.log;

class ClassPathHelper {
    private static final boolean DEBUG = ForgeBootstrap.DEBUG;
    private static final Name AUTOMATIC_MODULE_NAME = new Name("Automatic-Module-Name");
    private static final Name MULTI_RELEASE         = new Name("Multi-Release");
    private static final Name FORGE_MODULE_LAYER    = new Name("Forge-Module-Layer");
    private static final String VERSION_DIR         = "META-INF/versions/";
    private static final String IGNORE              = "META-INF/forge-bootstrap-ignore";
    private static final String MODULE_INFO         = "module-info.class";

    private static List<SecureJar> cache = null;
    public static List<SecureJar> getCleanedClassPath() {
        if (cache == null) {
            try {
                cache = getCleanedClassPathImpl();
            } catch (IOException e) {
                sneak(e);
            }
        }
        return cache;
    }

    private static List<SecureJar> getCleanedClassPathImpl() throws IOException {
        var sourcesets = new HashMap<String, Map<String, List<Path>>>();
        var modules = new TreeMap<String, ModuleInfo>();

        var classpath = findAllClassPathEntries();
        for (var path : classpath) {
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

                if (prj != null) {
                    //if (DEBUG) log("Found Project `" + prj + "` SourceSet `" + sourceset + "` Directory: " + path);
                    var lst = sourcesets
                        .computeIfAbsent(prj, n -> new HashMap<>())
                        .computeIfAbsent(sourceset, n -> new ArrayList<>());
                    if (!lst.contains(path))
                        lst.add(path);
                } //else if (DEBUG) log("Unknown directory format: " + path);
            } else {
                var module = findModuleName(path, null);
                if (module.name() == null) {
                    var meta = JarMetadata.fromFileName(path, Set.of(), List.of());
                    module = new ModuleVersion(meta.name(), meta.version(), module.layer());
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
                var module = findModuleName(dirs);
                if (module == null) {
                    if (DEBUG) {
                        for (int x = 0; x < dirs.size(); x++) {
                            if (x == 0) log("Non Module: " + dirs.get(x));
                            else        log("            " + dirs.get(x));
                        }
                    }
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
                } else if ("boot".equals(module.layer())) { // Allow directories to opt-into being on the boot layer.
                    if (DEBUG) {
                        log("Forced:     " + module.name());
                        dirs.forEach(path -> log("            " + path));
                    }
                    info = new ModuleInfo();
                    info.paths.addAll(dirs);
                    info.version = module.version();
                    modules.put(module.name(), info);
                }
            }
        }

        // TODO: Merge modules by package names

        var jars = new TreeMap<String, SecureJar>();
        for (var info : modules.values()) {
            var paths = new ArrayList<Path>(info.paths.size());
            paths.addAll(info.paths);
            Collections.reverse(paths); // Securejar is last win instead of first win like the classpath
            /*
            var debug = DEBUG && paths.size() > 1;
            if (debug) {
                log("Module: " + info.name);
                for (var path : paths)
                    log("\t" + path);
            }
            */

            var securejar = SecureJar.from(jar -> getMetadata(jar, info), paths.toArray(Path[]::new));
            jars.put(securejar.moduleDataProvider().name(), securejar);

            /*
            if (debug) {
                //desc.packages().stream().sorted().forEach(p -> log("\t" + p));
                securejar.moduleDataProvider().descriptor().provides().forEach(p -> {
                    log("\tPovides: " + p.service());
                    p.providers().forEach(s -> log("\t\t" + s));
                });
            }
            */
        }

        // A system to tell us to ignore certain modules completely.
        // This is used by ForgeDev tests because we essentially have our own locator for those.
        // We filter out mod files so *most* things should be fine. ForgeDev Tests are just weird.
        for (var jar : List.copyOf(jars.values())) {
            var ignore = jar.getPath(IGNORE);
            if (Files.exists(ignore)) {
                if (DEBUG) log("Ingore File: " + ignore);
                var ignores = new ArrayList<>();
                for (var line : Files.readAllLines(ignore, StandardCharsets.UTF_8)) {
                    int idx = line.indexOf('#');
                    if (idx != -1)
                        line = line.substring(0, idx);
                    line = line.trim();
                    if (!line.isEmpty())
                        ignores.add(line);
                }

                if (ignores.isEmpty())
                    ignores.add(jar.moduleDataProvider().name());

                for (var line : ignores) {
                    if (DEBUG) log("\tIgnoring: " + line);
                    jars.remove(line);
                }
            }
        }

        cache = List.copyOf(jars.values());
        return cache;
    }

    private static List<Path> findAllClassPathEntries() throws IOException {
        var parts = System.getProperty("java.class.path").split(File.pathSeparator);
        var paths = new ArrayList<Path>();
        for (var part : parts) {
            var path = new File(part).getCanonicalFile().toPath();
            if (!Files.exists(path)) {
                //if (DEBUG) log("Skipping missing: " + path);
                continue;
            }
            if (Files.isDirectory(path) && Files.list(path).findAny().isEmpty()) {
                //if (DEBUG) log("Skipping empty:   " + path);
                continue;
            }
            paths.add(path);
        }
        return paths;
    }

    private static String getParent(int levels, Path path) {
        var parent = path;
        for (int x = 0; x < levels; x++) {
            parent = parent.getParent();
            if (parent == null)
                return null;
        }
        return parent == null ? null : parent.getFileName().toString();
    }

    private static String getProjectName(int levels, Path path) {
        var name = getParent(levels, path);
        if (name != null)
            return name;
        if (DEBUG)
            log("Invalid directory path: " + path);
        return "default";
    }

    private static ModuleVersion findModuleName(Collection<Path> paths) throws IOException {
        for (var path : paths) {
            var ret = findModuleName(path, null);
            if (ret.name != null)
                return ret;
        }
        return null;
    }

    private record ModuleVersion(String name, String version, String layer) {}
    private static ModuleVersion findModuleName(Path path, String _default) throws IOException {
        Manifest mf = null;
        record InfoData(int version, byte[] data) {}
        var infos = new ArrayList<InfoData>();

        if (Files.isDirectory(path)) {
            var manifest = Files.walk(path)
                .filter(p -> JarFile.MANIFEST_NAME.equalsIgnoreCase(path.relativize(p).toString().replace('\\', '/')))
                .findFirst()
                .orElse(null);

            if (manifest != null && Files.exists(manifest)) {
                try (var is = Files.newInputStream(manifest)) {
                    mf = new Manifest(is);
                }
            }
            var paths = Files.walk(path).filter(p -> p.toString().endsWith(MODULE_INFO)).toList();
            for (var p : paths) {
                var relative = path.relativize(p).toString().replace('\\', '/');
                int version = 0;
                if (relative.startsWith(VERSION_DIR)) {
                    int idx = relative.indexOf('/', VERSION_DIR.length());
                    var ver = relative.substring(VERSION_DIR.length(), idx);
                    version = Integer.parseInt(ver);
                }
                infos.add(new InfoData(version, Files.readAllBytes(p)));
            }
        } else {
            // I would use JarFile here and let the JVM handle all this, but it only works on File objects not Paths
            try (var zip = new ZipInputStream(Files.newInputStream(path))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    var name = entry.getName();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(name)) {
                        mf = new Manifest(zip);
                    } else if (name.endsWith(MODULE_INFO)) {
                        int version = 0;
                        if (name.startsWith(VERSION_DIR)) {
                            int idx = name.indexOf('/', VERSION_DIR.length());
                            var ver = name.substring(VERSION_DIR.length(), idx);
                            version = Integer.parseInt(ver);
                        }
                        infos.add(new InfoData(version, zip.readAllBytes()));
                    }
                }
            }
        }

        InfoData info = null;
        if (mf != null && Boolean.parseBoolean(mf.getMainAttributes().getValue(MULTI_RELEASE))) {
            info = infos.stream()
                .sorted((a, b) -> b.version - a.version)
                .filter(a -> a.version <= Runtime.version().feature())
                .findFirst()
                .orElse(null);
        } else {
            info = infos.stream().filter(i -> i.version == 0).findFirst().orElse(null);
        }

        String name = _default;
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

        return new ModuleVersion(name, version, layer);
    }

    private static JarMetadata getMetadata(SecureJar jar, ModuleInfo info) {
        var ret = JarMetadata.from(jar, jar.getPrimaryPath());

        return ret;
        /*
        // Old implementation Kept here in case I need it. Probably worth deleting.
        ModuleDescriptor.Builder bldr;

        var moduleInfo = jar.moduleDataProvider().findFile(MODULE_INFO);
        if (moduleInfo.isPresent()) {
            var uri = moduleInfo.get();

            try (var is = Files.newInputStream(Path.of(uri))) {
                var desc = ModuleDescriptor.read(is);
                bldr = ModuleDescriptor.newOpenModule(desc.name());
                desc.exports().forEach(bldr::exports);
                //desc.opens().forEach(bldr::opens);
                desc.mainClass().ifPresent(bldr::mainClass);
                bldr.packages(desc.packages());
                desc.provides().forEach(bldr::provides);
                desc.requires().forEach(bldr::requires);
                desc.uses().forEach(bldr::uses);
                desc.version().ifPresent(bldr::version);
            } catch (IOException e) {
                return sneak(e);
            }
        } else {
            bldr = ModuleDescriptor.newAutomaticModule(info.name);
            jar.getProviders().forEach(p -> {
                if (!p.providers().isEmpty())
                    bldr.provides(p.serviceName(), p.providers());
            });
        }
        // Add any packages that we merged in even if we have a real module.
        bldr.packages(jar.getPackages());

        var desc = bldr.build();

        return new JarMetadata() {
            @Override
            public String name() {
                return desc.name();
            }

            @Override
            public String version() {
                return desc.version().map(Object::toString).orElse(null);
            }

            @Override
            public ModuleDescriptor descriptor() {
                return desc;
            }
        };
        */
    }

    private static class ModuleInfo {
        private final List<Path> paths = new ArrayList<>();
        private String version;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, R> R sneak(Exception exception) throws E {
        throw (E)exception;
    }
}
