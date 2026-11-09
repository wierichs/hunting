package org.wierichs.dogtracker.jagdkarte

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileInvitationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun invitationDialogSendsSelectedRoleAndEmail() {
        val profile = HuntingProfile("revier", "Testrevier", "owner", false, JSONObject())
        var invitedEmail = ""
        var invitedRole: HuntingProfileMemberRole? = null
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                InviteMemberDialog(profile,
                    onInvite = { email, role -> invitedEmail = email; invitedRole = role },
                    onInvited = { dismissed = true }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("E-Mail-Adresse").performTextInput("Editor@Example.org")
        composeRule.onNodeWithText("Gast").performClick()
        composeRule.onNodeWithText("Bearbeiter").performClick()
        composeRule.onNodeWithText("Einladung senden").performClick()
        composeRule.runOnIdle {
            assertEquals("Editor@Example.org", invitedEmail)
            assertEquals(HuntingProfileMemberRole.EDITOR, invitedRole)
            assertEquals(true, dismissed)
        }
    }
}
