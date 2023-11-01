/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap.api;

/**
 * After setting up the new module layers, Bootstrap will find the first service and
 * execute it as if a normal java main entry point.
 */
public interface BootstrapEntryPoint {
    /**
     * Just like normal java main functions, command line arguments are passed in from the user.
     * Potentially enhanced by Bootstrap itself.
     *
     * @param args Command line arguments
     */
    void main(String... args);

    /**
     * A unique name for this service, only used for debugging info if multiple services are found.
     * However, I could potentially use this to allow selection of multiple entry points.
     */
    default String name() {
        return this.getClass().getName();
    }
}
