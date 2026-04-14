package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

class AiFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Pulse animation on the AI icon ──
        val icon = view.findViewById<View>(R.id.ai_icon)
        val scaleX = ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.08f, 1f).apply {
            duration = 2500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.08f, 1f).apply {
            duration = 2500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }

        // ── Glow halo pulse (alpha 0.1 → 0.3 → 0.1) ──
        val glow = view.findViewById<View>(R.id.ai_glow)
        ObjectAnimator.ofFloat(glow, "alpha", 0.1f, 0.3f, 0.1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        // Scale glow too
        ObjectAnimator.ofFloat(glow, "scaleX", 1f, 1.3f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(glow, "scaleY", 1f, 1.3f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // ── Loading dots stagger animation ──
        val dot1 = view.findViewById<View>(R.id.dot_1)
        val dot2 = view.findViewById<View>(R.id.dot_2)
        val dot3 = view.findViewById<View>(R.id.dot_3)

        // Dot 1: dim → bright → dim (0ms offset)
        ObjectAnimator.ofFloat(dot1, "alpha", 0.2f, 0.8f, 0.2f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        // Dot 2: dim → bright → dim (500ms offset)
        ObjectAnimator.ofFloat(dot2, "alpha", 0.8f, 0.2f, 0.8f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            startDelay = 500
            start()
        }
        // Dot 3: dim → bright → dim (1000ms offset)
        ObjectAnimator.ofFloat(dot3, "alpha", 0.2f, 0.8f, 0.2f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            startDelay = 1000
            start()
        }
    }
}
