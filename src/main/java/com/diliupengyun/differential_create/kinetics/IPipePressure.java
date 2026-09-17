package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.fluids.pump.PumpBlockEntity;

public interface IPipePressure {
    void differentialcreate$addPumpPressure(PumpBlockEntity pump, boolean inbound, float pressure);
}
