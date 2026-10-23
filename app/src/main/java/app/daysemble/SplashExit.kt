package app.daysemble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.PathInterpolator
import androidx.core.splashscreen.SplashScreenViewProvider
import kotlin.math.hypot

/**
 * Hand-off from the launch splash to the app.
 *
 * The splash background closes in as a circle around the logo (echoing the orbit ring in the
 * artwork), revealing the app from the edges inward, while the logo settles slightly and fades.
 * The app's own entrance (DaysembleRoot `animateIntro`) runs underneath at the same time.
 * Only the supplied artwork is shown; nothing is redrawn. Total 420 ms, no added waiting:
 * this starts as soon as settings are loaded and the first frame is ready.
 *
 * With reduced motion the splash simply fades out (150 ms; instant when animations are off).
 */
internal object SplashExit {
    private const val DURATION = 420L
    private val emphasized = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    private val accelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    fun play(provider: SplashScreenViewProvider, reduced: Boolean) {
        val splash = provider.view
        if (reduced || !splash.isAttachedToWindow || splash.width == 0) {
            fade(provider, splash)
            return
        }
        val icon: View? = runCatching { provider.iconView }.getOrNull()
        val (cx, cy) = icon?.centreIn(splash) ?: (splash.width / 2 to splash.height / 2)
        val startRadius = hypot(maxOf(cx, splash.width - cx).toFloat(), maxOf(cy, splash.height - cy).toFloat())
        val endRadius = (icon?.width ?: splash.width / 4) * 0.36f

        val reveal = runCatching {
            ViewAnimationUtils.createCircularReveal(splash, cx, cy, startRadius, endRadius).apply {
                interpolator = emphasized
                duration = DURATION
            }
        }.getOrNull() ?: run { fade(provider, splash); return }

        // The logo settles slightly and fades in the last two thirds; it ends together with the reveal,
        // which removes the splash.
        icon?.animate()?.scaleX(0.86f)?.scaleY(0.86f)?.setInterpolator(emphasized)?.setDuration(DURATION)?.start()
        icon?.let {
            ObjectAnimator.ofFloat(it, View.ALPHA, 1f, 0f).apply {
                interpolator = accelerate; startDelay = DURATION / 3; duration = DURATION - DURATION / 3
            }.start()
        }
        reveal.addListener(removeOnEnd(provider))
        reveal.start()
    }

    private fun fade(provider: SplashScreenViewProvider, splash: View) {
        splash.animate().alpha(0f).setDuration(150L).withEndAction { provider.remove() }.start()
    }

    private fun removeOnEnd(provider: SplashScreenViewProvider) = object : AnimatorListenerAdapter() {
        private var done = false
        private fun finish() { if (!done) { done = true; provider.remove() } }
        override fun onAnimationEnd(animation: Animator) = finish()
        override fun onAnimationCancel(animation: Animator) = finish()
    }

    /** Centre of this view in [root]'s coordinates. */
    private fun View.centreIn(root: View): Pair<Int, Int> {
        val a = IntArray(2).also { getLocationInWindow(it) }
        val b = IntArray(2).also { root.getLocationInWindow(it) }
        return (a[0] - b[0] + width / 2) to (a[1] - b[1] + height / 2)
    }
}
