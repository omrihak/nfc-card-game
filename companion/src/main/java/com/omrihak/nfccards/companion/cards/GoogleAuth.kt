package com.omrihak.nfccards.companion.cards

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Google Drive access for the parent's own account (Google Identity Services' AuthorizationClient).
 *
 * Android identifies the app by its package name and signing key's SHA-1, registered once as an
 * "Android" OAuth client in the same Google Cloud project as the web manager (see README).
 * After the first consent, [token] gets a fresh token silently whenever it is needed.
 */
class GoogleAuth(context: Context) {

    private val client = Identity.getAuthorizationClient(context)
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .build()

    /** Has a resolution (a consent screen to show) the first time; otherwise a token. */
    suspend fun authorize(): AuthorizationResult = client.authorize(request).await()

    /** After the consent screen. Throws if it was cancelled or failed. */
    fun resultFrom(data: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

    /** A valid access token, or [NeedsSignIn] when the parent has to go through the consent screen (again). */
    suspend fun token(): String {
        val result = try {
            authorize()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Revoked, offline Play services, or the OAuth client isn't set up: the sign-in button shows why.
            throw NeedsSignIn()
        }
        if (result.hasResolution()) throw NeedsSignIn()
        return result.accessToken ?: throw NeedsSignIn()
    }

    class NeedsSignIn : Exception("Sign in with Google first")

    private companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
