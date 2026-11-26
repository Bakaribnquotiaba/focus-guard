package com.mossab.focusguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shown during the 15-minute lockout. It locks the screen again a few seconds after it appears,
 * unless you are solving the puzzle: 5 correct answers in a row end the lockout early.
 */
class LockoutActivity : Activity() {

    companion object {
        private const val LOCK_DELAY_MS = 6_000L
        private const val PUZZLE_IDLE_LOCK_MS = 90_000L

        fun show(c: Context) {
            try {
                c.startActivity(
                    Intent(c, LockoutActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timer: TextView
    private lateinit var reason: TextView
    private lateinit var solveButton: Button
    private lateinit var puzzleBox: LinearLayout
    private lateinit var progress: TextView
    private lateinit var question: TextView
    private lateinit var answer: EditText
    private lateinit var submit: Button
    private lateinit var feedback: TextView

    private var current: Puzzle.Question? = null
    private var solved = 0
    private var solving = false
    private var waitUntil = 0L

    private val lockAgain = Runnable {
        if (Penalty.isLockedOut(this)) Penalty.lockScreen(this)
    }

    private val update = object : Runnable {
        override fun run() {
            val ms = Penalty.remainingMs(this@LockoutActivity)
            if (ms <= 0) {
                finish()
                return
            }
            val s = ms / 1000
            timer.text = String.format("%02d:%02d", s / 60, s % 60)
            refreshWait()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        box.addView(bigText("🔒", 64f))
        box.addView(bigText("Phone locked", 30f, bold = true))
        timer = bigText("", 52f, bold = true)
        box.addView(timer)
        reason = bigText("", 16f, Color.rgb(255, 210, 210))
        box.addView(reason)

        solveButton = Button(this).apply {
            text = "Solve ${Puzzle.QUESTIONS_IN_A_ROW} questions to unlock early"
            setOnClickListener { startPuzzle() }
        }
        box.addView(solveButton)

        puzzleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        progress = bigText("", 15f, Color.rgb(255, 200, 200))
        question = bigText("", 22f, bold = true)
        answer = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(220, 160, 160))
            hint = "Your answer"
            gravity = Gravity.CENTER
            textSize = 24f
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> check(); true }
        }
        submit = Button(this).apply {
            text = "Check"
            setOnClickListener { check() }
        }
        feedback = bigText("", 16f, Color.rgb(255, 230, 150))
        puzzleBox.addView(progress)
        puzzleBox.addView(question)
        puzzleBox.addView(answer)
        puzzleBox.addView(submit)
        puzzleBox.addView(feedback)
        box.addView(puzzleBox)

        box.addView(
            bigText(
                "Emergency calls still work from the lock screen.",
                14f, Color.rgb(255, 180, 180),
            ),
        )
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(90, 10, 16))
            isFillViewport = true
            addView(box)
        })
    }

    override fun onResume() {
        super.onResume()
        if (!Penalty.isLockedOut(this)) {
            finish()
            return
        }
        reason.text = Prefs.lockoutReason(this)
        handler.post(update)
        scheduleLock(if (solving) PUZZLE_IDLE_LOCK_MS else LOCK_DELAY_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(update)
        handler.removeCallbacks(lockAgain)
    }

    private fun scheduleLock(delay: Long) {
        handler.removeCallbacks(lockAgain)
        handler.postDelayed(lockAgain, delay)
    }

    // ------------------------------------------------------------------ puzzle

    private fun startPuzzle() {
        solving = true
        solved = 0
        solveButton.visibility = View.GONE
        puzzleBox.visibility = View.VISIBLE
        nextQuestion()
        scheduleLock(PUZZLE_IDLE_LOCK_MS)
    }

    private fun nextQuestion() {
        current = Puzzle.next()
        progress.text = "Question ${solved + 1} of ${Puzzle.QUESTIONS_IN_A_ROW}"
        question.text = current?.text
        answer.setText("")
        answer.requestFocus()
    }

    private fun check() {
        if (System.currentTimeMillis() < waitUntil) return
        val q = current ?: return
        scheduleLock(PUZZLE_IDLE_LOCK_MS)
        val given = answer.text.toString().trim().toLongOrNull()
        if (given == null) {
            feedback.text = "Type a number."
            return
        }
        if (given == q.answer) {
            solved++
            if (solved >= Puzzle.QUESTIONS_IN_A_ROW) {
                Prefs.setLockout(this, 0L, "")
                handler.removeCallbacks(lockAgain)
                finish()
                return
            }
            feedback.text = "Correct."
            nextQuestion()
        } else {
            solved = 0
            waitUntil = System.currentTimeMillis() + Puzzle.WRONG_ANSWER_WAIT_SECONDS * 1000L
            feedback.text = "Wrong, the answer was ${q.answer}. Starting again."
            nextQuestion()
            refreshWait()
        }
    }

    private fun refreshWait() {
        if (!solving) return
        val left = (waitUntil - System.currentTimeMillis() + 999) / 1000
        if (left > 0) {
            submit.isEnabled = false
            answer.isEnabled = false
            submit.text = "Wait ${left}s"
        } else if (!submit.isEnabled) {
            submit.isEnabled = true
            answer.isEnabled = true
            submit.text = "Check"
            answer.requestFocus()
        }
    }

    @Deprecated("Back does nothing while locked.")
    override fun onBackPressed() {
        if (!Penalty.isLockedOut(this)) finish()
    }
}
