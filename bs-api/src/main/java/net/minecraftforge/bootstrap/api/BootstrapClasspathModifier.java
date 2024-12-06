/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.bootstrap.api;

import java.nio.file.Path;
import java.util.List;

/*
 * This is mean to allow modifications to the classpath before booting.
 * This can be adding extra entries, removing entries, or more importantly
 * merging entries during dev time because IntelliJ/Gradle is stupid and
 * requires classes and resources to be put in different folders even
 * tho Java modules expect them to be in one.
 *
 * You could force them to output to a single directory and that method
 * is recommended for performance reasons. But this is to allow people
 * who don't want to do that simple thing to still work.
 */
public interface BootstrapClasspathModifier {
    /**
     * The name of this service, In theory I could use this to
     * make some form of sorting system. But I have no plans to at this time.
     */
    String name();

    /**
     * Used to modify the classpath that is used before bootstraping into Module land.
     *
     * @return If the return is not the same instance (==) as the input, then a new ClassLoader
     * will be allocated with the new classpath and no parent.
     */
    boolean process(List<Path[]> classpath);


}
