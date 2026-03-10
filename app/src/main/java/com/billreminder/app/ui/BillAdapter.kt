package com.billreminder.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvDueDate: TextView = itemView.findViewById(R.id.tvDueDate)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val btnCalendar: Button = itemView.findViewById(R.id.btnCalendar)
        private val btnReminder: Button = itemView.findViewById(R.id.btnReminder)
        private val btnPaid: Button = itemView.findViewById(R.id.btnPaid)

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)

        fun bind(bill: Bill) {
            tvTitle.text = bill.description.ifBlank { bill.subject }
            tvSender.text = bill.sender
            tvAmount.text = if (bill.amount > 0) currencyFormat.format(bill.amount) else "Amount N/A"
            tvDueDate.text = "Due: ${dateFormat.format(bill.getDueDateAsDate())}"
            tvCategory.text = bill.category

            val status = bill.getComputedStatus()
            tvStatus.text = status.name.replace("_", " ")

            // Color coding
            val (statusColor, cardAlpha) = when (status) {
                BillStatus.OVERDUE -> Pair(Color.parseColor("#F44336"), 1.0f)
                BillStatus.DUE_SOON -> Pair(Color.parseColor("#FF9800"), 1.0f)
                BillStatus.PAID -> Pair(Color.parseColor("#4CAF50"), 0.7f)
                BillStatus.PENDING -> Pair(Color.parseColor("#2196F3"), 1.0f)
            }
            tvStatus.setTextColor(statusColor)
            card.alpha = cardAlpha

            // Calendar button
            btnCalendar.text = if (bill.calendarEventId != null) "📅 Added" else "📅 Add to Calendar"
            btnCalendar.isEnabled = bill.calendarEventId == null

            // Reminder button
            btnReminder.text = if (bill.reminderSet) "🔔 Set" else "🔔 Remind Me"
            btnReminder.isEnabled = status != BillStatus.PAID && !bill.reminderSet

            // Paid button
            btnPaid.visibility = if (status == BillStatus.PAID) View.GONE else View.VISIBLE

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
