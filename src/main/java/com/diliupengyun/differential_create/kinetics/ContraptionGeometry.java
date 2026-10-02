package com.diliupengyun.differential_create.kinetics;

import java.util.Set;

/** Equal-mass unit cubes. Coordinates are measured from the controller's anchor centre. */
public final class ContraptionGeometry {
	/** Saved controller summaries identify the mass/air calibration used to produce them. */
	public static final int CALIBRATION = 1;
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
	public record Shape(int blocks, double[] rotationInertia, double[] rotationDrag, double[] translationArea) {
		public double linearInertia() { return blocks * CUBE_MASS * LINEAR_RADIUS * LINEAR_RADIUS; }
		public double linearDrag(int axis) { return AIR_COEFFICIENT * translationArea[axis] * Math.pow(LINEAR_RADIUS, 3); }
		/** Uniform translation per radian of the driving shaft; a stabilized body does not spin. */
		public double translatedInertia(double x, double y, double z) { return blocks * CUBE_MASS * (x * x + y * y + z * z); }
		public double translatedDrag(double x, double y, double z) {
			return AIR_COEFFICIENT * (x * x + y * y + z * z)
				* (translationArea[0] * Math.abs(x) + translationArea[1] * Math.abs(y) + translationArea[2] * Math.abs(z));
		}
	}
	private ContraptionGeometry() {}

	public static Shape measure(Set<Cell> cubes) {
		double[] inertia = new double[3], drag = new double[3], area = new double[3];
		double quadrature = 1 / (2 * Math.sqrt(3));
		for (Cell cell : cubes) {
			for (int axis = 0; axis < 3; axis++) {
				double radiusSquared = 0;
				for (int k = 0; k < 3; k++) if (k != axis) radiusSquared += Math.pow(cell.coordinate(k), 2);
				inertia[axis] += CUBE_INERTIA + CUBE_MASS * radiusSquared;
			}
			for (int normal = 0; normal < 3; normal++) for (int sign : new int[]{-1, 1}) {
				if (cubes.contains(cell.neighbour(normal, sign))) continue;
				area[normal] += .5; // The two opposing sides describe the same projected area.
				int u = (normal + 1) % 3, v = (normal + 2) % 3;
				for (int su : new int[]{-1, 1}) for (int sv : new int[]{-1, 1}) {
					double[] point = {cell.x, cell.y, cell.z};
					point[normal] += sign * .5;
					point[u] += su * quadrature;
					point[v] += sv * quadrature;
					for (int axis = 0; axis < 3; axis++) {
						// Velocity per radian is axis cross position. Integrate exposed faces,
						// including the cube's own radius, rather than only its centre point.
						int a = (axis + 1) % 3, b = (axis + 2) % 3;
						double radiusSquared = point[a] * point[a] + point[b] * point[b];
						double normalVelocity = normal == a ? -point[b] : normal == b ? point[a] : 0;
						drag[axis] += AIR_COEFFICIENT * radiusSquared * Math.abs(normalVelocity) / 8;
					}
				}
			}
		}
		return new Shape(cubes.size(), inertia, drag, area);
	}
}
