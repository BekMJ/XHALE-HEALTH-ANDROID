package com.xhale.health.feature.home

import android.content.Context
import com.xhale.health.core.ble.AndroidBleRepository
import com.xhale.health.core.ble.BleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HomeProvidesModule {
    @Provides
    @Singleton
    fun provideBleRepository(@ApplicationContext context: Context): BleRepository = AndroidBleRepository(context)
}


