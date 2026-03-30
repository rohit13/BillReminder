package com.billreminder.app.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import com.billreminder.app.repository.BillRepository
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

enum class BillFilter {
    ALL, DUE, PAID, OVERDUE, NEEDS_REVIEW
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BillRepository(application)

    private val _filter = MutableLiveData(BillFilter.ALL)
    val filter: LiveData<BillFilter> = _filter

    private val visibleBills: LiveData<List<Bill>> = repository.getVisibleBills()

    val filteredBills: LiveData<List<Bill>> = _filter.switchMap { currentFilter ->
        visibleBills.map { bills ->
            when (currentFilter) {
                BillFilter.ALL -> bills
                BillFilter.DUE -> bills.filter { it.status != BillStatus.PAID && it.status != BillStatus.NEEDS_REVIEW && !it.isOverdue() }
                BillFilter.PAID -> bills.filter { it.status == BillStatus.PAID }
                BillFilter.OVERDUE -> bills.filter { it.isOverdue() }
                BillFilter.NEEDS_REVIEW -> bills.filter { it.status == BillStatus.NEEDS_REVIEW }
            }
        }
    }

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private val _syncProgress = MutableLiveData<Boolean>(false)
    val syncProgress: LiveData<Boolean> = _syncProgress

    fun setFilter(filter: BillFilter) {
        _filter.value = filter
    }

    fun syncEmails() {
        viewModelScope.launch {
            _syncProgress.value = true
            _uiState.value = UiState.Loading
            val result = repository.syncFromGmail()
            _syncProgress.value = false
            _uiState.value = if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                UiState.Success("Sync complete. Found $count new bills.")
            } else {
                UiState.Error("Sync failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun addToCalendar(bill: Bill) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            val result = repository.addToCalendar(bill)
            _uiState.value = if (result.isSuccess) {
                UiState.Success("Added to Google Calendar!")
            } else {
                UiState.Error("Failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun markAsPaid(bill: Bill) {
        viewModelScope.launch {
            repository.markAsPaid(bill)
            _uiState.value = UiState.Success("Marked as paid!")
        }
    }

    fun deleteBill(bill: Bill) {
        viewModelScope.launch {
            repository.deleteBill(bill)
        }
    }

    fun addManualBill(bill: Bill) {
        viewModelScope.launch {
            repository.insertBill(bill)
            _uiState.value = UiState.Success("Bill added!")
        }
    }

    fun resetAndResync() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            val result = repository.resetAndResync()
            _uiState.value = if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                UiState.Success("Reset complete. Found $count new bills.")
            } else {
                UiState.Error("Reset failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun confirmBill(bill: Bill) {
        viewModelScope.launch {
            repository.confirmBill(bill)
            _uiState.value = UiState.Success("Bill confirmed!")
        }
    }

    fun dismissBill(bill: Bill) {
        viewModelScope.launch {
            repository.dismissBill(bill)
            _uiState.value = UiState.Success("Bill dismissed.")
        }
    }

    fun clearState() {
        _uiState.value = UiState.Idle
    }
}
