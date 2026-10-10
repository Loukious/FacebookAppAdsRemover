package tn.loukious.facebookappadsremover.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Keeps the actions visible while the options use the dialog's remaining height. */
@SuppressLint("ViewConstructor") // Constructed in code, never inflated from XML.
internal class QuickDownloadDialogLayout(
    context: Context,
    options: View,
    actions: View,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        // WRAP_CONTENT keeps short lists compact; weight lets long lists shrink.
        addView(ScrollView(context).apply { addView(options) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f))
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Reflows using the actual label widths, including the user's font scale. */
    class ActionBar(context: Context) : LinearLayout(context) {
        private val density = resources.displayMetrics.density
        private val gap = (6 * density).toInt()

        init {
            gravity = Gravity.END
            val horizontalPadding = (16 * density).toInt()
            setPadding(horizontalPadding, gap, horizontalPadding, gap)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            var requiredWidth = paddingLeft + paddingRight
            var visibleCount = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                child.measure(unspecified, unspecified)
                requiredWidth += child.measuredWidth + if (visibleCount++ > 0) gap else 0
            }
            val stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
                requiredWidth > MeasureSpec.getSize(widthMeasureSpec)
            orientation = if (stacked) VERTICAL else HORIZONTAL

            var visibleIndex = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                val params = child.layoutParams as LayoutParams
                params.width = if (stacked) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
                params.marginStart = if (!stacked && visibleIndex > 0) gap else 0
                params.topMargin = if (stacked && visibleIndex > 0) gap else 0
                visibleIndex++
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
