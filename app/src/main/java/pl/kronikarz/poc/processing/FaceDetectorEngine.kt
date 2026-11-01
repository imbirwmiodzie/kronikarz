package pl.kronikarz.poc.processing

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import pl.kronikarz.poc.analysis.Pt

data class DetectedFace(
    val box: Rect,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?,
    val leftEye: Pt?,
    val rightEye: Pt?,
    val nose: Pt?,
    val mouthLeft: Pt?,
    val mouthRight: Pt?,
)

/**
 * ML Kit Face Detection, wariant z modelem w paczce (com.google.mlkit:face-detection) —
 * działa bez sieci i bez Google Play Services. Tryb dokładny, z klasyfikacją i punktami twarzy.
 */
class FaceDetectorEngine(minFaceSizeRelative: Float) : AutoCloseable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(minFaceSizeRelative.coerceIn(0.01f, 1f))
            .build()
    )

    /** Blokujące — wywoływać z wątku w tle. */
    fun detect(bitmap: Bitmap): List<DetectedFace> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        return faces.map { it.toDetected() }
    }

    private fun Face.toDetected(): DetectedFace {
        fun lm(type: Int) = getLandmark(type)?.position?.let { Pt(it.x, it.y) }
        return DetectedFace(
            box = Rect(boundingBox),
            yaw = headEulerAngleY,
            pitch = headEulerAngleX,
            roll = headEulerAngleZ,
            leftEyeOpen = leftEyeOpenProbability,
            rightEyeOpen = rightEyeOpenProbability,
            smiling = smilingProbability,
            leftEye = lm(FaceLandmark.LEFT_EYE),
            rightEye = lm(FaceLandmark.RIGHT_EYE),
            nose = lm(FaceLandmark.NOSE_BASE),
            mouthLeft = lm(FaceLandmark.MOUTH_LEFT),
            mouthRight = lm(FaceLandmark.MOUTH_RIGHT),
        )
    }

    override fun close() = detector.close()
}
