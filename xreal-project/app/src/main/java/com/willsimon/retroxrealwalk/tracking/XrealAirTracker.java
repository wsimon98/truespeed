package com.willsimon.retroxrealwalk.tracking;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class XrealAirTracker {
    public interface Listener {
        void onStatus(String status);
        void onConnected(boolean connected);
    }

    private static final int XREAL_VENDOR_ID = 0x3318;
    private static final int XREAL_AIR_PRODUCT_ID = 0x0424;
    private static final String ACTION_USB_PERMISSION = "com.willsimon.retroxrealwalk.USB_PERMISSION";

    private static final float GYRO_SCALE_DPS = 2000f / 8388608f;
    private static final float ACCEL_SCALE_G = 16f / 8388608f;

    private final Context context;
    private final UsbManager usbManager;
    private final HeadPose headPose;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final OrientationEstimator estimator = new OrientationEstimator();

    private UsbDeviceConnection connection;
    private UsbInterface imuInterface;
    private UsbEndpoint imuIn;
    private UsbEndpoint imuOut;
    private Thread readerThread;
    private boolean receiverRegistered = false;

    public XrealAirTracker(Context context, HeadPose headPose, Listener listener) {
        this.context = context.getApplicationContext();
        this.headPose = headPose;
        this.listener = listener;
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    public void start() {
        registerReceiver();
        connect();
    }

    public void stop() {
        stopReader();
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
    }

    public void reconnect() {
        stopReader();
        connect();
    }

    private void connect() {
        UsbDevice device = findOriginalAir();
        if (device == null) {
            listener.onConnected(false);
            listener.onStatus("Original XREAL Air USB IMU not found. Phone sensor fallback is active.");
            return;
        }

        if (!usbManager.hasPermission(device)) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    context, 771, new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
            usbManager.requestPermission(device, permissionIntent);
            listener.onStatus("XREAL Air found. Waiting for USB permission.");
            return;
        }
        openDevice(device);
    }

    private UsbDevice findOriginalAir() {
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            if (device.getVendorId() == XREAL_VENDOR_ID && device.getProductId() == XREAL_AIR_PRODUCT_ID) {
                return device;
            }
        }
        return null;
    }

    private void openDevice(UsbDevice device) {
        stopReader();

        UsbInterface foundInterface = null;
        UsbEndpoint foundIn = null;
        UsbEndpoint foundOut = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getId() != 3 || candidate.getAlternateSetting() != 0) continue;
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint endpoint = candidate.getEndpoint(e);
                if (endpoint.getAddress() == 0x84) foundIn = endpoint;
                if (endpoint.getAddress() == 0x05) foundOut = endpoint;
            }
            if (foundIn != null && foundOut != null) {
                foundInterface = candidate;
                break;
            }
        }

        if (foundInterface == null || foundIn == null || foundOut == null) {
            listener.onConnected(false);
            listener.onStatus("XREAL Air detected, but its IMU endpoints were not found.");
            return;
        }

        UsbDeviceConnection opened = usbManager.openDevice(device);
        if (opened == null) {
            listener.onConnected(false);
            listener.onStatus("Could not open the XREAL Air USB connection.");
            return;
        }
        if (!opened.claimInterface(foundInterface, true)) {
            opened.close();
            listener.onConnected(false);
            listener.onStatus("Could not claim the XREAL Air IMU interface.");
            return;
        }

        connection = opened;
        imuInterface = foundInterface;
        imuIn = foundIn;
        imuOut = foundOut;
        estimator.reset();

        byte[] startImu = new byte[]{
                (byte)0xAA, (byte)0xC5, (byte)0xD1, 0x21, 0x42, 0x04, 0x00, 0x19, 0x01
        };
        int sent = connection.bulkTransfer(imuOut, startImu, startImu.length, 300);
        if (sent < 0) {
            listener.onStatus("XREAL Air opened, but the IMU start command failed.");
            closeConnection();
            return;
        }

        running.set(true);
        readerThread = new Thread(this::readLoop, "XrealAirImu");
        readerThread.start();
        listener.onConnected(true);
        listener.onStatus("XREAL Air connected. 3DoF head tracking active.");
    }

    private void readLoop() {
        byte[] packet = new byte[64];
        long previousUptime = 0L;
        int badPackets = 0;

        while (running.get() && connection != null) {
            int read = connection.bulkTransfer(imuIn, packet, packet.length, 250);
            if (read < 0) {
                if (running.get()) listener.onStatus("XREAL Air IMU stream stopped. Falling back to phone sensor.");
                break;
            }
            if (read != 64 || !looksLikeOriginalAirPacket(packet)) {
                badPackets++;
                if (badPackets == 20) {
                    listener.onStatus("XREAL Air is sending an unfamiliar IMU packet format.");
                }
                continue;
            }
            badPackets = 0;

            long uptime = readLittleEndianLong(packet, 4);
            if (previousUptime == 0L) {
                previousUptime = uptime;
                continue;
            }
            float dt = (uptime - previousUptime) / 1_000_000_000f;
            previousUptime = uptime;
            if (dt <= 0f || dt > 0.1f) continue;

            int gxRaw = readSigned24(packet, 18);
            int gyRaw = readSigned24(packet, 21);
            int gzRaw = readSigned24(packet, 24);
            int axRaw = readSigned24(packet, 33);
            int ayRaw = readSigned24(packet, 36);
            int azRaw = readSigned24(packet, 39);

            float gx = gxRaw * GYRO_SCALE_DPS;
            float gy = gyRaw * GYRO_SCALE_DPS;
            float gz = gzRaw * GYRO_SCALE_DPS;
            float ax = axRaw * ACCEL_SCALE_G;
            float ay = ayRaw * ACCEL_SCALE_G;
            float az = azRaw * ACCEL_SCALE_G;

            estimator.update(gx, gy, gz, ax, ay, az, dt);
            float[] q = estimator.getQuaternion();
            headPose.setXrealQuaternion(q[0], q[1], q[2], q[3], System.nanoTime());
        }

        running.set(false);
        listener.onConnected(false);
    }

    private static boolean looksLikeOriginalAirPacket(byte[] p) {
        return p[0] == 0x01 && p[1] == 0x02 &&
                (p[12] & 0xFF) == 0xA0 && (p[13] & 0xFF) == 0x0F &&
                (p[27] & 0xFF) == 0x20;
    }

    private static int readSigned24(byte[] p, int offset) {
        int value = (p[offset] & 0xFF) | ((p[offset + 1] & 0xFF) << 8) | ((p[offset + 2] & 0xFF) << 16);
        if ((value & 0x00800000) != 0) value |= 0xFF000000;
        return value;
    }

    private static long readLittleEndianLong(byte[] p, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) value |= ((long)p[offset + i] & 0xFFL) << (8 * i);
        return value;
    }

    private void stopReader() {
        running.set(false);
        if (readerThread != null) {
            try { readerThread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            readerThread = null;
        }
        closeConnection();
        listener.onConnected(false);
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                if (imuInterface != null) connection.releaseInterface(imuInterface);
            } catch (Exception ignored) { }
            try { connection.close(); } catch (Exception ignored) { }
        }
        connection = null;
        imuInterface = null;
        imuIn = null;
        imuOut = null;
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device;
                if (Build.VERSION.SDK_INT >= 33) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    openDevice(device);
                } else {
                    listener.onConnected(false);
                    listener.onStatus("USB permission was denied. Phone sensor fallback is active.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                connect();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                stopReader();
                listener.onStatus("XREAL Air disconnected. Phone sensor fallback is active.");
            }
        }
    };

    private static final class OrientationEstimator {
        private float q0 = 1f, q1 = 0f, q2 = 0f, q3 = 0f;
        private float integralX = 0f, integralY = 0f, integralZ = 0f;

        void reset() {
            q0 = 1f; q1 = q2 = q3 = 0f;
            integralX = integralY = integralZ = 0f;
        }

        void update(float gxDps, float gyDps, float gzDps, float ax, float ay, float az, float dt) {
            float gx = (float)Math.toRadians(gxDps);
            float gy = (float)Math.toRadians(gyDps);
            float gz = (float)Math.toRadians(gzDps);

            float norm = (float)Math.sqrt(ax*ax + ay*ay + az*az);
            if (norm > 0.0001f && norm < 4f) {
                ax /= norm; ay /= norm; az /= norm;

                float vx = 2f * (q1*q3 - q0*q2);
                float vy = 2f * (q0*q1 + q2*q3);
                float vz = q0*q0 - q1*q1 - q2*q2 + q3*q3;

                float ex = ay*vz - az*vy;
                float ey = az*vx - ax*vz;
                float ez = ax*vy - ay*vx;

                final float kp = 1.4f;
                final float ki = 0.03f;
                integralX += ki * ex * dt;
                integralY += ki * ey * dt;
                integralZ += ki * ez * dt;

                gx += kp*ex + integralX;
                gy += kp*ey + integralY;
                gz += kp*ez + integralZ;
            }

            float halfDt = 0.5f * dt;
            float nq0 = q0 + (-q1*gx - q2*gy - q3*gz) * halfDt;
            float nq1 = q1 + ( q0*gx + q2*gz - q3*gy) * halfDt;
            float nq2 = q2 + ( q0*gy - q1*gz + q3*gx) * halfDt;
            float nq3 = q3 + ( q0*gz + q1*gy - q2*gx) * halfDt;

            float qNorm = (float)Math.sqrt(nq0*nq0 + nq1*nq1 + nq2*nq2 + nq3*nq3);
            if (qNorm > 0.000001f) {
                q0 = nq0/qNorm; q1 = nq1/qNorm; q2 = nq2/qNorm; q3 = nq3/qNorm;
            }
        }

        float[] getQuaternion() {
            return new float[]{q0, q1, q2, q3};
        }
    }
}
