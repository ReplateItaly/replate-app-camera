# --- SafeArSceneView reflection: ArSceneView private fields ---
-keepclassmembernames class com.google.ar.sceneform.ArSceneView {
    *** pauseResumeTask;
    boolean hasSetTextureNames;
    *** session;
    *** isProcessingFrame;
    *** currentFrame;
    *** currentFrameTimestamp;
    *** allTrackables;
    *** updatedTrackables;
    *** cameraStream;
    *** planeRenderer;
}

-keepclassmembernames class com.google.ar.sceneform.SequentialTask {
    boolean isDone();
}

# --- ReplateArFragment reflection: BaseArFragment private fields ---
-keepclassmembernames class com.google.ar.sceneform.ux.BaseArFragment {
    *** arSceneView;
    *** onFocusListener;
}

# (optional) Kotlin helpers used by Sceneform fork
-keep class com.gorisse.thomas.sceneform.ArSceneViewKt { *; }
-keep class com.gorisse.thomas.sceneform.light.LightEstimationKt { *; }
-keep class com.gorisse.thomas.sceneform.scene.CameraKt { *; }

