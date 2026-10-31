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
import app.daysemble.data.db.SyncService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

sealed interface AuthResult {
    /**
     * [services] are those the user allowed. Google's consent screen lets people untick a scope,
     * so this can be fewer than were asked for (never none: that is [Failed] "scopes_denied").
     */
    data class Authorized(val token: String, val email: String?, val services: Set<SyncService> = SyncService.entries.toSet()) : AuthResult
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

    private fun request(email: String?, services: Set<SyncService>): AuthorizationRequest = AuthorizationRequest.Builder()
        .setRequestedScopes(scopesFor(services).map(::Scope))
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /** Starts (or silently completes) authorization for [services]. Safe to call from the UI. */
    suspend fun authorize(email: String? = null, services: Set<SyncService> = SyncService.entries.toSet()): AuthResult {
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (gms != ConnectionResult.SUCCESS) return AuthResult.PlayServicesUnavailable
        return try {
            toResult(client.authorize(request(email, services)).await())
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
        val granted = grantedServices(r.grantedScopes)
        if (granted.isEmpty()) return AuthResult.Failed("scopes_denied")
        // [services] may have changed while this request ran: keep the token only if it covers what
        // is synced now, otherwise the next requests would fail with 403 for the missing scopes.
        if (coversSynced(granted, services)) cached = token
        return AuthResult.Authorized(token, r.toGoogleSignInAccount()?.email, granted)
    }

    private fun mapError(e: ApiException): AuthResult = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR -> AuthResult.SetupRequired
        CommonStatusCodes.CANCELED -> AuthResult.Failed("canceled")
        CommonStatusCodes.NETWORK_ERROR -> AuthResult.Failed("network")
        else -> AuthResult.Failed("status_${e.statusCode}")
    }

    @Volatile var accountEmail: String? = null

    /**
     * The services being synced. Silent token requests ask only for their scopes, so one the user
     * didn't allow (or turned off) doesn't make every request need consent again.
     */
    @Volatile var services: Set<SyncService> = SyncService.entries.toSet()
        set(value) {
            // A token granted for other scopes would fail with 403 on the newly needed ones.
            if (value != field) cached = null
            field = value
        }

    override suspend fun token(): String? = mutex.withLock {
        cached ?: (authorize(accountEmail, services) as? AuthResult.Authorized)?.token
    }

    override suspend fun invalidate(token: String) {
        mutex.withLock { if (cached == token) cached = null }
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    /** Revokes Daysemble's access on Google's side. Remote data is untouched. */
    suspend fun revoke(email: String) {
        cached?.let { invalidate(it) }
        cached = null
        // Also the full "calendar" scope that earlier versions asked for: an account connected then
        // still holds it. If Google refuses the list with that scope, revoke the current ones.
        val account = Account(email, "com.google")
        suspend fun revokeOnly(scopes: List<String>) =
            client.revokeAccess(RevokeAccessRequest.builder().setAccount(account).setScopes(scopes.map(::Scope)).build()).await()
        runCatching { revokeOnly(scopesToRevoke()) }.recoverCatching { revokeOnly(scopesFor(SyncService.entries.toSet())) }
    }

    companion object {
        /**
         * Narrowest scopes for what CalendarApi calls: events (list, get, insert, patch, delete,
         * instances) on any calendar the user can write to, and reading the calendar list. Not the
         * full "calendar" scope, which also allows deleting calendars and changing their sharing.
         */
        const val SCOPE_CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"
        const val SCOPE_CALENDAR_LIST = "https://www.googleapis.com/auth/calendar.calendarlist.readonly"
        const val SCOPE_TASKS = "https://www.googleapis.com/auth/tasks"
        private const val SCOPE_EMAIL = "email"
        /** Full calendar access, requested by versions before the narrow scopes; only ever revoked. */
        const val LEGACY_SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"

        private fun scopesOf(service: SyncService) = when (service) {
            SyncService.CALENDAR -> listOf(SCOPE_CALENDAR_EVENTS, SCOPE_CALENDAR_LIST)
            SyncService.TASKS -> listOf(SCOPE_TASKS)
        }

        fun scopesFor(services: Set<SyncService>): List<String> = services.sortedBy { it.ordinal }.flatMap(::scopesOf) + SCOPE_EMAIL

        /** What disconnecting revokes: every scope this app asks for now or asked for before. */
        fun scopesToRevoke(): List<String> = scopesFor(SyncService.entries.toSet()) + LEGACY_SCOPE_CALENDAR

        /** A token is worth keeping only if it was granted every service that is synced. */
        fun coversSynced(granted: Set<SyncService>, synced: Set<SyncService>) = granted.containsAll(synced)

        /** A service counts as allowed only if every scope it needs was granted. */
        fun grantedServices(granted: Collection<String>): Set<SyncService> =
            SyncService.entries.filter { granted.containsAll(scopesOf(it)) }.toSet()
    }
}
