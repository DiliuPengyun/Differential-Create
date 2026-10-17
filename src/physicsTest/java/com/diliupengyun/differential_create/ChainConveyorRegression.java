package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.ChainConveyorTravel;

/** First-passage checks independent of wrapped end coordinates. */
final class ChainConveyorRegression {
    static void run() {
        int checks = 0;
        for (double start : new double[]{0, 1, 90, 179, 180, 359})
            for (double target : new double[]{0, 1, 90, 179, 180, 359})
                for (int sign : new int[]{-1, 1}) {
                    require(!ChainConveyorTravel.crosses(start, target, 0), "stationary parcels cross no ports");
                    for (double turns : new double[]{1, 2, 32, 1e20}) {
                        require(ChainConveyorTravel.crosses(start, target, sign * 360 * turns), "every full revolution visits every port");
                        checks++;
                    }
                    for (double arc : new double[]{.0001, 1, 45, 179, 180, 217, 359, 434, 1738}) {
                        boolean once = ChainConveyorTravel.crosses(start, target, sign * arc);
                        boolean split = ChainConveyorTravel.crosses(start, target, sign * arc / 2)
                            || ChainConveyorTravel.crosses(ChainConveyorTravel.wrap(start + sign * arc / 2), target, sign * arc / 2);
                        require(once == split, "splitting a path cannot add or remove a port encounter");
                        require(once == ChainConveyorTravel.crosses(-start, -target, -sign * arc), "reversing coordinates preserves first passage");
                        checks += 2;
                    }
                }
        require(ChainConveyorTravel.crosses(0, 180, ChainConveyorTravel.degrees(2048)), "original 2048 RPM reproduction");
        require(ChainConveyorTravel.crosses(54, 55, ChainConveyorTravel.degrees(1024)), "original branch reproduction");
        require(!ChainConveyorTravel.crosses(0, 180, Double.NaN), "invalid travel cannot deliver a parcel");
        System.out.println("Chain conveyor regression: " + checks + " first-passage checks passed");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
