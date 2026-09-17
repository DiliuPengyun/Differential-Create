package com.diliupengyun.differential_create.kinetics;

/** Finite cylinder torque with a mechanical power budget; no speed-dependent internal loss. */
public final class DriveTorque {
    private DriveTorque() {}

    public static double torque(double stall, double powerLimit, double speed) {
        if (stall == 0 || powerLimit == 0) return 0;
        // While reversing, cylinder pressure opposes existing motion with finite stall torque.
        // Absorbed shaft energy is not returned to the boiler or another engine's power budget.
        if (stall * speed > powerLimit) return powerLimit / speed;
        return stall;
    }

    /** Negative derivative of torque, used only to solve the nonlinear force implicitly. */
    public static double slope(double stall, double powerLimit, double speed) {
        return stall * speed > powerLimit ? (powerLimit / speed) / speed : 0;
    }
}
