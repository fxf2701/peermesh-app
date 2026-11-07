package app.peermesh

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

class NearbyRoomsLoadingDotsView(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val animators = mutableListOf<AnimatorSet>()
    private val dots = mutableListOf<View>()
    private val dotSizeDp: Int
    private val dotSpacingDp: Int

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.NearbyRoomsLoadingDotsView)
        dotSizeDp = typedArray.getInt(R.styleable.NearbyRoomsLoadingDotsView_dotSizeDp, 14)
        dotSpacingDp = typedArray.getInt(R.styleable.NearbyRoomsLoadingDotsView_dotSpacingDp, 10)
        typedArray.recycle()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val dotColor = 0xFFB8B8B8.toInt()
        val dotSizesDp = listOf(dotSizeDp, dotSizeDp, dotSizeDp, dotSizeDp)
        dotSizesDp.forEachIndexed { index, sizeDp ->
            val dot = View(context).apply {
                layoutParams = LayoutParams(dpToPx(sizeDp), dpToPx(sizeDp)).also { params ->
                    if (index < dotSizesDp.lastIndex) {
                        params.marginEnd = dpToPx(dotSpacingDp)
                    }
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
                alpha = 0.45f
                scaleX = 0.82f
                scaleY = 0.82f
            }
            dots += dot
            addView(dot)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            startAnimating()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) {
            if (visibility == VISIBLE) {
                startAnimating()
            } else {
                stopAnimating()
            }
        }
    }

    fun setLoadingVisible(visible: Boolean) {
        visibility = if (visible) VISIBLE else GONE
    }

    private fun startAnimating() {
        if (animators.isNotEmpty()) return
        dots.forEachIndexed { index, dot ->
            val scaleXAnimator = ObjectAnimator.ofFloat(dot, View.SCALE_X, 0.82f, 1f, 0.82f)
            val scaleYAnimator = ObjectAnimator.ofFloat(dot, View.SCALE_Y, 0.82f, 1f, 0.82f)
            val alphaAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.45f, 0.9f, 0.45f)
            listOf(scaleXAnimator, scaleYAnimator, alphaAnimator).forEach { animator ->
                animator.duration = 900L
                animator.startDelay = index * 120L
                animator.repeatCount = ObjectAnimator.INFINITE
            }
            AnimatorSet().apply {
                playTogether(scaleXAnimator, scaleYAnimator, alphaAnimator)
                start()
                animators += this
            }
        }
    }

    private fun stopAnimating() {
        animators.forEach { it.cancel() }
        animators.clear()
        dots.forEach { dot ->
            dot.alpha = 0.45f
            dot.scaleX = 0.82f
            dot.scaleY = 0.82f
        }
    }

    private fun dpToPx(valueDp: Int): Int {
        return (valueDp * resources.displayMetrics.density).toInt()
    }
}
