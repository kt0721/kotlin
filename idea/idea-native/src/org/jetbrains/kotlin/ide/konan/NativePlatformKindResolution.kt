/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ide.konan

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.ProjectManager
import org.jetbrains.konan.KONAN_CURRENT_ABI_VERSION
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.caches.resolve.IdePlatformKindResolution
import org.jetbrains.kotlin.config.KotlinFacetSettingsProvider
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.context.GlobalContextImpl
import org.jetbrains.kotlin.ide.konan.analyzer.NativeAnalyzerFacade
import org.jetbrains.kotlin.idea.caches.project.LibraryInfo
import org.jetbrains.kotlin.idea.caches.project.getModuleInfosFromIdeaModel
import org.jetbrains.kotlin.idea.caches.resolve.PlatformAnalysisSettings
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.KONAN_STDLIB_NAME
import org.jetbrains.kotlin.konan.library.createKonanLibrary
import org.jetbrains.kotlin.konan.utils.KonanFactories.DefaultDeserializedDescriptorFactory
import org.jetbrains.kotlin.resolve.konan.platform.KonanPlatform

class NativePlatformKindResolution : IdePlatformKindResolution {

    override val kind get() = NativeIdePlatformKind

    override val resolverForModuleFactory get() = NativeAnalyzerFacade

    override fun isModuleForPlatform(module: Module) = module.isKotlinNativeModule

    override fun createBuiltIns(settings: PlatformAnalysisSettings, sdkContext: GlobalContextImpl) = createKotlinNativeBuiltIns(sdkContext)
}

val Module.isKotlinNativeModule: Boolean
    get() {
        val settings = KotlinFacetSettingsProvider.getInstance(project).getInitializedSettings(this)
        return settings.platformKind.isKotlinNative
    }

private fun createKotlinNativeBuiltIns(sdkContext: GlobalContextImpl): KotlinBuiltIns {

    // TODO: It depends on a random project's stdlib, propagate the actual project here.
    fun findStdlib(): Pair<String, LibraryInfo>? {
        ProjectManager.getInstance().openProjects.asSequence().forEach { project ->
            getModuleInfosFromIdeaModel(project, KonanPlatform).asSequence().forEach {
                for (dependency in it.dependencies()) {
                    if (dependency is LibraryInfo) {
                        for (path in dependency.getLibraryRoots()) {
                            if (path.endsWith(KONAN_STDLIB_NAME)) {
                                return path to dependency
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    val stdlib: Pair<String, LibraryInfo>? = findStdlib()

    if (stdlib != null) {

        val (path, libraryInfo) = stdlib
        val library = createKonanLibrary(File(path), KONAN_CURRENT_ABI_VERSION)

        val builtInsModule = DefaultDeserializedDescriptorFactory.createDescriptorAndNewBuiltIns(
            library,
            LanguageVersionSettingsImpl.DEFAULT,
            sdkContext.storageManager,
            // This is to preserve "capabilities" from the original IntelliJ LibraryInfo:
            customCapabilities = libraryInfo.capabilities
        )
        builtInsModule.setDependencies(listOf(builtInsModule))

        return builtInsModule.builtIns
    }

    return DefaultBuiltIns.Instance
}
