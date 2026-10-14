package com.ultrawidecam

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.ultrawidecam.databinding.ActivityPaymentHubBinding
import com.ultrawidecam.databinding.ItemPaymentHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PaymentHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentHubBinding
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }

    private val galleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            processQrFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeaderActions()
        setupBentoActions()
        setupDirectVpaInput()
        loadPaymentHistory()
    }

    override fun onResume() {
        super.onResume()
        detectClipboardVpa()
        loadPaymentHistory()
    }

    private fun setupHeaderActions() {
        binding.btnHubBack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            finish()
        }

        binding.btnHubClearHistory.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            AlertDialog.Builder(this)
                .setTitle("Clear Payment History?")
                .setMessage("This will remove all local transaction logs. Your payment apps will not be affected.")
                .setPositiveButton("Clear") { _, _ ->
                    PaymentHistoryHelper.clearRecords(this)
                    loadPaymentHistory()
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupBentoActions() {
        binding.tileScanGallery.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            galleryPickerLauncher.launch("image/*")
        }

        binding.tileDirectPay.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.etVpaInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etVpaInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupDirectVpaInput() {
        binding.btnSubmitVpa.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val rawInput = binding.etVpaInput.text?.toString()?.trim() ?: ""
            if (rawInput.isBlank()) {
                Toast.makeText(this, "Please enter a valid UPI ID or number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetVpa = if (!rawInput.contains("@") && rawInput.matches(Regex("^\\d{10}$"))) {
                "$rawInput@upi"
            } else {
                rawInput
            }

            val vpaPattern = Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z0-9]{2,64}$")
            if (!targetVpa.matches(vpaPattern)) {
                Toast.makeText(this, "Please enter a valid UPI ID (e.g. name@bank)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            primeClipboard(targetVpa)
            PaymentHistoryHelper.addRecord(this, targetVpa, targetVpa, "")
            val cleanUrl = Uri.Builder()
                .scheme("upi")
                .authority("pay")
                .appendQueryParameter("pa", targetVpa)
                .appendQueryParameter("cu", "INR")
                .build()
                .toString()
            launchDirectGPay(targetVpa, cleanUrl)
        }
    }

    private fun detectClipboardVpa() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
                if (clipText.isNotBlank() && (clipText.contains("@") || clipText.matches(Regex("^\\d{10}$"))) && clipText.length < 50) {
                    val displayVpa = if (clipText.length > 22) clipText.take(20) + "..." else clipText
                    binding.chipClipboardPaste.text = "📋 Paste: $displayVpa"
                    binding.chipClipboardPaste.visibility = View.VISIBLE
                    binding.chipClipboardPaste.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        binding.etVpaInput.setText(clipText)
                        binding.etVpaInput.setSelection(clipText.length)
                    }
                    return
                }
            }
        } catch (_: Exception) {}
        binding.chipClipboardPaste.visibility = View.GONE
    }

    private fun processQrFromUri(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    var found = false
                    for (barcode in barcodes) {
                        val value = barcode.rawValue ?: continue
                        if (isPaymentQr(value)) {
                            found = true
                            handleDetectedQr(value)
                            break
                        }
                    }
                    if (!found) {
                        Toast.makeText(this, "No valid payment QR found in selected image", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to read QR: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDetectedQr(upiUrl: String) {
        val trimmed = upiUrl.trim()
        var payeeVpa = ""
        var payeeName = "Merchant"
        var amount = ""

        if (trimmed.startsWith("upi://", ignoreCase = true) ||
            trimmed.startsWith("paytm://", ignoreCase = true) ||
            trimmed.startsWith("phonepe://", ignoreCase = true)
        ) {
            val uri = try { Uri.parse(trimmed) } catch (_: Exception) { null }
            payeeVpa = uri?.getQueryParameter("pa") ?: ""
            payeeName = Uri.decode(uri?.getQueryParameter("pn") ?: "").ifBlank { "Merchant" }
            amount = uri?.getQueryParameter("am") ?: ""
        } else if (trimmed.matches(Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z0-9]{2,64}$"))) {
            payeeVpa = trimmed
            payeeName = trimmed
        }

        // 1. Auto-prime clipboard
        primeClipboard(payeeVpa)

        // 2. Save to local payment history
        PaymentHistoryHelper.addRecord(this, payeeName, payeeVpa, amount)

        // 3. Directly launch Google Pay with copied UPI ID
        launchDirectGPay(payeeVpa, trimmed)

        loadPaymentHistory()
    }

    private fun loadPaymentHistory() {
        val records = PaymentHistoryHelper.getRecords(this)
        binding.llHistoryRecords.removeAllViews()

        if (records.isEmpty()) {
            binding.llEmptyHistory.visibility = View.VISIBLE
            binding.llHistoryRecords.visibility = View.GONE
            binding.tvHistoryCount.text = "0 records"
            return
        }

        binding.llEmptyHistory.visibility = View.GONE
        binding.llHistoryRecords.visibility = View.VISIBLE
        binding.tvHistoryCount.text = "${records.size} records"

        val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

        for (record in records) {
            val itemBinding = ItemPaymentHistoryBinding.inflate(
                LayoutInflater.from(this),
                binding.llHistoryRecords,
                false
            )

            itemBinding.tvHistoryAvatar.text = record.payeeName.firstOrNull()?.uppercaseChar()?.toString() ?: "M"
            itemBinding.tvHistoryPayee.text = record.payeeName
            itemBinding.tvHistoryDate.text = dateFormat.format(Date(record.timestamp))
            itemBinding.tvHistoryVpa.text = record.payeeVpa

            if (record.amount.isNotEmpty()) {
                itemBinding.tvHistoryAmount.visibility = View.VISIBLE
                itemBinding.tvHistoryAmount.text = "₹${record.amount}"
            } else {
                itemBinding.tvHistoryAmount.visibility = View.GONE
            }

            itemBinding.btnHistoryCopy.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                primeClipboard(record.payeeVpa)
                Toast.makeText(this, "Copied UPI ID: ${record.payeeVpa}", Toast.LENGTH_SHORT).show()
            }

            itemBinding.btnHistoryPayAgain.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                primeClipboard(record.payeeVpa)
                val cleanUrl = Uri.Builder()
                    .scheme("upi")
                    .authority("pay")
                    .appendQueryParameter("pa", record.payeeVpa)
                    .apply {
                        if (record.payeeName.isNotBlank() && record.payeeName != "Merchant") {
                            appendQueryParameter("pn", record.payeeName)
                        }
                        if (record.amount.isNotBlank()) {
                            appendQueryParameter("am", record.amount)
                        }
                        appendQueryParameter("cu", "INR")
                    }
                    .build()
                    .toString()
                launchDirectGPay(record.payeeVpa, cleanUrl)
            }

            binding.llHistoryRecords.addView(itemBinding.root)
        }
    }

    private fun primeClipboard(vpa: String) {
        if (vpa.isBlank()) return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("UPI ID", vpa))
        } catch (e: Exception) {
            Log.e("PaymentHubActivity", "Clipboard error: ${e.message}")
        }
    }

    /**
     * Directly launches the Google Pay app in its trusted native state with the VPA primed on clipboard,
     * avoiding 3rd-party intent payment blocks. Falls back to system chooser if GPay is not installed.
     */
    private fun launchDirectGPay(payeeVpa: String, fallbackUrl: String) {
        val gpayPackage = "com.google.android.apps.nbu.paisa.user"
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(gpayPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

            if (launchIntent != null) {
                val msg = if (payeeVpa.isNotBlank()) "Copied: $payeeVpa\nOpening Google Pay..." else "Opening Google Pay..."
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Google Pay not installed. Choose payment app.", Toast.LENGTH_SHORT).show()
                launchSystemUpiChooser(fallbackUrl)
            }
        } catch (e: Exception) {
            Log.e("PaymentHubActivity", "Failed to launch GPay: ${e.message}")
            launchSystemUpiChooser(fallbackUrl)
        }
    }

    private fun launchSystemUpiChooser(cleanUpiUrl: String) {
        try {
            val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUpiUrl))
            val chooserIntent = Intent.createChooser(baseIntent, "Pay using").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooserIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No UPI app found on device", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not launch payment app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isPaymentQr(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("upi://pay", ignoreCase = true) ||
                trimmed.startsWith("paytm://pay", ignoreCase = true) ||
                trimmed.startsWith("phonepe://pay", ignoreCase = true) ||
                trimmed.matches(Regex("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z0-9]{2,64}$"))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            barcodeScanner.close()
        } catch (_: Exception) {}
    }
}

// Inset fix: NestedScrollView viewport weight handling
