package com.github.slmpc.irisrt.rhi;

import java.util.List;
import java.util.Map;

/** Extension payload on Iris's resolved Bindings record; captured once, shared by views and synchronization. */
public interface ResolvedAccelerationStructures {
    Map<String, List<AccelerationStructure>> accelerationStructures();
    void setAccelerationStructures(Map<String, List<AccelerationStructure>> structures);
}
