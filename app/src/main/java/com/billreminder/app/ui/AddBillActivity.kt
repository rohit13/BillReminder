package com.billreminder.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.billreminder.app.databinding.ActivityAddBillBinding
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import com.billreminder.app.viewmodel.MainViewModel
import com.billreminder.app.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddBillActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddBillBinding
    private val viewModel: MainViewModel by viewModels()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private var selectedDueDateMs = System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000)

    private val categories = arrayOf(
        "Electricity", "Water", "Internet", "Phone", "Gas",
        "Credit Card", "Insurance", "Streaming", "Rent", "Mortgage", "Medical", "Other"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddBillBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Add Bill Manually"

        // Category spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategory.adapter = adapter

        // Due date picker
        binding.btnPickDate.text = dateFormat.format(selectedDueDateMs)
        binding.btnPickDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = selectedDueDateMs }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d)
                selectedDueDateMs = cal.timeInMillis
                binding.btnPickDate.text = dateFormat.format(selectedDueDateMs)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnSaveBill.setOnClickListener {
            saveBill()
        }

        viewModel.uiState.observe(this) { state ->
            if (state is UiState.Success) {
                Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                viewModel.clearState()
                finish()
            }
        }
    }

    private fun saveBill() {
        val title = binding.etTitle.text.toString().trim()
        val sender = binding.etSender.text.toString().trim()
        val amountStr = binding.etAmount.text.toString().trim()

        if (title.isBlank()) {
            binding.etTitle.error = "Required"
            return
        }

        val amount = amountStr.toDoubleOrNull() ?: 0.0
        val category = categories[binding.spinnerCategory.selectedItemPosition]

        val bill = Bill(
            description = title,
            sender = sender,
            amount = amount,
            dueDate = selectedDueDateMs,
            receivedDate = System.currentTimeMillis(),
            category = category,
            status = BillStatus.PENDING
        )

        viewModel.addManualBill(bill)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
