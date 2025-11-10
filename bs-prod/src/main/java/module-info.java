/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
module net.minecraftforge.bootstrap.prod {
    exports net.minecraftforge.bootstrap.prod;

    requires net.minecraftforge.bootstrap.api;

    provides net.minecraftforge.bootstrap.api.BootstrapClasspathModifier with
        net.minecraftforge.bootstrap.prod.BootstrapProdClasspathFixer;
}