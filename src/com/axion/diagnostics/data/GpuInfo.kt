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

    fun collect(): GpuSnapshot {
        val config = KernelConfig.gpuConfig
        val gpuRoot = File("/sys/class/kgsl/kgsl-3d0")
        val devfreq = firstExistingDirectory(
            config.node,
            "/sys/class/kgsl/kgsl-3d0/devfreq",
            "/sys/class/devfreq/1c00000.qcom,kgsl-3d0",
            "/sys/class/devfreq/gpufreq"
        )
        val current = firstExistingFile(
            config.currentNode,
            File(gpuRoot, "gpuclk").path,
            File(devfreq ?: gpuRoot, "cur_freq").path,
            File(devfreq ?: gpuRoot, "scaling_cur_freq").path
        )
        val currentMhz = current?.let { readFrequencyMhz(it) } ?: 0
        if (currentMhz <= 0) {
            return GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
        }

        val maxFreq = readFrequencyMhz(firstExistingFile(
            File(devfreq ?: gpuRoot, "max_freq").path,
            File(gpuRoot, "max_gpuclk").path,
            File(gpuRoot, "gpu_max_clock").path
        ))
        val minFreq = readFrequencyMhz(firstExistingFile(
            File(devfreq ?: gpuRoot, "min_freq").path,
            File(gpuRoot, "min_gpuclk").path,
            File(gpuRoot, "gpu_min_clock").path
        ))
        val busyNode = firstExistingFile(
            config.usageNode,
            File(gpuRoot, "gpu_busy_percentage").path,
            File(gpuRoot, "gpubusy").path
        )
        val availableNode = firstExistingFile(
            File(gpuRoot, "gpu_available_frequencies").path,
            File(devfreq ?: gpuRoot, "available_frequencies").path,
            File(devfreq ?: gpuRoot, "scaling_available_frequencies").path
        )
        val availableFrequencies = config.values.ifEmpty {
            readAvailableFrequencies(availableNode)
        }
        val governor = readText(firstExistingFile(
            File(devfreq ?: gpuRoot, "governor").path,
            File(gpuRoot, "governor").path
        )) ?: "unknown"

        return GpuSnapshot(
            frequencyMhz = currentMhz,
            maxFrequencyMhz = maxFreq,
            minFrequencyMhz = minFreq,
            busyPercent = readBusyPercent(busyNode),
            governor = governor,
            availableFrequencies = availableFrequencies,
            available = true
        )
    }

    private fun firstExistingDirectory(vararg paths: String?): File? = paths
        .filterNotNull()
        .map(::File)
        .firstOrNull { it.isDirectory }

    private fun firstExistingFile(vararg paths: String?): File? = paths
        .filterNotNull()
        .map(::File)
        .firstOrNull { it.isFile }

    private fun readText(file: File?): String? = file?.let {
        runCatching { it.readText().trim() }.getOrNull()?.takeIf(String::isNotEmpty)
    }

    private fun readFrequencyMhz(file: File?): Int {
        val raw = readText(file)?.let { Regex("\\d+").find(it)?.value?.toLongOrNull() } ?: return 0
        return KernelConfig.scaleFreqToMhz(raw)
    }

    private fun readBusyPercent(file: File?): Int {
        val values = readText(file)
            ?.let { text -> Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.take(2).toList() }
            ?: return 0
        val busy = values.firstOrNull() ?: return 0
        val total = values.getOrNull(1)
        val percent = if (total != null && total > 0) (busy * 100 / total) else busy
        return percent.toInt().coerceIn(0, 100)
    }

    private fun readAvailableFrequencies(file: File?): List<Int> = readText(file)
        ?.let { text ->
            Regex("\\d+").findAll(text)
                .mapNotNull { it.value.toLongOrNull() }
                .map { KernelConfig.scaleFreqToMhz(it) }
                .filter { it > 0 }
                .distinct()
                .sorted()
                .toList()
        }
        ?: emptyList()
}
