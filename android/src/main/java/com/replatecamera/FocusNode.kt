package com.replatecamera

import android.content.Context
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory

class FocusNode(context: Context, private val arFragment: com.google.ar.sceneform.ux.ArFragment) : Node() {

    private val focusIndicator: Node = Node()

    init {
        MaterialFactory.makeOpaqueWithColor(context, Color(1f, 1f, 1f, 0.5f))
            .thenAccept { material ->
                val renderable = ShapeFactory.makeCylinder(0.1f, 0.01f, Vector3.zero(), material)
                focusIndicator.renderable = renderable
            }
        addChild(focusIndicator)
    }

    override fun onUpdate(frameTime: FrameTime?) {
        super.onUpdate(frameTime)
        val frame = arFragment.arSceneView.arFrame
        if (frame != null) {
            val hitResult = getCenterHitResult(frame)
            if (hitResult != null) {
                this.isEnabled = true
                this.worldPosition = Vector3(hitResult.hitPose.tx(), hitResult.hitPose.ty(), hitResult.hitPose.tz())
            } else {
                this.isEnabled = false
            }
        }
    }

    private fun getCenterHitResult(frame: com.google.ar.core.Frame): HitResult? {
        val view = arFragment.arSceneView
        val cx = view.width / 2f
        val cy = view.height / 2f
        val hitResults = frame.hitTest(cx, cy)
        return hitResults.firstOrNull {
            val trackable = it.trackable
            (trackable is Plane && trackable.isPoseInPolygon(it.hitPose))
        }
    }
}
