package com.willsimon.retroxrealwalk.render;

import android.content.Context;
import android.opengl.GLSurfaceView;

import com.willsimon.retroxrealwalk.model.WalkState;
import com.willsimon.retroxrealwalk.tracking.HeadPose;

public final class RetroGLView extends GLSurfaceView {
    public RetroGLView(Context context, WalkState walkState, HeadPose headPose) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(new RetroRenderer(walkState, headPose));
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }
}
