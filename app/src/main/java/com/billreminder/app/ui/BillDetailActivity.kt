package com.billreminder.app.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.billreminder.app.databinding.ActivityBillDetailBinding
import com.billreminder.app.model.Bill
import com.billreminder.app.repository.BillRepository
import com.billreminder.app.util.ReminderScheduler
import com.billreminder.app.viewmodel.MainViewModel
import com.billreminder.app.viewmodel.UiState
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

class BillDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_ID = "bill_id"
    }

    private lateinit var binding: ActivityBillDetailBinding
    private val viewModel: MainViewModel by viewModels()
    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
    private var currentBill: Bill? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBillDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val billId = intent.getLongExtra(EXTRA_BILL_ID, -1)
        if (billId == -1L) { finish(); return }

        loadBill(billId)

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Success -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                    viewModel.clearState()
                    loadBill(billId) // refresh
                }
                is UiState.Error -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    viewModel.clearState()
                }
                else -> {}
            }
        }
    }

    private fun loadBill(billId: Long) {
        lifecycleScope.launch {
            val bill = BillRepository(this@BillDetailActivity).getBillById(billId)
            bill?.let {
                currentBill = it
                bindBill(it)
            }
        }
    }

    private fun bindBill(bill: Bill) {
        supportActionBar?.title = bill.description
        binding.tvDetailTitle.text = bill.description
        binding.tvDetailSender.text = "From: ${bill.sender} (${bill.senderEmail})"
        binding.tvDetailAmount.text = if (bill.amount > 0) currencyFormat.format(bill.amount) else "Amount not detected"
        binding.tvDetailDueDate.text = "Due: ${dateFormat.format(bill.getDueDateAsDate())}"
        binding.tvDetailReceived.text = "Received: ${dateFormat.format(bill.getReceivedDateAsDate())}"
        binding.tvDetailCategory.text = "Category: ${bill.category}"
        binding.tvDetailStatus.text = "Status: ${bill.getComputedStatus().name}"
        binding.tvDetailSnippet.text = bill.rawEmailSnippet

        binding.btnDetailCalendar.text = if (bill.calendarEventId != null) "✅ Added to Calendar" else "📅 Add to Google Calendar"
        binding.btnDetailCalendar.isEnabled = bill.calendarEventId == null
        binding.btnDetailCalendar.setOnClickListener {
            viewModel.addToCalendar(bill)
        }

        binding.btnDetailReminder.text = if (bill.reminderSet) "🔔 Reminder Set" else "🔔 Set Reminder"
        binding.btnDetailReminder.isEnabled = !bill.reminderSet
        binding.btnDetailReminder.setOnClickListener {
            ReminderScheduler.scheduleReminder(this, bill)
            Toast.makeText(this, "Reminder set!", Toast.LENGTH_SHORT).show()
        }

        binding.btnDetailPaid.setOnClickListener {
            viewModel.markAsPaid(bill)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
