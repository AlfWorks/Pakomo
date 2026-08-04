package com.pakomo

import android.app.Application
import com.pakomo.update.NoviUpdateController

/**
 * Process-wide entry point. Holds the single [NoviUpdateController] so update state survives
 * Activity recreation, and reconciles any install session left pending by a replaced process.
 */
class PakomoApplication : Application() {

    val updateController: NoviUpdateController by lazy { NoviUpdateController(this) }

    override fun onCreate() {
        super.onCreate()
        updateController.reconcileOnStart()
    }
}
