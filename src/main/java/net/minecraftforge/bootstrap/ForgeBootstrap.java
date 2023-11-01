/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import cpw.mods.jarhandling.SecureJar;

public class ForgeBootstrap extends Bootstrap {
    private static final String MODS_TOML = "META-INF/mods.toml";
    private static final String MINECRAFT = "net/minecraft/client/main/Main.class";
    private static final Attributes.Name MOD_TYPE = new Attributes.Name("FMLModType");

    public static void main(String[] args) throws Exception {
        new ForgeBootstrap().start(args);
    }

    @Override
    protected List<SecureJar> selectRuntimeModules(List<SecureJar> classpath) {
        var ret = new ArrayList<SecureJar>();
        var bootlayer = getClass().getModule().getLayer();
        var width = classpath.stream().mapToInt(p -> p.moduleDataProvider().name().length()).max().orElse(0) + 1;

        if (DEBUG) log("Found classpath:");
        for (var securejar : classpath) {
            // If it's a mod we'll find it later
            var meta = securejar.moduleDataProvider();
            if (meta.findFile(MODS_TOML).isPresent() ||
                meta.findFile(MINECRAFT).isPresent() ||
                meta.getManifest().getMainAttributes().getValue(MOD_TYPE) != null) {
                if (DEBUG) log("  ModFile:   " + pad(width, meta.name()) + securejar.getPrimaryPath());
                continue;
            }

            if (bootlayer.findModule(meta.name()).isEmpty()) {
                if (DEBUG) log("  Module:    " + pad(width, meta.name()) + securejar.getPrimaryPath());
                ret.add(securejar);
            } else {
                if (DEBUG) log("  Bootstrap: " + pad(width, meta.name()) + securejar.getPrimaryPath());
            }
        }

        return ret;
    }

    private static String pad(int width, String str) {
        return str + " ".repeat(width - str.length());
    }
}
