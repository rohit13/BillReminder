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
        /** Called when the user confirms a NEEDS_REVIEW bill as a real invoice. */
        fun onConfirmBill(bill: Bill)
        /** Called when the user dismisses a NEEDS_REVIEW bill as not relevant. */
        fun onDismissBill(bill: Bill)
    }

    companion object {
        private const val VIEW_TYPE_NORMAL = 0
        private const val VIEW_TYPE_REVIEW = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).status == BillStatus.NEEDS_REVIEW) VIEW_TYPE_REVIEW
        else VIEW_TYPE_NORMAL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BillViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_REVIEW) R.layout.item_bill_review
                       else R.layout.item_bill
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return BillViewHolder(view, viewType)
    }

    override fun onBindViewHolder(holder: BillViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BillViewHolder(itemView: View, private val viewType: Int) :
        RecyclerView.ViewHolder(itemView) {

        private val card: CardView = itemView.findViewById(R.id.card)
        private val tvServiceProvider: TextView = itemView.findViewById(R.id.tvServiceProvider)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvDueDate: TextView = itemView.findViewById(R.id.tvDueDate)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val btnViewMail: Button = itemView.findViewById(R.id.btnViewMail)

        // Normal-only views (null for review cards)
        private val btnCalendar: Button? = itemView.findViewById(R.id.btnCalendar)
        private val btnReminder: Button? = itemView.findViewById(R.id.btnReminder)
        private val btnPaid: Button? = itemView.findViewById(R.id.btnPaid)

        // Review-only views (null for normal cards)
        private val btnConfirm: Button? = itemView.findViewById(R.id.btnConfirm)
        private val btnDismiss: Button? = itemView.findViewById(R.id.btnDismiss)
        private val tvConfidenceHint: TextView? = itemView.findViewById(R.id.tvConfidenceHint)

        private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)

        fun bind(bill: Bill) {
            tvServiceProvider.text = bill.sender.ifBlank { "Unknown Provider" }
            tvAmount.text = if (bill.amount > 0) currencyFormat.format(bill.amount) else "---"
            tvDueDate.text = "Due: ${dateFormat.format(bill.getDueDateAsDate())}"
            tvCategory.text = bill.category

            if (viewType == VIEW_TYPE_REVIEW) {
                bindReviewCard(bill)
            } else {
                bindNormalCard(bill)
            }

            // View Email — shared by both card types
            btnViewMail.setOnClickListener {
                if (bill.emailId.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://mail.google.com/mail/u/0/#all/${bill.emailId}")
                        setPackage("com.google.android.gm")
                    }
                    try {
                        itemView.context.startActivity(intent)
                    } catch (e: Exception) {
                        itemView.context.startActivity(Intent(Intent.ACTION_VIEW, intent.data))
                    }
                } else {
                    Toast.makeText(itemView.context, "No source email available", Toast.LENGTH_SHORT).show()
                }
            }

            itemView.setOnClickListener { listener.onBillClick(bill) }
        }

        private fun bindNormalCard(bill: Bill) {
            val status = bill.getComputedStatus()
            tvStatus.text = status.name.replace("_", " ")

            val statusColor = when (status) {
                BillStatus.OVERDUE -> Color.parseColor("#E53935")
                BillStatus.DUE_SOON -> Color.parseColor("#FB8C00")
                BillStatus.PAID -> Color.parseColor("#43A047")
                BillStatus.PENDING -> Color.parseColor("#1E88E5")
                BillStatus.NEEDS_REVIEW -> Color.parseColor("#F9A825") // shouldn't reach here
            }
            tvStatus.setTextColor(statusColor)
            card.alpha = if (status == BillStatus.PAID) 0.7f else 1.0f

            btnCalendar?.let { btn ->
                btn.isEnabled = bill.calendarEventId == null
                btn.alpha = if (bill.calendarEventId == null) 1.0f else 0.5f
                btn.setOnClickListener { listener.onAddToCalendar(bill) }
            }
            btnReminder?.let { btn ->
                btn.isEnabled = status != BillStatus.PAID && !bill.reminderSet
                btn.alpha = if (btn.isEnabled) 1.0f else 0.5f
                btn.setOnClickListener { listener.onSetReminder(bill) }
            }
            btnPaid?.let { btn ->
                btn.visibility = if (status == BillStatus.PAID) View.GONE else View.VISIBLE
                btn.setOnClickListener { listener.onMarkAsPaid(bill) }
            }
        }

        private fun bindReviewCard(bill: Bill) {
            // Confidence hint (e.g. "AI confidence: 72%" — too uncertain to auto-accept)
            tvConfidenceHint?.text = if (bill.geminiConfidence >= 0) {
                val pct = (bill.geminiConfidence * 100).toInt()
                "AI confidence: $pct% — please verify this is a real bill"
            } else {
                "Please verify this is a real bill"
            }

            btnConfirm?.setOnClickListener { listener.onConfirmBill(bill) }
            btnDismiss?.setOnClickListener { listener.onDismissBill(bill) }
        }
    }

    class BillDiffCallback : DiffUtil.ItemCallback<Bill>() {
        override fun areItemsTheSame(oldItem: Bill, newItem: Bill) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Bill, newItem: Bill) = oldItem == newItem
    }
}
