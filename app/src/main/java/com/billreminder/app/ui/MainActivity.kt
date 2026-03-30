package com.billreminder.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.billreminder.app.R
import com.billreminder.app.auth.AuthManager
import com.billreminder.app.databinding.ActivityMainBinding
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import com.billreminder.app.repository.BillRepository
import com.billreminder.app.util.ReminderScheduler
import com.billreminder.app.viewmodel.BillFilter
import com.billreminder.app.viewmodel.MainViewModel
import com.billreminder.app.viewmodel.UiState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), BillAdapter.BillClickListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: BillAdapter
    private lateinit var repository: BillRepository

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handle result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repository = BillRepository(this)
        setupRecyclerView()
        setupFilters()
        observeViewModel()
        requestPermissionsIfNeeded()

        val account = GoogleSignIn.getLastSignedInAccount(this)
        supportActionBar?.subtitle = account?.email

        binding.fabAddBill.setOnClickListener {
            startActivity(Intent(this, AddBillActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.syncEmails()
        }

        viewModel.syncEmails()
    }

    private fun setupRecyclerView() {
        adapter = BillAdapter(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupFilters() {
        binding.filterChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipDue -> BillFilter.DUE
                R.id.chipPaid -> BillFilter.PAID
                R.id.chipOverdue -> BillFilter.OVERDUE
                R.id.chipReview -> BillFilter.NEEDS_REVIEW
                else -> BillFilter.ALL
            }
            viewModel.setFilter(filter)
        }
    }

    private fun observeViewModel() {
        viewModel.filteredBills.observe(this) { bills ->
            adapter.submitList(bills)
            binding.emptyView.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
            
            val pending = bills.count { it.status != BillStatus.PAID && !it.isOverdue() }
            val overdue = bills.count { it.isOverdue() }
            val paid = bills.count { it.status == BillStatus.PAID }
            
            val review = bills.count { it.status == BillStatus.NEEDS_REVIEW }
            binding.tvSummary.text = when(viewModel.filter.value) {
                BillFilter.PAID -> "$paid paid bills"
                BillFilter.OVERDUE -> "$overdue overdue bills"
                BillFilter.DUE -> "$pending due bills"
                BillFilter.NEEDS_REVIEW -> "$review bills need your review"
                else -> "$pending due, $overdue overdue" + if (review > 0) ", $review to review" else ""
            }
        }

        viewModel.uiState.observe(this) { state ->
            binding.swipeRefresh.isRefreshing = state == UiState.Loading
            when (state) {
                is UiState.Success -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                    viewModel.clearState()
                }
                is UiState.Error -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    viewModel.clearState()
                }
                else -> {}
            }
        }
    }

    override fun onBillClick(bill: Bill) {
        val intent = Intent(this, BillDetailActivity::class.java)
        intent.putExtra(BillDetailActivity.EXTRA_BILL_ID, bill.id)
        startActivity(intent)
    }

    override fun onAddToCalendar(bill: Bill) {
        if (bill.calendarEventId != null) {
            Toast.makeText(this, "Already added to calendar", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.addToCalendar(bill)
    }

    override fun onSetReminder(bill: Bill) {
        ReminderScheduler.scheduleReminder(this, bill, daysBefore = 1)
        lifecycleScope.launch { repository.setReminderSet(bill.id, true) }
        Toast.makeText(this, "Reminder set for 1 day before due date", Toast.LENGTH_SHORT).show()
    }

    override fun onMarkAsPaid(bill: Bill) {
        AlertDialog.Builder(this)
            .setTitle("Mark as Paid")
            .setMessage("Mark this bill as paid?")
            .setPositiveButton("Yes") { _, _ -> viewModel.markAsPaid(bill) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onConfirmBill(bill: Bill) {
        viewModel.confirmBill(bill)
    }

    override fun onDismissBill(bill: Bill) {
        AlertDialog.Builder(this)
            .setTitle("Dismiss Bill")
            .setMessage("This email will not appear again. Continue?")
            .setPositiveButton("Dismiss") { _, _ -> viewModel.dismissBill(bill) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sync -> { viewModel.syncEmails(); true }
            R.id.action_reset_resync -> { showResetDialog(); true }
            R.id.action_sign_out -> { showSignOutDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset & Resync")
            .setMessage("This will delete all local bills and the AI classification cache, then re-fetch from Gmail. Use this if emails are incorrectly classified.\n\nContinue?")
            .setPositiveButton("Reset") { _, _ -> viewModel.resetAndResync() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSignOutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Sign out of your Google account?")
            .setPositiveButton("Sign Out") { _, _ ->
                AuthManager.signOut(this) {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("Enable Exact Alarms")
                    .setMessage("To set bill reminders, please allow exact alarms in settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                    .setNegativeButton("Skip", null)
                    .show()
            }
        }
    }
}
