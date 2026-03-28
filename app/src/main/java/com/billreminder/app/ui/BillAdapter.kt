package com.billreminder.app.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.billreminder.app.R
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

class BillAdapter(private val listener: BillClickListener) :
    ListAdapter<Bill, BillAdapter.BillViewHolder>(BillDiffCallback()) {

    interface BillClickListener {
        fun onBillClick(bill: Bill)
        fun onAddToCalendar(bill: Bill)
        fun onSetReminder(bill: Bill)
        fun onMarkAsPaid(bill: Bill)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BillViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bill, parent, false)
        return BillViewHolder(view)
    }

    override fun onBindViewHolder(holder: BillViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BillViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: CardView = itemView.findViewById(R.id.card)
        private val tvServiceProvider: TextView = itemView.findViewById(R.id.tvServiceProvider)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvDueDate: TextView = itemView.findViewById(R.id.tvDueDate)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val btnCalendar: Button = itemView.findViewById(R.id.btnCalendar)
        private val btnReminder: Button = itemView.findViewById(R.id.btnReminder)
        private val btnViewMail: Button = itemView.findViewById(R.id.btnViewMail)
        private val btnPaid: Button = itemView.findViewById(R.id.btnPaid)

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)

        fun bind(bill: Bill) {
            // Service Provider - using sender name as service provider
            tvServiceProvider.text = bill.sender.ifBlank { "Unknown Provider" }
            tvAmount.text = if (bill.amount > 0) currencyFormat.format(bill.amount) else "---"
            tvDueDate.text = "Due: ${dateFormat.format(bill.getDueDateAsDate())}"
            tvCategory.text = bill.category

            val status = bill.getComputedStatus()
            tvStatus.text = status.name.replace("_", " ")

            // Color coding based on status
            val statusColor = when (status) {
                BillStatus.OVERDUE -> Color.parseColor("#E53935") // Red
                BillStatus.DUE_SOON -> Color.parseColor("#FB8C00") // Orange
                BillStatus.PAID -> Color.parseColor("#43A047") // Green
                BillStatus.PENDING -> Color.parseColor("#1E88E5") // Blue
            }
            tvStatus.setTextColor(statusColor)
            
            // Transparency for paid bills
            card.alpha = if (status == BillStatus.PAID) 0.7f else 1.0f

            // Button states
            btnCalendar.isEnabled = bill.calendarEventId == null
            btnCalendar.alpha = if (bill.calendarEventId == null) 1.0f else 0.5f
            
            btnReminder.isEnabled = status != BillStatus.PAID && !bill.reminderSet
            btnReminder.alpha = if (btnReminder.isEnabled) 1.0f else 0.5f

            btnPaid.visibility = if (status == BillStatus.PAID) View.GONE else View.VISIBLE

            // View Email Action
            btnViewMail.setOnClickListener {
                if (bill.emailId.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse("https://mail.google.com/mail/u/0/#all/${bill.emailId}")
                    intent.setPackage("com.google.android.gm")
                    try {
                        itemView.context.startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback to browser if Gmail app is not installed
                        itemView.context.startActivity(Intent(Intent.ACTION_VIEW, intent.data))
                    }
                } else {
                    Toast.makeText(itemView.context, "No source email available", Toast.LENGTH_SHORT).show()
                }
            }

            itemView.setOnClickListener { listener.onBillClick(bill) }
            btnCalendar.setOnClickListener { listener.onAddToCalendar(bill) }
            btnReminder.setOnClickListener { listener.onSetReminder(bill) }
            btnPaid.setOnClickListener { listener.onMarkAsPaid(bill) }
        }
    }

    class BillDiffCallback : DiffUtil.ItemCallback<Bill>() {
        override fun areItemsTheSame(oldItem: Bill, newItem: Bill) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Bill, newItem: Bill) = oldItem == newItem
    }
}
