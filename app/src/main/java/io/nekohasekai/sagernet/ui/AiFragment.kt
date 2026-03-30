package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
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

        // Subtle pulse animation on the AI icon
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
    }
}
