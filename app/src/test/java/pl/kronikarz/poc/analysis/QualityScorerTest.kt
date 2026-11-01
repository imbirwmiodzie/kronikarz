package pl.kronikarz.poc.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityScorerTest {
    private val scorer = QualityScorer()
    private val face = TestImages.detailed(96, 96)

    private fun obs(
        img: GrayImage = face,
        h: Float = 120f,
        yaw: Float = 0f,
        pitch: Float = 0f,
        eyes: Float? = 0.95f,
        smile: Float? = 0.1f,
    ) = FaceObservation(img, h, yaw, pitch, 0f, eyes, eyes, smile)

    private fun total(o: FaceObservation) = scorer.score(o).total

    @Test
    fun `wynik w zakresie 0-1`() {
        val q = scorer.score(obs())
        listOf(q.size, q.sharpness, q.frontal, q.eyes, q.exposure, q.smile, q.combing, q.total).forEach {
            assertTrue("$it", it in 0f..1f)
        }
    }

    @Test
    fun `ostra twarz wygrywa z rozmyta`() {
        assertTrue(total(obs()) > total(obs(img = TestImages.blur(face, 2))) + 0.05f)
    }

    @Test
    fun `twarz na wprost wygrywa z profilem`() {
        val front = total(obs(yaw = 3f))
        val turned = total(obs(yaw = 35f))
        val down = total(obs(pitch = 25f))
        assertTrue(front > turned + 0.1f)
        assertTrue(front > down + 0.1f)
        assertEquals(0f, scorer.score(obs(yaw = 60f)).frontal, 1e-6f)
    }

    @Test
    fun `otwarte oczy wygrywaja z mrugnieciem (takze jednym okiem)`() {
        val open = total(obs(eyes = 0.95f))
        val closed = total(obs(eyes = 0.05f))
        val oneEye = total(FaceObservation(face, 120f, 0f, 0f, 0f, 0.95f, 0.05f, 0.1f))
        assertTrue(open > closed + 0.1f)
        assertTrue(open > oneEye + 0.07f)
    }

    @Test
    fun `wieksza twarz wygrywa z mala`() {
        assertTrue(total(obs(h = 150f)) > total(obs(h = 45f)) + 0.1f)
    }

    @Test
    fun `nie- i przeswietlona twarz przegrywa z poprawna`() {
        val ok = total(obs())
        val dark = total(obs(img = TestImages.scaled(face) { it / 5 }))
        val bright = total(obs(img = TestImages.scaled(face) { 200 + it / 5 }))
        assertTrue(ok > dark + 0.05f)
        assertTrue(ok > bright + 0.05f)
    }

    @Test
    fun `usmiech daje tylko lekki bonus`() {
        val d = total(obs(smile = 1f)) - total(obs(smile = 0f))
        assertTrue(d > 0f && d <= 0.051f)
    }

    @Test
    fun `przeplot jest karany`() {
        val base = TestImages.blur(face, 1)
        val clean = scorer.score(obs(img = base))
        val combed = scorer.score(obs(img = TestImages.interlaced(base)))
        assertTrue(combed.combing > 0.5f)
        assertTrue(clean.combing < 0.1f)
        assertTrue(clean.total > combed.total + 0.1f)
    }

    @Test
    fun `brak klasyfikacji oczu daje wartosc neutralna`() {
        assertEquals(0.5f, scorer.score(obs(eyes = null)).eyes, 1e-6f)
    }
}
