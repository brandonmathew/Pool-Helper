package com.poolhelper.app

import android.content.Context
import com.poolhelper.core.CalibrationProfile
import com.poolhelper.core.PhysicsConstants

/** Persists the auto-calibration profile across restarts. */
object CalibrationStore {

    private const val PREFS = "calibration"

    fun load(context: Context): CalibrationProfile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun d(key: String, default: Double) = p.getString(key, null)?.toDoubleOrNull() ?: default
        fun dn(key: String): Double? = p.getString(key, null)?.toDoubleOrNull()
        return CalibrationProfile(
            physics = PhysicsConstants(
                rollingDeceleration = d("rollingDecel", PhysicsConstants().rollingDeceleration),
                ballRestitution = d("ballRestitution", PhysicsConstants().ballRestitution),
                throwFactor = d("throwFactor", PhysicsConstants().throwFactor),
                cushionRestitution = d("cushionRestitution", PhysicsConstants().cushionRestitution),
                cushionTangentialKeep = d("cushionKeep", PhysicsConstants().cushionTangentialKeep),
            ),
            guidelineLength = dn("guideline"),
            aimDragGain = dn("aimGain"),
            powerGain = dn("powerGain"),
            physicsConfidence = d("physicsConf", 0.0),
            aimConfidence = d("aimConf", 0.0),
            powerConfidence = d("powerConf", 0.0),
        )
    }

    fun save(context: Context, profile: CalibrationProfile) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        fun put(key: String, value: Double?) {
            if (value == null) e.remove(key) else e.putString(key, value.toString())
        }
        put("rollingDecel", profile.physics.rollingDeceleration)
        put("ballRestitution", profile.physics.ballRestitution)
        put("throwFactor", profile.physics.throwFactor)
        put("cushionRestitution", profile.physics.cushionRestitution)
        put("cushionKeep", profile.physics.cushionTangentialKeep)
        put("guideline", profile.guidelineLength)
        put("aimGain", profile.aimDragGain)
        put("powerGain", profile.powerGain)
        put("physicsConf", profile.physicsConfidence)
        put("aimConf", profile.aimConfidence)
        put("powerConf", profile.powerConfidence)
        e.apply()
    }

    /** Feed an observed guideline length; returns the updated profile. */
    fun observeGuideline(context: Context, observed: Double): CalibrationProfile {
        val updated = load(context).withObservedGuideline(observed)
        save(context, updated)
        return updated
    }
}
