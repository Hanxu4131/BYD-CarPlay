package com.shilapi.xcertplay

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R

/** The turn card reads phone metadata; it never infers an instruction from map pixels. */
internal object LegacyClusterGuidance {
    fun visible(content: CarPlayClusterDisplay.Content, state: CarPlayGlance.Snapshot): Boolean =
        content == CarPlayClusterDisplay.Content.INSTRUMENTS && state.connected && state.maneuverType != null

    fun arrow(type: Int, drivingSide: Int): Int = when (type) {
        in 1..14, in 18..53 -> NavigationWidgetUpdater.arrow(type, drivingSide)
        else -> R.drawable.ic_dp_navigation
    }

    fun distance(context: Context, meters: Int): String = when {
        meters < 1_000 -> context.getString(R.string.widget_distance_meters, ((meters.toLong() + 5) / 10 * 10).toInt())
        meters < 10_000 -> context.getString(R.string.widget_distance_km_decimal, meters / 1000.0)
        else -> context.getString(R.string.widget_distance_km, ((meters.toLong() + 500) / 1000).toInt())
    }

    fun placement(map: LegacyClusterLayout.Plan, turnArea: LegacyClusterTurnArea.Settings): LegacyClusterLayout.Plan =
        LegacyClusterTurnArea.project(map, turnArea)
}

internal class LegacyClusterGuidanceView(context: Context) : FrameLayout(context) {
    private val card = LayoutInflater.from(context).inflate(R.layout.legacy_cluster_guidance, this, false)
    private val arrow = card.findViewById<ImageView>(R.id.legacy_guidance_arrow)
    private val distance = card.findViewById<TextView>(R.id.legacy_guidance_distance)
    private val road = card.findViewById<TextView>(R.id.legacy_guidance_road)

    init {
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun place(plan: LegacyClusterLayout.Plan) {
        layoutParams = FrameLayout.LayoutParams(plan.width, plan.height).apply {
            leftMargin = plan.left
            topMargin = plan.top
        }
        val padding = (plan.width * 0.035f).toInt()
        card.setPadding(padding, padding, padding, padding)
        val arrowSize = (plan.height * 0.72f).toInt().coerceAtLeast(1)
        arrow.layoutParams = (arrow.layoutParams as LinearLayout.LayoutParams).apply {
            width = arrowSize
            height = arrowSize
            marginEnd = padding
        }
        distance.setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.height * 0.30f)
        road.setTextSize(TypedValue.COMPLEX_UNIT_PX, plan.height * 0.21f)
    }

    fun render(content: CarPlayClusterDisplay.Content, state: CarPlayGlance.Snapshot) {
        if (!LegacyClusterGuidance.visible(content, state)) {
            visibility = View.GONE
            return
        }
        arrow.setImageResource(LegacyClusterGuidance.arrow(state.maneuverType!!, state.drivingSide))
        distance.text = LegacyClusterGuidance.distance(context, state.distanceMeters)
        road.text = state.road
        road.visibility = if (state.road.isEmpty()) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
    }
}
