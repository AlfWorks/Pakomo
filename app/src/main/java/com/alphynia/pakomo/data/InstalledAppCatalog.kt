package com.alphynia.pakomo.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import com.alphynia.pakomo.core.model.DomainTarget
import com.alphynia.pakomo.core.model.InstalledApp
import java.text.Collator
import java.util.Locale

data class InstalledAppCatalogResult(
    val apps: List<InstalledApp>,
    val isAvailable: Boolean,
)

class InstalledAppCatalog(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun load(
        selectedPackages: Set<String>,
        domainsByPackage: Map<String, List<DomainTarget>>,
    ): InstalledAppCatalogResult {
        val applications = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            } else {
                packageManager.getInstalledApplications(0)
            }
        }.getOrElse {
            return InstalledAppCatalogResult(
                apps = emptyList(),
                isAvailable = false,
            )
        }

        val collator = Collator.getInstance(Locale.getDefault())
        val apps = applications
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
        return InstalledAppCatalogResult(
            apps = apps,
            isAvailable = apps.isNotEmpty(),
        )
    }
}
