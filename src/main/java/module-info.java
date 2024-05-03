/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
module net.minecraftforge.bootstrap {
    requires transitive cpw.mods.securejarhandler;

    exports net.minecraftforge.bootstrap;

    requires net.minecraftforge.bootstrap.api;
    requires net.minecraftforge.unsafe;

    uses net.minecraftforge.bootstrap.api.BootstrapEntryPoint;
    uses net.minecraftforge.bootstrap.api.BootstrapClasspathModifier;
}