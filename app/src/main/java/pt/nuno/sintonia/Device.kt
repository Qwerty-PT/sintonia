package pt.nuno.sintonia

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object Device {
    /** Google TV, Android TV, Fire TV (ou qualquer aparelho sem ecrã tátil). */
    fun isTv(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
