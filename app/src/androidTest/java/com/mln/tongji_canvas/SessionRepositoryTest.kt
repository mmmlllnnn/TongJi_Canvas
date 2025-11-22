package com.mln.tongji_canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mln.tongji_canvas.data.OAuth2Tokens
import com.mln.tongji_canvas.data.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sessions", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SessionRepository(context)
    }

    @Test
    fun saveAndRestoreSelectedUserIds() {
        val ids = setOf("user-1", "user-2", "user-3")
        repository.saveSelectedUserIds(ids)
        val loaded = repository.getSelectedUserIds()
        assertEquals(ids, loaded)
    }

    @Test
    fun addOrUpdateSessionReplacesExistingRecord() = runBlocking {
        val firstTokens = OAuth2Tokens(accessToken = "token-old", refreshToken = null, expiresIn = 3600)
        repository.addOrUpdateSession("Tester", firstTokens)

        val updatedTokens = OAuth2Tokens(accessToken = "token-new", refreshToken = null, expiresIn = 3600)
        repository.addOrUpdateSession("Tester", updatedTokens)

        val sessions = repository.getAllSessions()
        assertEquals(1, sessions.size)
        assertEquals("token-new", sessions.first().accessToken)
    }
}

