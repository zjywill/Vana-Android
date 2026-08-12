package com.pinapia.vana

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pinapia.vana.checkin.CheckInScheduler
import com.pinapia.vana.intents.VanaActions
import com.pinapia.vana.ui.theme.VanaTheme

class MainActivity : ComponentActivity() {
    var checkInQuestion by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        setContent {
            VanaTheme {
                VanaApp(
                    checkInQuestion = checkInQuestion,
                    onCheckInConsumed = { checkInQuestion = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        val question = intent?.getStringExtra(CheckInScheduler.QUESTION_KEY)
        if (!question.isNullOrBlank()) {
            checkInQuestion = question
        }
        VanaActions.handle(this, intent)
    }
}
