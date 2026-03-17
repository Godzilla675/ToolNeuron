package com.memoryvault

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

class BackupManagerBenchmark {

    @Test
    fun benchmarkCleanup() {
        runBlocking {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "backup_benchmark")
            tempDir.mkdirs()

            // Create 30000 dummy backup files
            for (i in 1..30000) {
                val file = File(tempDir, "vault_backup_$i.mvlt.gz")
                file.createNewFile()
            }

            val manager = BackupManager(File("dummy"))

            val time = measureTimeMillis {
                manager.cleanupOldBackups(tempDir, 10)
            }

            // Print to standard error as it might bypass gradle's output buffering
            System.err.println("Baseline Cleanup Time: $time ms")
            tempDir.deleteRecursively()
        }
    }
}
