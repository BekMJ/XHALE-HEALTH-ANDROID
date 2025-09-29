package com.xhale.health.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.core.firebase.TrendsRepository
import com.xhale.health.core.firebase.DailyMedian
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrendsUiState(
    val daily: List<DailyMedian> = emptyList(),
    val streakDays: Int = 0
)

@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val repo: TrendsRepository
) : ViewModel() {
    private val _state = MutableStateFlow(TrendsUiState())
    val state: StateFlow<TrendsUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val res = repo.fetchLast7Days()
            res.onSuccess {
                _state.value = TrendsUiState(daily = it.daily, streakDays = it.smokeFreeStreakDays)
            }
        }
    }
}


