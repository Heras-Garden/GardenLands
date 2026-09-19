package com.herasgarden.gardenlands.claim;

import java.util.List;
import java.util.UUID;

public record LandClaimRecord(
        UUID id,
        String type,
        String tag,
        String name,
        String ownerType,
        UUID ownerId,
        UUID parentId,
        UUID worldId,
        int minY,
        int maxY,
        boolean fullHeight,
        List<Point> vertices
) {
    public record Point(int x, int z) {}

    public boolean contains(int x, int y, int z) {
        if (!fullHeight && (y < minY || y > maxY)) return false;
        if (vertices.size() == 2) {
            Point a = vertices.get(0);
            Point b = vertices.get(1);
            return x >= Math.min(a.x(), b.x()) && x <= Math.max(a.x(), b.x())
                    && z >= Math.min(a.z(), b.z()) && z <= Math.max(a.z(), b.z());
        }
        if (vertices.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
            Point pi = vertices.get(i);
            Point pj = vertices.get(j);
            boolean crosses = ((pi.z() > z) != (pj.z() > z))
                    && (x < (double) (pj.x() - pi.x()) * (z - pi.z()) / (double) (pj.z() - pi.z()) + pi.x());
            if (crosses) inside = !inside;
        }
        return inside || onBoundary(x, z);
    }

    public double area() {
        if (vertices.size() == 2) {
            Point a = vertices.get(0);
            Point b = vertices.get(1);
            return (Math.abs(a.x() - b.x()) + 1.0) * (Math.abs(a.z() - b.z()) + 1.0);
        }
        double sum = 0.0;
        for (int i = 0; i < vertices.size(); i++) {
            Point a = vertices.get(i);
            Point b = vertices.get((i + 1) % vertices.size());
            sum += (double) a.x() * b.z() - (double) b.x() * a.z();
        }
        return Math.abs(sum) / 2.0;
    }

    public boolean is(String claimType) {
        return claimType != null && claimType.equalsIgnoreCase(type);
    }

    public boolean tagged(String claimTag) {
        return claimTag != null && tag != null && claimTag.equalsIgnoreCase(tag);
    }

    private boolean onBoundary(int x, int z) {
        for (int i = 0; i < vertices.size(); i++) {
            Point a = vertices.get(i);
            Point b = vertices.get((i + 1) % vertices.size());
            long cross = (long) (x - a.x()) * (b.z() - a.z()) - (long) (z - a.z()) * (b.x() - a.x());
            if (cross == 0 && x >= Math.min(a.x(), b.x()) && x <= Math.max(a.x(), b.x())
                    && z >= Math.min(a.z(), b.z()) && z <= Math.max(a.z(), b.z())) return true;
        }
        return false;
    }
}
