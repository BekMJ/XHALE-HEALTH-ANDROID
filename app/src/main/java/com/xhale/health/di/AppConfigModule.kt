package com.xhale.health.di

import com.xhale.health.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides
    @Named("firebase_enabled")
    fun provideFirebaseEnabled(): Boolean = BuildConfig.FIREBASE_ENABLED
}


