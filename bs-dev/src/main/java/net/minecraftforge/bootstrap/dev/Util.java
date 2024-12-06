/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap.dev;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class Util {
    private static final Name AUTOMATIC_MODULE_NAME = new Name("Automatic-Module-Name");
    private static final Name MULTI_RELEASE         = new Name("Multi-Release");
    private static final Name FORGE_MODULE_LAYER    = new Name("Forge-Module-Layer");
    private static final String META_INF            = "META-INF";
    private static final String MANIFEST            = "MANIFEST.MF";
    private static final String VERSIONS            = "versions";
    private static final String VERSION_DIR         = META_INF + '/' + VERSIONS + '/';
    private static final String MODULE_INFO         = "module-info.class";

    static ModuleVersion findModuleName(Collection<Path> paths) {
        for (var path : paths) {
            var ret = findModuleName(path, null);
            if (ret.name != null)
                return ret;
        }
        return null;
    }

    static ModuleVersion findModuleName(Path... paths) {
        for (var path : paths) {
            var ret = findModuleName(path, null);
            if (ret.name != null)
                return ret;
        }
        return null;
    }

    record ModuleVersion(String name, String version, String layer) {}
    static ModuleVersion findModuleName(Path path, String _default) {
        try {
            Manifest mf = null;
            record InfoData(int version, byte[] data) {}
            var infos = new ArrayList<InfoData>();

            if (Files.isDirectory(path)) {
                var meta_inf = findInsensitive(path, META_INF);
                var manifest = findInsensitive(meta_inf, MANIFEST);

                if (manifest != null && Files.exists(manifest)) {
                    try (var is = Files.newInputStream(manifest)) {
                        mf = new Manifest(is);
                    }
                }

                var module_info = path.resolve(MODULE_INFO);
                if (Files.exists(module_info)) {
                    infos.add(new InfoData(0, Files.readAllBytes(path)));
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
            } else {
                // I would use JarFile here and let the JVM handle all this.
                // but it only works on File objects not Paths
                // Normal java gets around this by extracting all non-file paths
                // to a temp directory and opening them as normal files.
                // I do not want to do this. So this is what you get.
                try (var zip = new ZipInputStream(Files.newInputStream(path))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        var name = entry.getName();
                        if (mf == null && JarFile.MANIFEST_NAME.equalsIgnoreCase(name)) {
                            mf = new Manifest(zip);
                        } else if (name.endsWith(MODULE_INFO)) {
                            int version = 0;
                            if (name.startsWith(VERSION_DIR)) {
                                if (!isMultiRelease(mf))
                                    continue;

                                int idx = name.indexOf('/', VERSION_DIR.length());
                                var ver = name.substring(VERSION_DIR.length(), idx);
                                try {
                                    version = Integer.parseInt(ver);
                                } catch (NumberFormatException e) {
                                    // If its not a numerical directory we don't care
                                    version = Integer.MAX_VALUE;
                                }
                            }
                            if (version <= Runtime.version().feature())
                                infos.add(new InfoData(version, zip.readAllBytes()));
                        }
                    }
                }
            }

            InfoData info = null;
            if (infos.size() == 1) {
                info = infos.get(0);
            } else if (infos.size() > 1) {
                info = infos.stream()
                    .sorted((a, b) -> b.version - a.version)
                    .findFirst()
                    .orElse(null);
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
    static <E extends Throwable, R> R sneak(Exception exception) throws E {
        throw (E)exception;
    }
}
