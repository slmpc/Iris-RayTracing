package com.github.slmpc.luminrt.scene;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 发布 CPU 快照并合并更新；空数据表示卸载，重放用于重新创建 GPU 场景。 */
public final class TerrainGeometryRegistry {
    private final Map<Object, TerrainGeometry> resident = new IdentityHashMap<>();
    private final Map<Object, TerrainGeometry> pending = new IdentityHashMap<>();
    private long epoch;

    public record Change(Object key, TerrainGeometry geometry) {}
    public synchronized long epoch() { return epoch; }

    public synchronized void publish(Object key, TerrainGeometry geometry, long sourceEpoch) {
        if (sourceEpoch != epoch) return;
        if (geometry != null && geometry.meshes().isEmpty()) geometry = null;
        var previous = geometry == null ? resident.remove(key) : resident.put(key, geometry);
        if (previous != geometry) pending.put(key, geometry);
    }

    public synchronized List<Change> drain() {
        var changes = new ArrayList<Change>(pending.size());
        pending.forEach((key, geometry) -> changes.add(new Change(key, geometry)));
        pending.clear();
        return changes;
    }

    public synchronized void replay() { pending.putAll(resident); }
    public synchronized void clearPending() { pending.clear(); }
    public synchronized void clearWorld() { epoch++; resident.clear(); pending.clear(); }
    public synchronized int size() { return resident.size(); }
}
