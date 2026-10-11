package com.github.slmpc.irisrt.rhi;

import java.util.List;
import java.util.Map;

/** 附加到 Iris 已解析的 Bindings 记录上的扩展数据；捕获一次，供资源视图和同步操作共用。 */
public interface ResolvedAccelerationStructures {
    Map<String, List<AccelerationStructure>> accelerationStructures();
    void setAccelerationStructures(Map<String, List<AccelerationStructure>> structures);
}
