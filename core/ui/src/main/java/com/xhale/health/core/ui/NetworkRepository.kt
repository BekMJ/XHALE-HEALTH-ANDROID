package com.xhale.health.core.ui

import kotlinx.coroutines.flow.Flow

interface NetworkRepository {
    val isConnected: Flow<Boolean>
    fun hasActiveConnection(): Boolean
}
