package com.xhale.health.feature.breath

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BreathModule {
    
    @Provides
    @Singleton
    fun provideCsvExportUtil(@ApplicationContext context: Context): CsvExportUtil {
        return CsvExportUtil(context)
    }

    @Provides
    @Singleton
    fun provideAnalyzeBreath(): AnalyzeBreathUseCase = AnalyzeBreathUseCase()
}
