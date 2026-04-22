package com.replatecamera

import android.content.Context
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import kotlin.math.sqrt

/**
 * A white unfilled square that sits flat on detected horizontal planes,
 * centered on the hit point from screen centre.
 * Hidden entirely when no plane is found (no "searching" animation).
 */
class FocusNode(private val context: Context) : Node() {

    companion object {
        // Half-size of the square in metres (actual size is scaled by distance)
        private const val HALF = 0.12f
        // Thickness of each side bar
        private const val BAR_THICKNESS = 0.004f
        // Vertical height of the bar (flat on the plane)
        private const val BAR_HEIGHT = 0.002f
    }

    private val sideNodes = mutableListOf<Node>()

    init {
        buildSquare()
        isEnabled = false
    }

    private fun buildSquare() {
        MaterialFactory.makeOpaqueWithColor(context, Color(1f, 1f, 1f, 1f))
            .thenAccept { material ->
                val barCenter = Vector3(0f, BAR_HEIGHT / 2f, 0f)
                // Top and Bottom bars — extend along X
                for (z in listOf(-HALF, HALF)) {
                    val node = Node()
                    node.renderable = ShapeFactory.makeCube(
                        Vector3(HALF * 2, BAR_HEIGHT, BAR_THICKNESS),
                        barCenter,
                        material.makeCopy()
                    )
                    node.localPosition = Vector3(0f, 0f, z)
                    addChild(node)
                    sideNodes.add(node)
                }
                // Left and Right bars — extend along Z
                for (x in listOf(-HALF, HALF)) {
                    val node = Node()
                    node.renderable = ShapeFactory.makeCube(
                        Vector3(BAR_THICKNESS, BAR_HEIGHT, HALF * 2),
                        barCenter,
                        material.makeCopy()
                    )
                    node.localPosition = Vector3(x, 0f, 0f)
                    addChild(node)
                    sideNodes.add(node)
                }
            }
    }

    fun updateFromFrame(frame: Frame, sceneView: ArSceneView) {
        val hit = getCenterHitResult(frame, sceneView)
        if (hit != null) {
            isEnabled = true
            worldPosition = Vector3(
                hit.hitPose.tx(),
                hit.hitPose.ty() + 0.002f,
                hit.hitPose.tz()
            )
            worldRotation = Quaternion(
                hit.hitPose.qx(),
                hit.hitPose.qy(),
                hit.hitPose.qz(),
                hit.hitPose.qw()
            )
            // Scale square with distance so it stays visually consistent
            val pose = frame.camera.pose
            val dx = pose.tx() - hit.hitPose.tx()
            val dy = pose.ty() - hit.hitPose.ty()
            val dz = pose.tz() - hit.hitPose.tz()
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            val scale = (distance * 0.55f).coerceIn(0.4f, 1.6f)
            localScale = Vector3.one().scaled(scale)
        } else {
            isEnabled = false
        }
    }

    private fun getCenterHitResult(frame: Frame, sceneView: ArSceneView): HitResult? {
        if (sceneView.width <= 0 || sceneView.height <= 0) return null
        val cx = sceneView.width / 2f
        val cy = sceneView.height / 2f
        return frame.hitTest(cx, cy).firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                trackable.trackingState == TrackingState.TRACKING &&
                trackable.isPoseInPolygon(hit.hitPose) &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
    }
}
