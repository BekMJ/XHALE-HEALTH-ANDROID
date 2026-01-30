package com.xhale.health.di

import android.content.Context
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
        // google_app_id is generated only when Google Services is applied.
        val resId = context.resources.getIdentifier("google_app_id", "string", context.packageName)
        return resId != 0
    }
}


