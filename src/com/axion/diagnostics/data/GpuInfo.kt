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

import java.io.File

data class GpuSnapshot(
    val frequencyMhz: Int,
    val maxFrequencyMhz: Int,
    val minFrequencyMhz: Int,
    val busyPercent: Int,
    val governor: String,
    val availableFrequencies: List<Int>,
    val available: Boolean
)

object GpuCollector {

    fun collect(): GpuSnapshot = collectFromKernelConfig()

    private fun collectFromKernelConfig(): GpuSnapshot {
        val config = KernelConfig.gpuConfig
        val currentNode = config.currentNode ?: return GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
        val freqFile = File(currentNode)
        if (!freqFile.exists()) return GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
        val rawFreq = runCatching { freqFile.readText().trim().toLong() }.getOrDefault(0L)
        val curFreq = KernelConfig.scaleFreqToMhz(rawFreq)
        if (curFreq <= 0) return GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)

        val busy = readBusyFromNode(config.usageNode)
        val governor = findGpuGovernor()
        val maxFreq = readScaledFreqFromNode(config.node, "max_freq")
        val minFreq = readScaledFreqFromNode(config.node, "min_freq")

        return GpuSnapshot(curFreq, maxFreq, minFreq, busy, governor, config.values, true)
    }

    private fun readBusyFromNode(node: String?): Int {
        if (node == null) return 0
        val file = File(node)
        if (!file.exists()) return 0
        val raw = runCatching { file.readText().trim() }.getOrDefault("")
        val percent = raw.replace("%", "").toIntOrNull()
        if (percent != null) return percent.coerceIn(0, 100)
        val counters = raw.split("\\s+".toRegex()).mapNotNull { it.toLongOrNull() }
        val busy = counters.getOrNull(0) ?: return 0
        val total = counters.getOrNull(1) ?: return 0
        return if (total > 0) (busy * 100 / total).toInt().coerceIn(0, 100) else 0
    }

    private fun readScaledFreqFromNode(baseNode: String?, fileName: String): Int {
        if (baseNode == null) return 0
        val base = File(baseNode)
        val alternateNames = when (fileName) {
            "max_freq" -> listOf("max_freq", "max_gpuclk", "gpu_max_clock")
            "min_freq" -> listOf("min_freq", "min_gpuclk", "gpu_min_clock")
            else -> listOf(fileName)
        }
        val file = alternateNames.map { File(base, it) }.firstOrNull { it.exists() } ?: return 0
        val raw = runCatching { file.readText().trim().toLong() }.getOrDefault(0L)
        return KernelConfig.scaleFreqToMhz(raw)
    }

    private fun findGpuGovernor(): String {
        val configNode = KernelConfig.gpuConfig.node
        if (configNode != null) {
            val gov = runCatching { File(configNode, "governor").readText().trim() }.getOrNull()
            if (!gov.isNullOrEmpty()) return gov
        }
        val fallbackNodes = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/governor",
            "/sys/class/devfreq/1c00000.qcom,kgsl-3d0/governor",
            "/sys/class/devfreq/gpufreq/governor"
        )
        for (path in fallbackNodes) {
            val gov = runCatching { File(path).readText().trim() }.getOrNull()
            if (!gov.isNullOrEmpty()) return gov
        }
        return "unknown"
    }

}
