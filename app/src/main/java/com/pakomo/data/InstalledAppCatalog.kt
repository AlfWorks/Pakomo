package com.pakomo.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import com.pakomo.core.model.InstalledApp
import java.text.Collator
import java.util.Locale

class InstalledAppCatalog(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun load(
        selectedPackages: Set<String>,
        domainsByPackage: Map<String, List<String>>,
    ): List<InstalledApp> {
        val applications = if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            packageManager.getInstalledApplications(0)
        }

        val collator = Collator.getInstance(Locale.getDefault())
        return applications
            .asSequence()
            .filterNot { it.packageName == context.packageName }
            .map { info ->
                val packageName = info.packageName
                InstalledApp(
                    label = packageManager.getApplicationLabel(info).toString(),
                    packageName = packageName,
                    isSelected = packageName in selectedPackages,
                    domains = domainsByPackage[packageName].orEmpty(),
                    icon = runCatching {
                        packageManager.getApplicationIcon(info)
                            .toBitmap(96, 96)
                            .also { it.prepareToDraw() }
                    }.getOrNull(),
                )
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.isSelected }
                    .thenComparator { left, right -> collator.compare(left.label, right.label) },
            )
            .toList()
    }
}
