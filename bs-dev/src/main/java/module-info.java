/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
module net.minecraftforge.bootstrap.dev {
    exports net.minecraftforge.bootstrap.dev;
    requires net.minecraftforge.bootstrap.api;

    provides net.minecraftforge.bootstrap.api.BootstrapClasspathModifier with
        net.minecraftforge.bootstrap.dev.BootstrapDevClasspathFixer;
}