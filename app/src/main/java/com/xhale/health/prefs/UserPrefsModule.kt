package com.xhale.health.prefs

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UserPrefsModule {
    @Provides
    @Singleton
    fun provideUserPrefsRepository(@ApplicationContext context: Context): UserPrefsRepository = UserPrefsRepository(context)
}


