package com.diliupengyun.differential_create.kinetics.portable;

import java.util.UUID;

/** A retained capability belongs to exactly one connection, including after reconnecting to the same body. */
public record DockingSession(DockingState endpoint, long generation, UUID entity) {
    public boolean valid() { return endpoint.valid(this); }
    public void transferred() { if (valid()) endpoint.transferred(); }

    /** Identity for shared inventory budgets; this never grants permission to access that inventory. */
    public interface Bound {
        Object inventoryIdentity();
        DockingSession session();
    }
    public static Object inventoryIdentity(Object handler) {
        return handler instanceof Bound bound && bound.session().valid() ? bound.inventoryIdentity() : handler;
    }
}
