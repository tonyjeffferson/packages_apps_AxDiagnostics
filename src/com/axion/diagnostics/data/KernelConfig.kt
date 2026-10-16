/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.axion.diagnostics.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

data class CpuClusterConfig(
    val id: String,
    val group: String,
    val minNode: String?,
    val maxNode: String?,
    val availablePath: String?,
    val governorNode: String?,
    val governorAvailablePath: String?
)

data class GpuConfig(
    val node: String?,
    val currentNode: String?,
    val usageNode: String?,
    val frequencyMultiplier: Long,
    val values: List<Int>
)

object KernelConfig {
    private const val CONFIG_PATH = "/system_ext/etc/ax_kernel_manager.xml"

    val cpuClusters: List<CpuClusterConfig> by lazy {
        val configured = parseCpuClusters()
        val configuredPolicies = configured.mapNotNull { cluster ->
            sequenceOf(cluster.minNode, cluster.maxNode, cluster.governorNode)
                .filterNotNull()
                .map { File(it).parentFile?.absolutePath }
                .firstOrNull()
        }.toSet()
        configured + discoverCpuPolicies().filterNot { policy ->
            sequenceOf(policy.minNode, policy.maxNode, policy.governorNode)
                .filterNotNull()
                .map { File(it).parentFile?.absolutePath }
                .firstOrNull()?.let { it in configuredPolicies } == true
        }
    }

    val gpuConfig: GpuConfig by lazy {
        parseGpuConfig()
    }

    fun scaleFreqToMhz(raw: Long): Int {
        return when {
            raw > 10_000_000_000L -> (raw / 1_000_000L).toInt()
            raw > 10_000_000L -> (raw / 1_000_000L).toInt()
            raw > 1_000L -> (raw / 1_000L).toInt()
            else -> raw.toInt()
        }
    }

    private fun parseCpuClusters(): List<CpuClusterConfig> {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return emptyList()

        val clusters = mutableListOf<CpuClusterConfig>()
        runCatching {
            FileInputStream(file).use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "cpu") {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val group = parser.getAttributeValue(null, "group") ?: ""
                        val minNode = parser.getAttributeValue(null, "minNode")
                        val maxNode = parser.getAttributeValue(null, "maxNode")
                        val availablePath = parser.getAttributeValue(null, "availablePath")
                        val governorNode = parser.getAttributeValue(null, "governorNode")
                        val governorAvailablePath = parser.getAttributeValue(null, "governorAvailablePath")
                        
                        if (id.isNotEmpty()) {
                            clusters.add(
                                CpuClusterConfig(
                                    id = id,
                                    group = group,
                                    minNode = minNode,
                                    maxNode = maxNode,
                                    availablePath = availablePath,
                                    governorNode = governorNode,
                                    governorAvailablePath = governorAvailablePath
                                )
                            )
                        }
                    }
                    eventType = parser.next()
                }
            }
        }
        return clusters
    }

    private fun parseGpuConfig(): GpuConfig {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return discoverGpuConfig()

        var node: String? = null
        var currentNode: String? = null
        var usageNode: String? = null
        var multiplier = 1L
        var values = emptyList<Int>()

        runCatching {
            FileInputStream(file).use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "gpu") {
                        node = parser.getAttributeValue(null, "node")
                        currentNode = parser.getAttributeValue(null, "currentNode")
                        usageNode = parser.getAttributeValue(null, "usageNode")
                        val multStr = parser.getAttributeValue(null, "frequencyMultiplier")
                        multiplier = multStr?.toLongOrNull() ?: 1L
                        
                        val valStr = parser.getAttributeValue(null, "values")
                        values = valStr?.split(",")
                            ?.mapNotNull { it.trim().toLongOrNull() }
                            ?.map { scaleFreqToMhz(it) } ?: emptyList()
                        break
                    }
                    eventType = parser.next()
                }
            }
        }
        val parsed = GpuConfig(node, currentNode, usageNode, multiplier, values)
        return if (parsed.currentNode != null) parsed else discoverGpuConfig()
    }

    private fun discoverCpuPolicies(): List<CpuClusterConfig> {
        val directory = File("/sys/devices/system/cpu/cpufreq")
        return directory.listFiles()?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            ?.mapNotNull { policy ->
                val related = File(policy, "related_cpus")
                val affected = File(policy, "affected_cpus")
                if (!related.exists() && !affected.exists()) return@mapNotNull null
                val min = listOf("scaling_min_freq", "cpuinfo_min_freq")
                    .map { File(policy, it) }.firstOrNull { it.exists() }
                val max = listOf("scaling_max_freq", "cpuinfo_max_freq")
                    .map { File(policy, it) }.firstOrNull { it.exists() }
                val governor = listOf("scaling_governor", "governor")
                    .map { File(policy, it) }.firstOrNull { it.exists() }
                val id = policy.name.removePrefix("policy")
                CpuClusterConfig(
                    id = id,
                    group = id,
                    minNode = min?.absolutePath,
                    maxNode = max?.absolutePath,
                    availablePath = File(policy, "scaling_available_frequencies").takeIf { it.exists() }?.absolutePath,
                    governorNode = governor?.absolutePath,
                    governorAvailablePath = File(policy, "scaling_available_governors").takeIf { it.exists() }?.absolutePath
                )
            } ?: emptyList()
    }

    private fun discoverGpuConfig(): GpuConfig {
        val kgsl = File("/sys/class/kgsl/kgsl-3d0")
        val devfreq = File(kgsl, "devfreq")
        val candidates = listOf(devfreq, kgsl).filter { it.exists() }
        val base = candidates.firstOrNull { File(it, "cur_freq").exists() }
            ?: candidates.firstOrNull()
        val current = listOf(File(kgsl, "gpuclk"), File(devfreq, "cur_freq"), File(kgsl, "cur_freq"))
            .firstOrNull { it.exists() }
        val busy = listOf(File(kgsl, "gpu_busy_percentage"), File(kgsl, "gpubusy"), File(devfreq, "busy"))
            .firstOrNull { it.exists() }
        val available = listOf(
            File(kgsl, "gpu_available_frequencies"),
            File(devfreq, "available_frequencies")
        ).firstOrNull { it.exists() }
        val values = available?.let { file ->
            runCatching { file.readText().trim().split("\\s+".toRegex()).mapNotNull { it.toLongOrNull() }.map(::scaleFreqToMhz) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        return GpuConfig(base?.absolutePath, current?.absolutePath, busy?.absolutePath, 1L, values)
    }
}
