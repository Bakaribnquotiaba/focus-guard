package com.mossab.focusguard

import kotlin.random.Random

/** Random numeric questions that take real thinking. A new set every time, so they can't be memorised. */
object Puzzle {

    class Question(val text: String, val answer: Long)

    const val QUESTIONS_IN_A_ROW = 5
    const val WRONG_ANSWER_WAIT_SECONDS = 30

    fun next(r: Random = Random.Default): Question = when (r.nextInt(6)) {
        0 -> multiply(r)
        1 -> secondOrderSequence(r)
        2 -> doublingSequence(r)
        3 -> mixedArithmetic(r)
        4 -> remainder(r)
        else -> ages(r)
    }

    private fun multiply(r: Random): Question {
        val a = r.nextInt(23, 99)
        val b = r.nextInt(13, 49)
        return Question("$a × $b = ?", a.toLong() * b)
    }

    /** Differences grow by a fixed step, e.g. 3, 7, 13, 21, 31, ? */
    private fun secondOrderSequence(r: Random): Question {
        var v = r.nextLong(2, 20)
        var d = r.nextLong(2, 9)
        val step = r.nextLong(2, 6)
        val terms = mutableListOf<Long>()
        repeat(6) {
            terms.add(v)
            v += d
            d += step
        }
        return Question(
            "What comes next?\n${terms.take(5).joinToString(", ")}, ?",
            terms[5],
        )
    }

    /** Each term is (previous × k) + c, e.g. 2, 7, 22, 67, ? */
    private fun doublingSequence(r: Random): Question {
        val k = r.nextLong(2, 4)
        val c = r.nextLong(-3, 4)
        var v = r.nextLong(1, 6)
        val terms = mutableListOf<Long>()
        repeat(5) {
            terms.add(v)
            v = v * k + c
        }
        return Question(
            "What comes next?\n${terms.take(4).joinToString(", ")}, ?",
            terms[4],
        )
    }

    private fun mixedArithmetic(r: Random): Question {
        val a = r.nextInt(12, 60)
        val b = r.nextInt(12, 60)
        val c = r.nextInt(3, 13)
        val d = r.nextInt(10, 99)
        return Question("($a + $b) × $c − $d = ?", (a + b).toLong() * c - d)
    }

    private fun remainder(r: Random): Question {
        val n = r.nextInt(500, 5000)
        val m = r.nextInt(7, 20)
        return Question("What is the remainder when $n is divided by $m?", (n % m).toLong())
    }

    private fun ages(r: Random): Question {
        val son = r.nextInt(5, 16)
        val factor = r.nextInt(3, 6)
        val years = r.nextInt(3, 12)
        val father = son * factor
        return Question(
            "A father is $factor times as old as his son. The son is $son now.\n" +
                "How old will the father be in $years years?",
            (father + years).toLong(),
        )
    }
}
