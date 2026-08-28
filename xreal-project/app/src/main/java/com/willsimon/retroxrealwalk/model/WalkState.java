package com.willsimon.retroxrealwalk.model;

public final class WalkState {
    private static final double MPH_TO_MPS = 0.44704;

    private double speedMph = 2.5;
    private double distanceMeters = 0.0;
    private double elapsedSeconds = 0.0;
    private boolean paused = true;
    private long lastUpdateNanos = 0L;

    public synchronized void update(long nowNanos) {
        if (lastUpdateNanos == 0L) {
            lastUpdateNanos = nowNanos;
            return;
        }
        double dt = (nowNanos - lastUpdateNanos) / 1_000_000_000.0;
        lastUpdateNanos = nowNanos;
        if (dt <= 0.0 || dt > 0.25) return;
        if (!paused) {
            elapsedSeconds += dt;
            distanceMeters += speedMph * MPH_TO_MPS * dt;
        }
    }

    public synchronized void setSpeedMph(double mph) { speedMph = Math.max(0.0, Math.min(6.0, mph)); }
    public synchronized void adjustSpeed(double delta) { setSpeedMph(Math.round((speedMph + delta) * 10.0) / 10.0); }
    public synchronized double getSpeedMph() { return speedMph; }
    public synchronized double getDistanceMeters() { return distanceMeters; }
    public synchronized double getDistanceMiles() { return distanceMeters / 1609.344; }
    public synchronized double getElapsedSeconds() { return elapsedSeconds; }
    public synchronized boolean isPaused() { return paused; }

    public synchronized void togglePaused() {
        paused = !paused;
        lastUpdateNanos = System.nanoTime();
    }

    public synchronized void setPaused(boolean value) {
        paused = value;
        lastUpdateNanos = System.nanoTime();
    }

    public synchronized void reset() {
        distanceMeters = 0.0;
        elapsedSeconds = 0.0;
        paused = true;
        lastUpdateNanos = System.nanoTime();
    }
}
