package com.willsimon.retroxrealwalk.render;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.willsimon.retroxrealwalk.model.WalkState;
import com.willsimon.retroxrealwalk.tracking.HeadPose;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class RetroRenderer implements GLSurfaceView.Renderer {
    private static final float EYE_HEIGHT = 1.65f;
    private static final float CHUNK = 4.0f;
    private static final int VISIBLE_CHUNKS = 48;

    private final WalkState walkState;
    private final HeadPose headPose;
    private final LineBatch lines = new LineBatch(18000);
    private final List<StarSystem> starSystems = new ArrayList<>();
    private final List<Star> backgroundStars = new ArrayList<>();
    private final Random shootingRandom = new Random(0x1984BEEF);

    private final float[] projection = new float[16];
    private final float[] translation = new float[16];
    private final float[] headInverse = new float[16];
    private final float[] view = new float[16];
    private final float[] mvp = new float[16];

    private int program;
    private int aPosition;
    private int aColor;
    private int uMvp;

    private long nextShootingStarNanos = 0L;
    private long shootingStartNanos = -1L;
    private ShootingStar currentShootingStar;

    public RetroRenderer(WalkState walkState, HeadPose headPose) {
        this.walkState = walkState;
        this.headPose = headPose;
        buildBackgroundStars();
        buildStarSystems();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glLineWidth(2f);
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aColor = GLES20.glGetAttribLocation(program, "aColor");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        scheduleNextShootingStar(System.nanoTime());
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspect = height == 0 ? 1f : (float) width / (float) height;
        Matrix.perspectiveM(projection, 0, 66f, aspect, 0.05f, 240f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        walkState.update(now);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        lines.reset();

        addTrackAndScenery((float) walkState.getDistanceMeters());
        addStars();
        addShootingStar(now);

        Matrix.setIdentityM(translation, 0);
        Matrix.translateM(translation, 0, 0f, -EYE_HEIGHT, 0f);
        headPose.fillInverseRotationMatrix(headInverse);
        Matrix.multiplyMM(view, 0, headInverse, 0, translation, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0);

        drawBatch();
    }

    private void drawBatch() {
        if (lines.vertexCount == 0) return;
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        lines.buffer.position(0);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 7 * 4, lines.buffer);
        lines.buffer.position(3);
        GLES20.glEnableVertexAttribArray(aColor);
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 7 * 4, lines.buffer);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lines.vertexCount);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aColor);
    }

    private void addTrackAndScenery(float travelMeters) {
        float phase = travelMeters % CHUNK;
        long baseChunk = (long)Math.floor(travelMeters / CHUNK);

        float cyanR = 0.02f, cyanG = 0.95f, cyanB = 1.0f;
        float dimR = 0.02f, dimG = 0.34f, dimB = 0.38f;

        for (int i = 0; i < VISIBLE_CHUNKS; i++) {
            float zFront = phase - 2.0f - i * CHUNK;
            float zBack = zFront - CHUNK;
            float fade = clamp01(1f - i / (float)VISIBLE_CHUNKS * 0.82f);

            lines.add(-1.55f, 0f, zFront, -1.55f, 0f, zBack, cyanR, cyanG, cyanB, 0.88f * fade);
            lines.add( 1.55f, 0f, zFront,  1.55f, 0f, zBack, cyanR, cyanG, cyanB, 0.88f * fade);
            lines.add(-1.55f, 0f, zBack,   1.55f, 0f, zBack, dimR, dimG, dimB, 0.58f * fade);

            if ((baseChunk + i) % 2 == 0) {
                lines.add(-0.12f, 0.006f, zFront - 0.6f, 0.12f, 0.006f, zFront - 1.5f,
                        0.25f, 1.0f, 0.42f, 0.75f * fade);
            }

            long logicalChunk = baseChunk + i;
            addSceneryForChunk(logicalChunk, zFront - CHUNK * 0.55f, fade);
        }

        for (int lane = 2; lane <= 8; lane++) {
            float x = lane * 2.5f;
            lines.add(-x, 0f, 1.5f, -x, 0f, -188f, 0.08f, 0.20f, 0.24f, 0.26f);
            lines.add( x, 0f, 1.5f,  x, 0f, -188f, 0.08f, 0.20f, 0.24f, 0.26f);
        }
    }

    private void addSceneryForChunk(long chunk, float z, float fade) {
        int h = hash32(chunk);
        for (int side = -1; side <= 1; side += 2) {
            int local = hash32(h * 31L + side * 997L);
            if ((local & 7) == 0) continue;

            float x = side * (3.8f + ((local >>> 8) & 7) * 0.48f);
            float size = 0.65f + ((local >>> 12) & 7) * 0.10f;
            float height = 1.0f + ((local >>> 16) & 15) * 0.16f;
            int type = Math.abs(local) % 5;
            float[] c = palette(Math.abs(local >>> 4) % 4, fade);

            switch (type) {
                case 0: cube(x, size * 0.5f, z, size, size, size, c); break;
                case 1: pyramid(x, 0f, z, size * 1.35f, height, c); break;
                case 2: cube(x, height * 0.5f, z, size * 0.75f, height, size * 0.75f, c); break;
                case 3: arch(x, z, size, height, c); break;
                default: wall(x, z, side, 1.1f + size, height * 0.75f, c); break;
            }
        }
    }

    private void cube(float cx, float cy, float cz, float sx, float sy, float sz, float[] c) {
        float x0 = cx - sx/2, x1 = cx + sx/2;
        float y0 = cy - sy/2, y1 = cy + sy/2;
        float z0 = cz - sz/2, z1 = cz + sz/2;
        edgeBox(x0,y0,z0,x1,y1,z1,c);
    }

    private void edgeBox(float x0,float y0,float z0,float x1,float y1,float z1,float[] c) {
        line(x0,y0,z0,x1,y0,z0,c); line(x1,y0,z0,x1,y0,z1,c); line(x1,y0,z1,x0,y0,z1,c); line(x0,y0,z1,x0,y0,z0,c);
        line(x0,y1,z0,x1,y1,z0,c); line(x1,y1,z0,x1,y1,z1,c); line(x1,y1,z1,x0,y1,z1,c); line(x0,y1,z1,x0,y1,z0,c);
        line(x0,y0,z0,x0,y1,z0,c); line(x1,y0,z0,x1,y1,z0,c); line(x1,y0,z1,x1,y1,z1,c); line(x0,y0,z1,x0,y1,z1,c);
    }

    private void pyramid(float cx, float y, float cz, float size, float height, float[] c) {
        float h = size/2;
        float z0 = cz-h, z1 = cz+h, x0 = cx-h, x1 = cx+h;
        line(x0,y,z0,x1,y,z0,c); line(x1,y,z0,x1,y,z1,c); line(x1,y,z1,x0,y,z1,c); line(x0,y,z1,x0,y,z0,c);
        line(x0,y,z0,cx,y+height,cz,c); line(x1,y,z0,cx,y+height,cz,c);
        line(x1,y,z1,cx,y+height,cz,c); line(x0,y,z1,cx,y+height,cz,c);
    }

    private void arch(float cx, float cz, float size, float height, float[] c) {
        float gap = size * 0.72f;
        float post = Math.max(0.18f, size * 0.22f);
        cube(cx-gap, height*0.5f, cz, post, height, post, c);
        cube(cx+gap, height*0.5f, cz, post, height, post, c);
        cube(cx, height, cz, gap*2f+post, post, post, c);
    }

    private void wall(float cx, float cz, int side, float width, float height, float[] c) {
        float depth = 0.16f;
        float x0 = cx - (side < 0 ? width : depth)/2f;
        float x1 = cx + (side < 0 ? width : depth)/2f;
        float z0 = cz - (side < 0 ? depth : width)/2f;
        float z1 = cz + (side < 0 ? depth : width)/2f;
        edgeBox(x0,0f,z0,x1,height,z1,c);
    }

    private void addStars() {
        for (Star star : backgroundStars) {
            drawStar(star, 1.0f);
        }
        for (StarSystem system : starSystems) {
            Star primary = system.stars.get(0);
            drawStar(primary, 1.0f);
            for (int i = 1; i < system.stars.size(); i++) {
                Star companion = system.stars.get(i);
                drawStar(companion, 0.72f);
                if (i <= 4) {
                    lines.add(primary.x, primary.y, primary.z, companion.x, companion.y, companion.z,
                            0.20f, 0.32f, 0.42f, 0.18f);
                }
            }
        }
    }

    private void drawStar(Star s, float alphaScale) {
        float r = s.size;
        float a = s.a * alphaScale;
        lines.add(s.x-r, s.y, s.z, s.x+r, s.y, s.z, s.r,s.g,s.b,a);
        lines.add(s.x, s.y-r, s.z, s.x, s.y+r, s.z, s.r,s.g,s.b,a);
        if (s.size > 0.13f) lines.add(s.x, s.y, s.z-r, s.x, s.y, s.z+r, s.r,s.g,s.b,a*0.75f);
    }

    private void buildBackgroundStars() {
        Random r = new Random(0x5A17F13DL);
        for (int i = 0; i < 420; i++) {
            double yaw = r.nextDouble() * Math.PI * 2.0;
            double pitch = Math.toRadians(3.0 + r.nextDouble() * 84.0);
            float radius = 92f + r.nextFloat() * 54f;
            float[] pos = spherical(radius, yaw, pitch);

            float brightnessRoll = r.nextFloat();
            float size;
            float alpha;
            if (brightnessRoll < 0.72f) {
                size = 0.018f + r.nextFloat() * 0.020f;
                alpha = 0.20f + r.nextFloat() * 0.24f;
            } else if (brightnessRoll < 0.95f) {
                size = 0.035f + r.nextFloat() * 0.030f;
                alpha = 0.42f + r.nextFloat() * 0.23f;
            } else {
                size = 0.065f + r.nextFloat() * 0.050f;
                alpha = 0.68f + r.nextFloat() * 0.22f;
            }

            float[] c = starColor(r.nextInt(10) == 0 ? 2 : (r.nextBoolean() ? 0 : 3));
            backgroundStars.add(new Star(
                    pos[0], pos[1] + EYE_HEIGHT, pos[2],
                    size, c[0], c[1], c[2], alpha));
        }
    }

    private void buildStarSystems() {
        Random r = new Random(19840101L);
        for (int i = 0; i < 34; i++) {
            double yaw = r.nextDouble() * Math.PI * 2.0;
            double pitch = Math.toRadians(-12.0 + r.nextDouble() * 84.0);
            float radius = 75f + r.nextFloat() * 55f;
            float[] center = spherical(radius, yaw, pitch);
            StarSystem system = new StarSystem();
            int palette = r.nextInt(4);
            float[] color = starColor(palette);
            system.stars.add(new Star(center[0], center[1] + EYE_HEIGHT, center[2],
                    0.16f + r.nextFloat()*0.17f, color[0],color[1],color[2],0.95f));
            int companions = 3 + r.nextInt(9);
            for (int c = 0; c < companions; c++) {
                float spread = 1.2f + r.nextFloat() * 5.0f;
                float dx = (r.nextFloat()-0.5f) * spread;
                float dy = (r.nextFloat()-0.5f) * spread;
                float dz = (r.nextFloat()-0.5f) * spread;
                float[] cc = starColor((palette + (r.nextInt(5)==0 ? 1 : 0)) % 4);
                system.stars.add(new Star(center[0]+dx, center[1]+EYE_HEIGHT+dy, center[2]+dz,
                        0.04f + r.nextFloat()*0.09f, cc[0],cc[1],cc[2],0.55f+r.nextFloat()*0.35f));
            }
            starSystems.add(system);
        }
    }

    private void addShootingStar(long now) {
        if (shootingStartNanos < 0 && now >= nextShootingStarNanos) {
            shootingStartNanos = now;
            currentShootingStar = randomShootingStar();
        }
        if (shootingStartNanos < 0 || currentShootingStar == null) return;

        float t = (now - shootingStartNanos) / 1_150_000_000f;
        if (t >= 1f) {
            shootingStartNanos = -1L;
            currentShootingStar = null;
            scheduleNextShootingStar(now);
            return;
        }
        float eased = t * t * (3f - 2f*t);
        float hx = lerp(currentShootingStar.x0, currentShootingStar.x1, eased);
        float hy = lerp(currentShootingStar.y0, currentShootingStar.y1, eased);
        float hz = lerp(currentShootingStar.z0, currentShootingStar.z1, eased);
        float trailT = Math.max(0f, eased - 0.16f);
        float tx = lerp(currentShootingStar.x0, currentShootingStar.x1, trailT);
        float ty = lerp(currentShootingStar.y0, currentShootingStar.y1, trailT);
        float tz = lerp(currentShootingStar.z0, currentShootingStar.z1, trailT);
        float alpha = (float)Math.sin(Math.PI * t);
        lines.add(tx,ty,tz,hx,hy,hz,1f,0.93f,0.72f,0.9f*alpha);
        lines.add(tx,ty+0.08f,tz,hx,hy+0.08f,hz,0.55f,0.78f,1f,0.45f*alpha);
    }

    private ShootingStar randomShootingStar() {
        double yaw = shootingRandom.nextDouble() * Math.PI * 2.0;
        double pitch = Math.toRadians(18 + shootingRandom.nextDouble() * 50);
        float[] a = spherical(78f, yaw, pitch);
        float[] b = spherical(78f, yaw + Math.toRadians(18 + shootingRandom.nextDouble()*16),
                pitch - Math.toRadians(7 + shootingRandom.nextDouble()*10));
        return new ShootingStar(a[0],a[1]+EYE_HEIGHT,a[2],b[0],b[1]+EYE_HEIGHT,b[2]);
    }

    private void scheduleNextShootingStar(long now) {
        long delayMs = 9000L + shootingRandom.nextInt(17000);
        nextShootingStarNanos = now + delayMs * 1_000_000L;
    }

    private void line(float x0,float y0,float z0,float x1,float y1,float z1,float[] c) {
        lines.add(x0,y0,z0,x1,y1,z1,c[0],c[1],c[2],c[3]);
    }

    private static float[] palette(int index, float fade) {
        switch (index) {
            case 0: return new float[]{0.02f,0.90f,1.0f,0.78f*fade};
            case 1: return new float[]{1.0f,0.18f,0.78f,0.72f*fade};
            case 2: return new float[]{0.42f,1.0f,0.28f,0.70f*fade};
            default:return new float[]{1.0f,0.70f,0.16f,0.72f*fade};
        }
    }

    private static float[] starColor(int index) {
        switch (index) {
            case 0: return new float[]{0.72f,0.84f,1.0f};
            case 1: return new float[]{1.0f,0.95f,0.82f};
            case 2: return new float[]{1.0f,0.72f,0.50f};
            default:return new float[]{0.82f,0.92f,1.0f};
        }
    }

    private static float[] spherical(float radius, double yaw, double pitch) {
        float cp = (float)Math.cos(pitch);
        float x = radius * cp * (float)Math.sin(yaw);
        float y = radius * (float)Math.sin(pitch);
        float z = -radius * cp * (float)Math.cos(yaw);
        return new float[]{x,y,z};
    }

    private static int hash32(long v) {
        v ^= (v >>> 33);
        v *= 0xff51afd7ed558ccdL;
        v ^= (v >>> 33);
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= (v >>> 33);
        return (int)(v ^ (v >>> 32));
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float lerp(float a,float b,float t) { return a + (b-a)*t; }

    private static int createProgram(String vertex, String fragment) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) throw new RuntimeException("OpenGL program link failed: " + GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) throw new RuntimeException("OpenGL shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n" +
            "attribute vec3 aPosition;\n" +
            "attribute vec4 aColor;\n" +
            "varying vec4 vColor;\n" +
            "void main(){ vColor=aColor; gl_Position=uMvp*vec4(aPosition,1.0); }\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec4 vColor;\n" +
            "void main(){ gl_FragColor=vColor; }\n";

    private static final class LineBatch {
        final FloatBuffer buffer;
        final int maxVertices;
        int vertexCount = 0;

        LineBatch(int maxVertices) {
            this.maxVertices = maxVertices;
            buffer = ByteBuffer.allocateDirect(maxVertices * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        void reset() {
            vertexCount = 0;
            buffer.clear();
        }

        void add(float x0,float y0,float z0,float x1,float y1,float z1,float r,float g,float b,float a) {
            if (vertexCount + 2 > maxVertices) return;
            put(x0,y0,z0,r,g,b,a);
            put(x1,y1,z1,r,g,b,a);
        }

        private void put(float x,float y,float z,float r,float g,float b,float a) {
            buffer.put(x).put(y).put(z).put(r).put(g).put(b).put(a);
            vertexCount++;
        }
    }

    private static final class StarSystem {
        final List<Star> stars = new ArrayList<>();
    }

    private static final class Star {
        final float x,y,z,size,r,g,b,a;
        Star(float x,float y,float z,float size,float r,float g,float b,float a) {
            this.x=x; this.y=y; this.z=z; this.size=size; this.r=r; this.g=g; this.b=b; this.a=a;
        }
    }

    private static final class ShootingStar {
        final float x0,y0,z0,x1,y1,z1;
        ShootingStar(float x0,float y0,float z0,float x1,float y1,float z1) {
            this.x0=x0; this.y0=y0; this.z0=z0; this.x1=x1; this.y1=y1; this.z1=z1;
        }
    }
}
