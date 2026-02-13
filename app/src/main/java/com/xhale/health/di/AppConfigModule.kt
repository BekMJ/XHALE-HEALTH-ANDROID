package com.xhale.health.di

import android.content.Context
import com.google.firebase.FirebaseOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides
    @Named("firebase_enabled")
    fun provideFirebaseEnabled(@ApplicationContext context: Context): Boolean {
        // Firebase is enabled only when a complete default option set is present.
        return FirebaseOptions.fromResource(context) != null
    }
}


