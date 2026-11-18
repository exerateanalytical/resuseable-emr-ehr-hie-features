/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — iPadOS Apple Pencil Specialty Annotation Bridge
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.platform

import platform.UIKit.UITouch
import platform.UIKit.UITouchTypeStylus

/**
 * Captures Apple Pencil pressure and palm-rejection stylus events on iPadOS
 * for Dental odontogram markings, Ophthalmology fundus diagrams, and Dermatology lesion maps.
 */
object ApplePencilBridge {

    fun extractStrokePoint(touch: UITouch, x: Float, y: Float): StylusStrokePoint {
        val isApplePencil = touch.type == UITouchTypeStylus
        val normalizedForce = if (touch.maximumPossibleForce > 0.0) {
            (touch.force / touch.maximumPossibleForce).toFloat().coerceIn(0.1f, 1.0f)
        } else {
            1.0f
        }
        return StylusStrokePoint(x = x, y = y, pressure = normalizedForce, isStylus = isApplePencil)
    }
}
