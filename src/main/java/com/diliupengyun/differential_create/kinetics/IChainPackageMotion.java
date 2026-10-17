package com.diliupengyun.differential_create.kinetics;

/** Transient tick ownership prevents a transferred parcel receiving a second tick. */
public interface IChainPackageMotion {
    final class State {
        public long tick = Long.MIN_VALUE;
        public boolean directionKnown;
        public boolean reversed;
    }
    State differentialcreate$motion();
}
