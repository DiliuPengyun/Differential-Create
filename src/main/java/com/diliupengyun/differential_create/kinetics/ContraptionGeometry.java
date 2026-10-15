package com.diliupengyun.differential_create.kinetics;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Equal-mass unit cubes. Coordinates are measured from the controller's anchor centre. */
public final class ContraptionGeometry {
	/** Trial balance: one hundredth of the original whole-cube inertia and mass. */
	public static final double CUBE_INERTIA = .0001;
	public static final double CUBE_MASS = 6 * CUBE_INERTIA;
	/** Create converts shaft RPM to blocks/tick by dividing by 512. */
	public static final double LINEAR_RADIUS = 1200 / (512 * 2 * Math.PI);
	/** Air torque for one projected square block at radius one and 64 RPM is 0.01. */
	public static final double AIR_COEFFICIENT = 1 / (100 * 64.0 * 64);
	public record Cell(int x, int y, int z) {
		Cell neighbour(int axis, int sign) {
			return new Cell(x + (axis == 0 ? sign : 0), y + (axis == 1 ? sign : 0), z + (axis == 2 ? sign : 0));
		}
		int coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
	}
	public static final class Shape {
		private final int blocks;
		private final double[] rotationInertia, rotationDrag, translationArea;
		private final Sample[] surface;
		private final Node index;
		private final boolean boxSilhouette;
		private final Map<Wind, Double> directions = new LinkedHashMap<>(16, .75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<Wind, Double> eldest) { return size() > 16; }
		};
		private Shape(int blocks, double[] inertia, double[] drag, double[] area, Sample[] surface, Node index, boolean boxSilhouette) {
			this.blocks = blocks; rotationInertia = inertia; rotationDrag = drag; translationArea = area;
			this.surface = surface; this.index = index;
			this.boxSilhouette = boxSilhouette;
		}
		public int blocks() { return blocks; }
		public double[] rotationInertia() { return rotationInertia; }
		public double[] rotationDrag() { return rotationDrag; }
		public double[] translationArea() { return translationArea; }
		public double linearInertia() { return blocks * CUBE_MASS * LINEAR_RADIUS * LINEAR_RADIUS; }
		public double linearDrag(int axis) { return AIR_COEFFICIENT * translationArea[axis] * Math.pow(LINEAR_RADIUS, 3); }
		/** Uniform translation per radian of the driving shaft; a stabilized body does not spin. */
		public double translatedInertia(double x, double y, double z) { return blocks * CUBE_MASS * (x * x + y * y + z * z); }
		public double translatedDrag(double x, double y, double z) {
			double speed = Math.sqrt(x * x + y * y + z * z);
			if (speed == 0 || blocks == 0) return 0;
			return AIR_COEFFICIENT * speed * speed * speed * projectedArea(x / speed, y / speed, z / speed);
		}
		private synchronized double projectedArea(double x, double y, double z) {
			// Actuators have axial motion. Column unions are exact, including gaps and sealed cavities.
			if (Math.abs(y) < 1e-12 && Math.abs(z) < 1e-12) return translationArea[0];
			if (Math.abs(x) < 1e-12 && Math.abs(z) < 1e-12) return translationArea[1];
			if (Math.abs(x) < 1e-12 && Math.abs(y) < 1e-12) return translationArea[2];
			if (boxSilhouette) return translationArea[0] * Math.abs(x) + translationArea[1] * Math.abs(y) + translationArea[2] * Math.abs(z);
			// Reverse travel sees the same silhouette. Keep only one of the two equivalent directions.
			double main = Math.abs(x) >= Math.abs(y) && Math.abs(x) >= Math.abs(z) ? x : Math.abs(y) >= Math.abs(z) ? y : z;
			if (main < 0) { x = -x; y = -y; z = -z; }
			var wind = new Wind(x, y, z);
			Double cached = directions.get(wind); if (cached != null) return cached;
			double area = 0;
			double inverseX = 1 / x, inverseY = 1 / y, inverseZ = 1 / z;
			for (var sample : surface) {
				double normal = sample.normal == 0 ? x : sample.normal == 1 ? y : z;
				if (normal == 0) continue;
				double out = Math.copySign(1, normal * sample.sign);
				if (!index.blockedInverse(sample, inverseX * out, inverseY * out, inverseZ * out)) area += Math.abs(normal) / 8;
			}
			directions.put(wind, area); return area;
		}
	}
	private record Wind(double x, double y, double z) {}
	private record Sample(Cell owner, int normal, int sign, double x, double y, double z) {
		double coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
	}
	private ContraptionGeometry() {}

	public static Shape measure(Set<Cell> cubes) {
		double[] inertia = new double[3], drag = new double[3], area = new double[3];
		var columns = List.of(new HashSet<Long>(), new HashSet<Long>(), new HashSet<Long>());
		var samples = new ArrayList<Sample>();
		var cells = cubes.toArray(Cell[]::new);
		Node index = cells.length == 0 ? null : new Node(cells, 0, cells.length);
		int boundaryCells = 0;
		double quadrature = 1 / (2 * Math.sqrt(3));
		for (Cell cell : cells) {
			if (cell.x == index.minX + .5 || cell.x == index.maxX - .5 || cell.y == index.minY + .5
				|| cell.y == index.maxY - .5 || cell.z == index.minZ + .5 || cell.z == index.maxZ - .5) boundaryCells++;
			for (int axis = 0; axis < 3; axis++) {
				double radiusSquared = 0;
				for (int k = 0; k < 3; k++) if (k != axis) radiusSquared += Math.pow(cell.coordinate(k), 2);
				inertia[axis] += CUBE_INERTIA + CUBE_MASS * radiusSquared;
				columns.get(axis).add(((long) cell.coordinate((axis + 1) % 3) << 32)
					| (cell.coordinate((axis + 2) % 3) & 0xffffffffL));
			}
			for (int normal = 0; normal < 3; normal++) for (int sign : new int[]{-1, 1}) {
				if (cubes.contains(cell.neighbour(normal, sign))) continue;
				int u = (normal + 1) % 3, v = (normal + 2) % 3;
				for (int su : new int[]{-1, 1}) for (int sv : new int[]{-1, 1}) {
					double[] point = {cell.x, cell.y, cell.z};
					point[normal] += sign * .5;
					point[u] += su * quadrature;
					point[v] += sv * quadrature;
					var sample = new Sample(cell, normal, sign, point[0], point[1], point[2]);
					samples.add(sample);
					for (int axis = 0; axis < 3; axis++) {
						// Local relative wind is the tangent axis cross position. Trace only this body's cubes.
						int a = (axis + 1) % 3, b = (axis + 2) % 3;
						double radiusSquared = point[a] * point[a] + point[b] * point[b];
						double normalVelocity = normal == a ? -point[b] : normal == b ? point[a] : 0;
						if (normalVelocity == 0) continue;
						double out = Math.copySign(1 / Math.sqrt(radiusSquared), normalVelocity * sign);
						double[] wind = new double[3]; wind[a] = -point[b] * out; wind[b] = point[a] * out;
						if (index.blocked(sample, wind[0], wind[1], wind[2])) continue;
						drag[axis] += AIR_COEFFICIENT * radiusSquared * Math.abs(normalVelocity) / 8;
					}
				}
			}
		}
		for (int axis = 0; axis < 3; axis++) area[axis] = columns.get(axis).size();
		double width = index == null ? 0 : index.maxX - index.minX, height = index == null ? 0 : index.maxY - index.minY,
			depth = index == null ? 0 : index.maxZ - index.minZ;
		double shell = width * height * depth - Math.max(0, width - 2) * Math.max(0, height - 2) * Math.max(0, depth - 2);
		return new Shape(cubes.size(), inertia, drag, area, samples.toArray(Sample[]::new), index, boundaryCells == shell);
	}

	/** A cached spatial index avoids walking every intervening empty block along long rays. */
	private static final class Node {
		private static final double EPSILON = 1e-10;
		final double minX, minY, minZ, maxX, maxY, maxZ;
		final Node first, second;
		final Cell[] cells;
		Node(Cell[] source, int from, int to) {
			int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
			int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
			for (int i = from; i < to; i++) for (int axis = 0; axis < 3; axis++) {
				int value = source[i].coordinate(axis); min[axis] = Math.min(min[axis], value); max[axis] = Math.max(max[axis], value);
			}
			minX = min[0] - .5; minY = min[1] - .5; minZ = min[2] - .5;
			maxX = max[0] + .5; maxY = max[1] + .5; maxZ = max[2] + .5;
			if (to - from <= 8) { cells = Arrays.copyOfRange(source, from, to); first = second = null; return; }
			int longest = 0;
			for (int axis = 1; axis < 3; axis++) if ((long) max[axis] - min[axis] > (long) max[longest] - min[longest]) longest = axis;
			int axis = longest;
			Arrays.sort(source, from, to, Comparator.comparingInt(cell -> cell.coordinate(axis)));
			int middle = (from + to) >>> 1;
			cells = null; first = new Node(source, from, middle); second = new Node(source, middle, to);
		}
		boolean blocked(Sample point, double x, double y, double z) {
			return blockedInverse(point, 1 / x, 1 / y, 1 / z);
		}
		boolean blockedInverse(Sample point, double x, double y, double z) {
			if (!intersects(point, x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) return false;
			if (cells == null) return first.blockedInverse(point, x, y, z) || second.blockedInverse(point, x, y, z);
			for (var cell : cells) if (cell != point.owner && intersects(point, x, y, z,
				cell.x - .5, cell.y - .5, cell.z - .5, cell.x + .5, cell.y + .5, cell.z + .5)) return true;
			return false;
		}
		private static boolean intersects(Sample p, double x, double y, double z,
			double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			double near = 0, far = Double.POSITIVE_INFINITY;
			for (int axis = 0; axis < 3; axis++) {
				double start = p.coordinate(axis), inverse = axis == 0 ? x : axis == 1 ? y : z;
				double low = axis == 0 ? minX : axis == 1 ? minY : minZ, high = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
				if (Double.isInfinite(inverse)) { if (start <= low || start >= high) return false; continue; }
				double a = (low - start) * inverse, b = (high - start) * inverse;
				near = Math.max(near, Math.min(a, b)); far = Math.min(far, Math.max(a, b));
				if (far - near <= EPSILON) return false;
			}
			return far > EPSILON;
		}
	}
}
