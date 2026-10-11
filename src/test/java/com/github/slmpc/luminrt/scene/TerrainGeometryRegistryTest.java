package com.github.slmpc.luminrt.scene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainGeometryRegistryTest {
    private static TerrainGeometry geometry(long node) {
        return new TerrainGeometry(node, List.of(new TriangleMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2}, true)));
    }

    @Test
    void coalescesReplacementAndRemovalAndReplaysResidentSections() {
        var registry = new TerrainGeometryRegistry();
        Object a = new Object(), b = new Object();
        var first = geometry(1); var replacement = geometry(2);
        registry.publish(a, first, 0);
        registry.publish(a, replacement, 0);
        registry.publish(b, first, 0);
        registry.publish(b, null, 0);
        var changes = registry.drain();
        assertEquals(2, changes.size());
        assertSame(replacement, changes.stream().filter(c -> c.key() == a).findFirst().orElseThrow().geometry());
        assertNull(changes.stream().filter(c -> c.key() == b).findFirst().orElseThrow().geometry());
        registry.publish(a, replacement, 0);
        assertTrue(registry.drain().isEmpty());
        registry.replay();
        assertEquals(List.of(new TerrainGeometryRegistry.Change(a, replacement)), registry.drain());
        registry.publish(a, new TerrainGeometry(2, List.of()), 0);
        assertNull(registry.drain().getFirst().geometry());
        assertEquals(0, registry.size());
    }

    @Test
    void worldChangeRejectsOldEventsAndGpuResetKeepsResidentSnapshot() {
        var registry = new TerrainGeometryRegistry();
        var key = new Object(); var mesh = geometry(0);
        registry.publish(key, mesh, registry.epoch());
        registry.clearPending();
        registry.replay();
        assertSame(mesh, registry.drain().getFirst().geometry());
        long oldEpoch = registry.epoch();
        registry.clearWorld();
        registry.publish(key, mesh, oldEpoch);
        assertEquals(0, registry.size());
        assertTrue(registry.drain().isEmpty());
        registry.publish(key, mesh, registry.epoch());
        assertEquals(1, registry.drain().size());
    }
}
