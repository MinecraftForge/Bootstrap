/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap;

import java.lang.module.ModuleFinder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.bootstrap.api.BootstrapEntryPoint;
import net.minecraftforge.securemodules.SecureModuleClassLoader;
import net.minecraftforge.securemodules.SecureModuleFinder;
import net.minecraftforge.unsafe.UnsafeHacks;

public class Bootstrap {
    static final boolean DEBUG = Boolean.getBoolean("bsl.debug");
    static void log(String message) {
        System.out.println(message);
    }

    protected void start(String... args) throws Exception {
        var boot = selectBootModules(ClassPathHelper.getCleanedClassPath());

        // First we need to get ourselves onto a module layer, so that we can be the parent of the actual runtime layer
        var finder = SecureModuleFinder.of(boot.toArray(SecureJar[]::new));
        var targets = boot.stream().map(SecureJar::name).toList();
        var cfg = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.ofSystem(), targets);
        var cl = Thread.currentThread().getContextClassLoader(); //BaseBootstrap.class.getClassLoader();
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(cfg, cl);

        // Find ourselves in the new fancy module environment.
        var bootstrap = layer.findModule("net.minecraftforge.bootstrap").get();
        var moduleCl = bootstrap.getClassLoader();
        var self = Class.forName(this.getClass().getName(), false, moduleCl);
        var inst = self.getDeclaredConstructor().newInstance();

        // And now invoke main as if we had done all the command line arguments to specify modules!
        var moduleMain = findMethod(self, "moduleMain", String[].class);
        if (moduleMain == null)
            throw new IllegalStateException("Could not find \"moduleMain(String[])\" on " + self.getName());
        UnsafeHacks.setAccessible(moduleMain);
        moduleMain.invoke(inst, (Object)args);
    }

    protected List<SecureJar> selectBootModules(List<SecureJar> classpath) {
        var ret = new ArrayList<SecureJar>();
        var bootLibraries = Set.of(
            "cpw.mods.securejarhandler",
            "net.minecraftforge.unsafe",
            "net.minecraftforge.bootstrap",
            "net.minecraftforge.bootstrap.api",
            "org.objectweb.asm",
            "org.objectweb.asm.tree"
        );

        for (var securejar : classpath) {
            if (bootLibraries.contains(securejar.moduleDataProvider().name()))
                ret.add(securejar);
        }

        return ret;
    }

    protected void moduleMain(String... args) throws Exception {
        var bootlayer = getClass().getModule().getLayer();
        var secure = selectRuntimeModules(ClassPathHelper.getCleanedClassPath());

        // Now lets build a layer that has all the non-Bootstrap/SecureModule libraries on it.
        var finder = SecureModuleFinder.of(secure.toArray(SecureJar[]::new));
        var targets = secure.stream().map(SecureJar::name).toList();
        var cfg = bootlayer.configuration().resolveAndBind(finder, ModuleFinder.ofSystem(), targets);
        var parent = List.of(ModuleLayer.boot(), bootlayer);
        var cl = new SecureModuleClassLoader("SECURE-BOOTSTRAP", null, cfg, parent);
        var layer = bootlayer.defineModules(cfg, module -> cl);

        var oldcl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(cl);
            var services = ServiceLoader.load(layer, BootstrapEntryPoint.class).stream().toList();

            if (services.isEmpty())
                throw new IllegalStateException("Could not find any " + BootstrapEntryPoint.class.getName() + " service providers");

            if (services.size() > 1) {
                throw new IllegalStateException("Found multiple " + BootstrapEntryPoint.class.getName() + " service providers: " +
                    services.stream().map(p -> p.get().name()).collect(Collectors.joining(", ")));
            }

            var loader = services.get(0).get();
            if (DEBUG) log("Starting: " + loader.getClass().getModule().getName() + '/' + loader.name());
            loader.main(args);
        } finally {
            Thread.currentThread().setContextClassLoader(oldcl);
        }
    }

    protected List<SecureJar> selectRuntimeModules(List<SecureJar> classpath) {
        var ret = new ArrayList<SecureJar>();
        var bootlayer = getClass().getModule().getLayer();
        var width = classpath.stream().mapToInt(p -> p.moduleDataProvider().name().length()).max().orElse(0) + 1;

        if (DEBUG) log("Found classpath:");
        for (var securejar : classpath) {
            var meta = securejar.moduleDataProvider();
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

    private static Method findMethod(Class<?> cls, String name, Class<?>... parameterTypes) {
        try {
            return cls.getDeclaredMethod(name, String[].class);
        } catch (NoSuchMethodException | SecurityException e) {
            if (cls.getSuperclass() != null)
                return findMethod(cls.getSuperclass(), name, parameterTypes);
        }
        return null;
    }
}
