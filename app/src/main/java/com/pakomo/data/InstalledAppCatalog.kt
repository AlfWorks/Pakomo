package com.pakomo.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    isSensitive = isSensitivePackage(packageName),
                    isSelected = packageName in selectedPackages,
                    domains = domainsByPackage[packageName].orEmpty(),
                )
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.isSelected }
                    .thenComparator { left, right -> collator.compare(left.label, right.label) },
            )
            .toList()
    }

    private fun isSensitivePackage(packageName: String): Boolean {
        val keywords = listOf(
            "phone",
            "dialer",
            "telecom",
            "authenticator",
            "password",
            "security",
            "update",
            "mdm",
        )
        return keywords.any(packageName::contains)
    }
}
