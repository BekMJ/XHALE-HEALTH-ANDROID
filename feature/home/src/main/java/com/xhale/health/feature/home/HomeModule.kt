package com.xhale.health.feature.home

// BleRepository is provided in core:ble BleModule. This file intentionally left minimal.
// Keeping module placeholder if future Home-specific providers are needed.
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object HomeProvidesModule

