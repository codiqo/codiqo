package io.codiqo.core.java;

import java.util.Collection;

import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.Plugin;

public class SpotbugsDetectors extends DetectorFactoryCollection {
    public SpotbugsDetectors(Collection<Plugin> enabled) {
        super(enabled);
    }
}
