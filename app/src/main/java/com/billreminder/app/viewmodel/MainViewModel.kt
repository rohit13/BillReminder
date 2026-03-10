package com.billreminder.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BillRepository(application)

    val allBills = repository.allBills
    val activeBills = repository.activeBills

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private val _syncProgress = MutableLiveData<Boolean>(false)
    val syncProgress: LiveData<Boolean> = _syncProgress

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

    fun clearState() {
        _uiState.value = UiState.Idle
    }
}
