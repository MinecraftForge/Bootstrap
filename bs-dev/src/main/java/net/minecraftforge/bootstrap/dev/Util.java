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
    private static final String VERSION_DIR         = "META-INF/versions/";
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
        } catch (IOException e) {
            return sneak(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <E extends Throwable, R> R sneak(Exception exception) throws E {
        throw (E)exception;
    }
}
