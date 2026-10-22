package app.daysemble.data.google.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

sealed interface AuthResult {
    data class Authorized(val token: String, val email: String?) : AuthResult
    /** The user must see Google's consent/account chooser. Launch [intent] from an Activity. */
    data class NeedsConsent(val intent: PendingIntent) : AuthResult
    /** OAuth client not configured for this package + signing certificate (DEVELOPER_ERROR). */
    data object SetupRequired : AuthResult
    /** Google Play services missing or outdated. */
    data object PlayServicesUnavailable : AuthResult
    data class Failed(val reason: String) : AuthResult
}

/** Supplies access tokens to the API layer. */
interface TokenProvider {
    /** Never shows UI. Returns null if consent/re-auth is needed. */
    suspend fun token(): String?
    /** Forget a token the server rejected (401), so the next call fetches a fresh one. */
    suspend fun invalidate(token: String)
}

/**
 * Google Identity Services AuthorizationClient wrapper. Access tokens are short-lived, come
 * from Play services on demand and live only in memory. Refresh tokens are never handled by the app.
 */
class GoogleAuthManager(private val context: Context) : TokenProvider {

    private val client get() = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()
    @Volatile private var cached: String? = null

    private fun request(email: String?): AuthorizationRequest = AuthorizationRequest.Builder()
        .setRequestedScopes(SCOPES.map(::Scope))
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /** Starts (or silently completes) authorization. Safe to call from the UI. */
    suspend fun authorize(email: String? = null): AuthResult {
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (gms != ConnectionResult.SUCCESS) return AuthResult.PlayServicesUnavailable
        return try {
            toResult(client.authorize(request(email)).await())
        } catch (e: ApiException) {
            mapError(e)
        } catch (e: Exception) {
            AuthResult.Failed(e.javaClass.simpleName)
        }
    }

    /** Completes the consent flow from the Activity result. */
    fun fromIntent(data: Intent?): AuthResult = try {
        toResult(client.getAuthorizationResultFromIntent(data))
    } catch (e: ApiException) {
        mapError(e)
    }

    private fun toResult(r: AuthorizationResult): AuthResult {
        if (r.hasResolution()) return r.pendingIntent?.let { AuthResult.NeedsConsent(it) } ?: AuthResult.Failed("no_resolution")
        val token = r.accessToken ?: return AuthResult.Failed("no_token")
        val missing = SCOPES.filter { it.startsWith("https://") } - r.grantedScopes.toSet()
        if (missing.isNotEmpty()) return AuthResult.Failed("scopes_denied")
        cached = token
        return AuthResult.Authorized(token, r.toGoogleSignInAccount()?.email)
    }

    private fun mapError(e: ApiException): AuthResult = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR -> AuthResult.SetupRequired
        CommonStatusCodes.CANCELED -> AuthResult.Failed("canceled")
        CommonStatusCodes.NETWORK_ERROR -> AuthResult.Failed("network")
        else -> AuthResult.Failed("status_${e.statusCode}")
    }

    @Volatile var accountEmail: String? = null

    override suspend fun token(): String? = mutex.withLock {
        cached ?: (authorize(accountEmail) as? AuthResult.Authorized)?.token
    }

    override suspend fun invalidate(token: String) {
        mutex.withLock { if (cached == token) cached = null }
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    /** Revokes Daysemble's access on Google's side. Remote data is untouched. */
    suspend fun revoke(email: String) {
        cached?.let { invalidate(it) }
        cached = null
        runCatching {
            client.revokeAccess(
                RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(SCOPES.map(::Scope)).build(),
            ).await()
        }
    }

    companion object {
        const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"
        const val SCOPE_TASKS = "https://www.googleapis.com/auth/tasks"
        val SCOPES = listOf(SCOPE_CALENDAR, SCOPE_TASKS, "email")
    }
}
