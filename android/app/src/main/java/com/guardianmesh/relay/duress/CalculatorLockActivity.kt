package com.guardianmesh.relay.duress

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.GuardianApp
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.trigger.SosTrigger
import com.guardianmesh.relay.ui.HomeActivity
import com.guardianmesh.relay.ui.theme.GuardianTheme
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * **The duress PIN screen.** It is a working calculator, and that is the entire trick.
 *
 * Someone who takes the phone and demands it be opened sees a calculator. Typing digits
 * into a calculator is the most ordinary thing in the world, so entering a PIN raises
 * nothing. Press `=` and:
 *
 *  - **real PIN** → the app opens;
 *  - **duress PIN** → an SOS goes out silently, with location, over whichever route
 *    works, and the calculator just… calculates. No flash, no toast, no notification,
 *    nothing in Recents. The screen the attacker is looking at does not change;
 *  - **anything else** → it evaluates as arithmetic, like any calculator.
 *
 * The arithmetic is real, not a prop: `7*8=` gives 56. If it were fake, one poke would
 * give the game away.
 *
 * Reached from [com.guardianmesh.relay.ui.LaunchActivity] whenever a PIN is configured.
 */
class CalculatorLockActivity : ComponentActivity() {

    private companion object {
        const val TAG = "DURESS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guardian.ensureInit(this)

        setContent {
            GuardianTheme {
                CalculatorScreen(onEquals = ::onPinCandidate)
            }
        }
    }

    /**
     * Called with whatever digits were on screen when `=` was pressed. Returns true if
     * it was a PIN (so the calculator shouldn't also evaluate it), false to carry on
     * being a calculator.
     *
     * The work is async (Room), so this returns immediately and the calculator evaluates
     * the expression as normal — which is the right cover either way.
     */
    private fun onPinCandidate(candidate: String): Boolean {
        if (!PinVault.isPlausible(candidate)) return false

        lifecycleScope.launch {
            val repo = GuardianApp.repository(this@CalculatorLockActivity)
            when (PinVault.verify(candidate, repo.current())) {
                PinVault.Outcome.REAL -> {
                    AppLog.i(TAG, "Unlocked")
                    startActivity(
                        Intent(this@CalculatorLockActivity, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    finish()
                }

                PinVault.Outcome.DURESS -> {
                    // Nothing on screen changes. Nothing is logged that names this as a
                    // duress unlock in a way a bystander could see; AppLog only reaches
                    // the in-app debug console and logcat.
                    SosTrigger.fire(
                        context = applicationContext,
                        source = SosTrigger.Source.DURESS_PIN,
                        silent = true,
                        note = "Duress PIN entered — user may be under direct coercion"
                    )
                }

                PinVault.Outcome.WRONG -> Unit // just a number someone typed
            }
        }
        return false
    }
}

// ───────────────────────────── the calculator ─────────────────────────────

/**
 * A plain four-function calculator. [onEquals] gets first look at the current entry when
 * `=` is pressed, before any arithmetic. It returns false in every normal case — the PIN
 * check has to hit the database, so it runs asynchronously and the calculator carries on
 * evaluating meanwhile, which is exactly the behaviour the cover needs.
 */
@Composable
private fun CalculatorScreen(onEquals: (String) -> Boolean) {
    var display by remember { mutableStateOf("0") }
    var accumulator by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var freshEntry by remember { mutableStateOf(true) }

    val format = remember { DecimalFormat("#.##########") }

    fun clear() {
        display = "0"; accumulator = null; pendingOp = null; freshEntry = true
    }

    fun digit(d: String) {
        display = when {
            freshEntry -> d
            display == "0" && d != "." -> d
            d == "." && display.contains('.') -> display
            display.length >= 12 -> display
            else -> display + d
        }
        freshEntry = false
    }

    fun applyPending(next: Double): Double {
        val left = accumulator ?: return next
        return when (pendingOp) {
            "+" -> left + next
            "−" -> left - next
            "×" -> left * next
            "÷" -> if (next == 0.0) Double.NaN else left / next
            else -> next
        }
    }

    fun operator(op: String) {
        val current = display.toDoubleOrNull() ?: 0.0
        accumulator = if (pendingOp == null || freshEntry) current else applyPending(current)
        display = accumulator?.let { if (it.isNaN()) "Error" else format.format(it) } ?: "0"
        pendingOp = op
        freshEntry = true
    }

    fun equals() {
        // The PIN check happens before the arithmetic, and only ever on plain digits.
        if (pendingOp == null && onEquals(display)) return

        val current = display.toDoubleOrNull() ?: 0.0
        val result = applyPending(current)
        display = if (result.isNaN()) "Error" else format.format(result)
        accumulator = null
        pendingOp = null
        freshEntry = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Text(
                    text = display,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(Modifier.height(12.dp))

            val rows = listOf(
                listOf("C", "±", "%", "÷"),
                listOf("7", "8", "9", "×"),
                listOf("4", "5", "6", "−"),
                listOf("1", "2", "3", "+"),
                listOf("0", ".", "=")
            )

            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { key ->
                        CalcKey(
                            label = key,
                            // "0" takes the double width a calculator's zero always has.
                            weight = if (key == "0") 2f else 1f,
                            isOperator = key in setOf("÷", "×", "−", "+", "="),
                            isFunction = key in setOf("C", "±", "%")
                        ) {
                            when (key) {
                                "C" -> clear()
                                "±" -> display = display.toDoubleOrNull()
                                    ?.let { format.format(-it) } ?: display
                                "%" -> {
                                    display = display.toDoubleOrNull()
                                        ?.let { format.format(it / 100) } ?: display
                                    freshEntry = false
                                }
                                "=" -> equals()
                                "÷", "×", "−", "+" -> operator(key)
                                else -> digit(key)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RowScope.CalcKey(
    label: String,
    weight: Float,
    isOperator: Boolean,
    isFunction: Boolean,
    onClick: () -> Unit
) {
    val colors = when {
        isOperator -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        isFunction -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        else -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = colors,
        modifier = Modifier
            .weight(weight)
            .then(if (weight == 1f) Modifier.aspectRatio(1f) else Modifier.aspectRatio(2.1f))
    ) {
        Text(text = label, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}
