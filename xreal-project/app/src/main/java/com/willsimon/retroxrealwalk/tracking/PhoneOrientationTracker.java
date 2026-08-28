package com.willsimon.retroxrealwalk.tracking;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public final class PhoneOrientationTracker implements SensorEventListener {
    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final HeadPose headPose;
    private final float[] quaternion = new float[4];

    public PhoneOrientationTracker(Context context, HeadPose headPose) {
        this.headPose = headPose;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor candidate = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (candidate == null) candidate = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = candidate;
    }

    public void start() {
        if (sensor != null) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getQuaternionFromVector(quaternion, event.values);
        headPose.setPhoneQuaternion(quaternion[0], quaternion[1], quaternion[2], quaternion[3], System.nanoTime());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
