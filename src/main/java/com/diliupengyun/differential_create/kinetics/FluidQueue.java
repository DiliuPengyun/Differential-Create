package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Ordered, incompressible parcels. Neither a new vacuum nor implicit air refill. */
public final class FluidQueue<T> {
    public record Part<T>(T identity, double amount) {
        public Part {
            if (!(amount > 0) || !Double.isFinite(amount)) throw new IllegalArgumentException("Invalid parcel volume");
        }
    }
    private final ArrayDeque<Part<T>> parts = new ArrayDeque<>();
    private final BiPredicate<T, T> same;
    private double volume;
    public FluidQueue(BiPredicate<T, T> same) { this.same = same; }
    public double volume() { return volume; }
    public int size() { return parts.size(); }
    public List<Part<T>> parts() { return List.copyOf(parts); }
    public Part<T> peek(boolean front) { return front ? parts.peekFirst() : parts.peekLast(); }
    public double volume(Predicate<T> accepts) {
        double sum = 0, error = 0;
        for (var part : parts) if (accepts.test(part.identity)) {
            double adjusted = part.amount - error, next = sum + adjusted;
            error = (next - sum) - adjusted; sum = next;
        }
        return sum;
    }
    public void add(T identity, double amount, boolean front) {
        if (amount == 0) return;
        var added = new Part<>(identity, amount);
        var end = peek(front);
        if (end != null && same.test(end.identity, identity)) {
            if (front) parts.removeFirst(); else parts.removeLast();
            added = new Part<>(end.identity, end.amount + amount);
        }
        if (front) parts.addFirst(added); else parts.addLast(added);
        volume += amount;
    }
    /** Arrival order: the first item travels furthest into the receiver. */
    public void receive(List<Part<T>> packet, boolean front) {
        for (var p : packet) add(p.identity, p.amount, front);
    }
    /** Simultaneous junction arrivals retain FIFO within every input. Rotate
     * between input heads and merge matching heads, rather than concatenating
     * duplicate complete streams that would multiply their interfaces in a loop. */
    public void receiveJoined(List<List<Part<T>>> incoming) {
        int[] at = new int[incoming.size()]; int remaining = 0, lead = 0;
        for (var packet : incoming) remaining += packet.size();
        while (remaining > 0) {
            while (at[lead] == incoming.get(lead).size()) lead = (lead + 1) % incoming.size();
            T identity = incoming.get(lead).get(at[lead]).identity;
            double amount = 0, error = 0;
            for (int n = 0; n < incoming.size(); n++) if (at[n] < incoming.get(n).size()
                && same.test(identity, incoming.get(n).get(at[n]).identity)) {
                double increment = incoming.get(n).get(at[n]++).amount - error;
                double next = amount + increment; error = (next - amount) - increment; amount = next; remaining--;
            }
            add(identity, amount, false); lead = (lead + 1) % incoming.size();
        }
    }
    public List<Part<T>> preview(double amount, boolean front) {
        if (!(amount >= 0) || !Double.isFinite(amount)) throw new IllegalArgumentException("Invalid parcel request");
        var result = new ArrayList<Part<T>>();
        var iterator = front ? parts.iterator() : parts.descendingIterator();
        while (amount > 0 && iterator.hasNext()) {
            var part = iterator.next(); double taken = Math.min(amount, part.amount);
            result.add(new Part<>(part.identity, taken)); amount -= taken;
        }
        return result;
    }
    /** The ordered prefix a port can pass; later accepted parcels cannot overtake a rejection. */
    public double prefixVolume(double maximum, boolean front, Predicate<T> accepts) {
        if (!(maximum >= 0) || !Double.isFinite(maximum)) throw new IllegalArgumentException("Invalid parcel request");
        double allowed = 0;
        var iterator = front ? parts.iterator() : parts.descendingIterator();
        while (allowed < maximum && iterator.hasNext()) {
            var part = iterator.next();
            if (!accepts.test(part.identity)) break;
            allowed += Math.min(maximum - allowed, part.amount);
        }
        return allowed;
    }
    public List<Part<T>> take(double amount, boolean front) {
        if (!(amount >= 0) || !Double.isFinite(amount) || amount > volume + 64 * Math.ulp(Math.max(1, volume)))
            throw new IllegalArgumentException("Parcel withdrawal exceeds actual stock: requested=" + amount + ", available=" + volume);
        amount = Math.min(amount, volume);
        var result = new ArrayList<Part<T>>();
        double takenTotal = 0;
        while (amount > 0 && !parts.isEmpty()) {
            var part = front ? parts.removeFirst() : parts.removeLast();
            double taken = Math.min(amount, part.amount);
            // Finish a face crossing at arithmetic roundoff by transferring
            // the complete owned parcel, never deleting its identity or stock.
            if (part.amount >= amount && part.amount - amount <= 8 * Math.ulp(Math.max(1, part.amount))) taken = part.amount;
            result.add(new Part<>(part.identity, taken)); takenTotal += taken; amount -= taken;
            // Preserve every positive remainder; no gameplay-sized zero cutoff.
            double left = part.amount - taken;
            if (left > 0) {
                var remainder = new Part<>(part.identity, left);
                if (front) parts.addFirst(remainder); else parts.addLast(remainder);
            }
        }
        volume = parts.isEmpty() ? 0 : Math.max(0, volume - takenTotal);
        return result;
    }
    public FluidQueue<T> copy() {
        var result = new FluidQueue<T>(same); result.parts.addAll(parts); result.volume = volume; return result;
    }
    /** Split each FIFO parcel between simultaneous outlets without mixing identities. */
    public static <T> List<List<Part<T>>> distribute(List<Part<T>> packet, double[] rates) {
        var result = new ArrayList<List<Part<T>>>(rates.length); double total = 0;
        for (double rate : rates) {
            if (!(rate > 0) || !Double.isFinite(rate)) throw new IllegalArgumentException("Invalid outlet rate");
            total += rate; result.add(new ArrayList<>());
        }
        if (!Double.isFinite(total) || rates.length == 0) throw new IllegalArgumentException("No finite outlets");
        for (var part : packet) {
            double left = part.amount;
            for (int n = 0; n < rates.length; n++) {
                double amount = n == rates.length - 1 ? left : Math.min(left, part.amount * (rates[n] / total));
                if (amount > 0) result.get(n).add(new Part<>(part.identity, amount));
                left -= amount;
            }
        }
        return result;
    }
    /** One phase or one interface splits symmetrically. A longer FIFO packet
     * is allocated in contiguous windows, with rotating outlet priority. Each
     * outlet still gets its prescribed volume and each input identity stays
     * ordered; an entire microscopic pattern is never copied to every outlet. */
    public List<List<Part<T>>> distributeBounded(List<Part<T>> packet, double[] rates, int first) {
        if (packet.size() <= 2) return distribute(packet, rates);
        var result = new ArrayList<List<Part<T>>>(rates.length); double total = 0;
        for (double rate : rates) {
            if (!(rate > 0) || !Double.isFinite(rate)) throw new IllegalArgumentException("Invalid outlet rate");
            total += rate; result.add(null);
        }
        if (!Double.isFinite(total) || rates.length == 0) throw new IllegalArgumentException("No finite outlets");
        var staged = new FluidQueue<T>(same); staged.receive(packet, false); double original = staged.volume();
        first = Math.floorMod(first, rates.length);
        for (int step = 0; step < rates.length; step++) {
            int outlet = (first + step) % rates.length;
            double amount = step == rates.length - 1 ? staged.volume() : Math.min(staged.volume(), original * (rates[outlet] / total));
            result.set(outlet, staged.take(amount, true));
        }
        return result;
    }
}
