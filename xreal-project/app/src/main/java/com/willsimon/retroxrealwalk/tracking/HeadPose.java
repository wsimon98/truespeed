package com.willsimon.retroxrealwalk.tracking;

public final class HeadPose {
    private final float[] xreal = new float[]{1f, 0f, 0f, 0f};
    private final float[] phone = new float[]{1f, 0f, 0f, 0f};
    private final float[] recenterInv = new float[]{1f, 0f, 0f, 0f};
    private long xrealTimestampNanos = 0L;
    private long phoneTimestampNanos = 0L;

    public synchronized void setXrealQuaternion(float w, float x, float y, float z, long timestampNanos) {
        normalizeInto(xreal, w, x, y, z);
        xrealTimestampNanos = timestampNanos;
    }

    public synchronized void setPhoneQuaternion(float w, float x, float y, float z, long timestampNanos) {
        normalizeInto(phone, w, x, y, z);
        phoneTimestampNanos = timestampNanos;
    }

    public synchronized boolean isXrealActive() {
        return System.nanoTime() - xrealTimestampNanos < 1_500_000_000L;
    }

    public synchronized boolean hasPhonePose() {
        return phoneTimestampNanos != 0L;
    }

    public synchronized String getSourceLabel() {
        if (isXrealActive()) return "XREAL AIR IMU";
        if (hasPhonePose()) return "PHONE SENSOR FALLBACK";
        return "NO TRACKING";
    }

    public synchronized void recenter() {
        float[] q = activeQuaternion();
        recenterInv[0] = q[0];
        recenterInv[1] = -q[1];
        recenterInv[2] = -q[2];
        recenterInv[3] = -q[3];
    }

    public synchronized void fillInverseRotationMatrix(float[] out16) {
        float[] current = activeQuaternion();
        float[] relative = multiply(recenterInv, current);
        float w = relative[0];
        float x = -relative[1];
        float y = -relative[2];
        float z = -relative[3];
        quaternionToMatrix(out16, w, x, y, z);
    }

    public synchronized float[] getRelativeEulerDegrees() {
        float[] current = activeQuaternion();
        float[] q = multiply(recenterInv, current);
        float w = q[0], x = q[1], y = q[2], z = q[3];

        double sinr = 2.0 * (w * x + y * z);
        double cosr = 1.0 - 2.0 * (x * x + y * y);
        double roll = Math.atan2(sinr, cosr);

        double sinp = 2.0 * (w * y - z * x);
        double pitch;
        if (Math.abs(sinp) >= 1.0) pitch = Math.copySign(Math.PI / 2.0, sinp);
        else pitch = Math.asin(sinp);

        double siny = 2.0 * (w * z + x * y);
        double cosy = 1.0 - 2.0 * (y * y + z * z);
        double yaw = Math.atan2(siny, cosy);
        return new float[]{(float)Math.toDegrees(yaw), (float)Math.toDegrees(pitch), (float)Math.toDegrees(roll)};
    }

    private float[] activeQuaternion() {
        if (System.nanoTime() - xrealTimestampNanos < 1_500_000_000L) return xreal.clone();
        return phone.clone();
    }

    private static void normalizeInto(float[] target, float w, float x, float y, float z) {
        float len = (float)Math.sqrt(w*w + x*x + y*y + z*z);
        if (len < 0.000001f) {
            target[0] = 1f; target[1] = 0f; target[2] = 0f; target[3] = 0f;
            return;
        }
        target[0] = w / len;
        target[1] = x / len;
        target[2] = y / len;
        target[3] = z / len;
    }

    private static float[] multiply(float[] a, float[] b) {
        return new float[]{
                a[0]*b[0] - a[1]*b[1] - a[2]*b[2] - a[3]*b[3],
                a[0]*b[1] + a[1]*b[0] + a[2]*b[3] - a[3]*b[2],
                a[0]*b[2] - a[1]*b[3] + a[2]*b[0] + a[3]*b[1],
                a[0]*b[3] + a[1]*b[2] - a[2]*b[1] + a[3]*b[0]
        };
    }

    private static void quaternionToMatrix(float[] m, float w, float x, float y, float z) {
        float xx = x*x, yy = y*y, zz = z*z;
        float xy = x*y, xz = x*z, yz = y*z;
        float wx = w*x, wy = w*y, wz = w*z;
        m[0] = 1f - 2f*(yy + zz); m[4] = 2f*(xy - wz);       m[8] = 2f*(xz + wy);        m[12] = 0f;
        m[1] = 2f*(xy + wz);       m[5] = 1f - 2f*(xx + zz); m[9] = 2f*(yz - wx);        m[13] = 0f;
        m[2] = 2f*(xz - wy);       m[6] = 2f*(yz + wx);       m[10] = 1f - 2f*(xx + yy); m[14] = 0f;
        m[3] = 0f;                 m[7] = 0f;                  m[11] = 0f;                  m[15] = 1f;
    }
}
