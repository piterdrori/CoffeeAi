package com.personaledge.ai.voice

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object BundledAssetCopier {
    private const val TAG = "BundledAssetCopier"

    fun hasAssetDir(assets: AssetManager, assetPath: String): Boolean {
        return try {
            val children = assets.list(assetPath) ?: return false
            children.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun ensureDirOnDisk(context: Context, assetPath: String): String {
        val target = File(context.filesDir, assetPath)
        val marker = File(target, ".installed")
        if (marker.exists() && target.exists()) {
            return target.absolutePath
        }
        if (!hasAssetDir(context.assets, assetPath)) {
            error("Missing bundled assets at $assetPath")
        }
        if (target.exists()) {
            target.deleteRecursively()
        }
        copyAssetDir(context.assets, assetPath, target)
        marker.writeText("ok")
        Log.i(TAG, "Installed bundled assets to ${target.absolutePath}")
        return target.absolutePath
    }

    private fun copyAssetDir(assets: AssetManager, assetPath: String, targetDir: File) {
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            copyAssetFile(assets, assetPath, targetDir)
            return
        }
        targetDir.mkdirs()
        for (child in children) {
            copyAssetDir(assets, "$assetPath/$child", File(targetDir, child))
        }
    }

    private fun copyAssetFile(assets: AssetManager, assetPath: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
