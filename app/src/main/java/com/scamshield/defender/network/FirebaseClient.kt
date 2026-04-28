package com.scamshield.defender.network

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.scamshield.defender.dataconnect.AddCallLogMutation
import com.scamshield.defender.dataconnect.BlockNumberMutation
import com.scamshield.defender.dataconnect.DefaultConnector
import com.scamshield.defender.dataconnect.GetBlockedNumbersQuery
import com.scamshield.defender.dataconnect.GetRecentCallLogsQuery
import com.scamshield.defender.dataconnect.GetScamNumberQuery
import com.scamshield.defender.dataconnect.UpsertScamNumberMutation
import com.scamshield.defender.dataconnect.UpsertUserProfileMutation
import com.scamshield.defender.dataconnect.GetUserProfileQuery
import com.scamshield.defender.dataconnect.instance
import com.scamshield.defender.dataconnect.execute
import com.scamshield.defender.model.BlockedNumber
import com.scamshield.defender.model.CallLog
import com.scamshield.defender.model.ScamNumber
import com.scamshield.defender.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * ═══════════════════════════════════════════════════════════════════
 * FirebaseClient — Full Firebase Client (Auth + Data Connect)
 * ═══════════════════════════════════════════════════════════════════
 */
class FirebaseClient private constructor() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val connector: DefaultConnector = DefaultConnector.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "FirebaseClient"
        
        @Volatile
        private var instance: FirebaseClient? = null

        @JvmStatic
        fun getInstance(): FirebaseClient {
            return instance ?: synchronized(this) {
                instance ?: FirebaseClient().also { instance = it }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AUTHENTICATION
    // ═══════════════════════════════════════════════════════════════

    fun isAuthenticated(): Boolean {
        return auth.currentUser != null
    }

    fun getUserId(): String? {
        return auth.currentUser?.uid
    }

    fun signOut() {
        auth.signOut()
    }

    fun getAuth(): FirebaseAuth {
        return auth
    }

    // ═══════════════════════════════════════════════════════════════
    // USER PROFILE
    // ═══════════════════════════════════════════════════════════════

    fun saveUserProfile(profile: UserProfile, callback: DataCallback<Void>?) {
        val uid = getUserId()
        if (uid == null) {
            callback?.onError("Not authenticated")
            return
        }

        scope.launch {
            try {
                connector.upsertUserProfile.execute {
                    phoneNumber = profile.phoneNumber
                    email = profile.email
                    country = profile.country
                }
                Log.i(TAG, "✅ User profile saved")
                callback?.onSuccess(null)
            } catch (e: Exception) {
                Log.e(TAG, "Save profile error", e)
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun getUserProfile(callback: DataCallback<UserProfile>?) {
        val uid = getUserId()
        if (uid == null) {
            callback?.onError("Not authenticated")
            return
        }

        scope.launch {
            try {
                val result = connector.getUserProfile.execute()
                val userProfile = result.data.userProfile
                if (userProfile != null) {
                    val profile = UserProfile()
                    profile.phoneNumber = userProfile.phoneNumber
                    profile.email = userProfile.email
                    profile.country = userProfile.country
                    callback?.onSuccess(profile)
                } else {
                    callback?.onSuccess(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get profile error", e)
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CALL LOGS
    // ═══════════════════════════════════════════════════════════════

    fun insertCallLog(callLog: CallLog, callback: DataCallback<Void>?) {
        val uid = getUserId()
        if (uid == null) {
            callback?.onError("Not authenticated")
            return
        }

        scope.launch {
            try {
                connector.addCallLog.execute(
                    phoneNumber = callLog.phoneNumber ?: "",
                    callDurationSeconds = callLog.callDurationSeconds,
                    scamScore = callLog.scamScore.toDouble(),
                    isScam = callLog.isScam,
                    deepfakeScore = callLog.deepfakeScore.toDouble()
                )
                Log.i(TAG, "✅ Call log inserted")
                callback?.onSuccess(null)
            } catch (e: Exception) {
                Log.e(TAG, "Insert call log error", e)
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun fetchCallHistory(limit: Int, callback: DataCallback<List<CallLog>>?) {
        val uid = getUserId()
        if (uid == null) {
            callback?.onError("Not authenticated")
            return
        }

        scope.launch {
            try {
                val result = connector.getRecentCallLogs.execute(userId = uid)
                val logs = result.data.callLogs.map { item ->
                    val cl = CallLog()
                    cl.userId = uid
                    cl.phoneNumber = item.phoneNumber
                    cl.callDurationSeconds = item.callDurationSeconds
                    cl.scamScore = item.scamScore.toFloat()
                    cl.isScam = item.isScam
                    cl.deepfakeScore = item.deepfakeScore.toFloat()
                    cl.analyzedAt = Date().toString()
                    cl
                }.take(limit)
                callback?.onSuccess(logs)
            } catch (e: Exception) {
                Log.w(TAG, "Error getting call logs.", e)
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLOCKED NUMBERS
    // ═══════════════════════════════════════════════════════════════

    fun insertBlockedNumber(blockedNumber: BlockedNumber, callback: DataCallback<Void>?) {
        val uid = getUserId()
        if (uid == null) {
            callback?.onError("Not authenticated")
            return
        }

        scope.launch {
            try {
                connector.blockNumber.execute(
                    phoneNumber = blockedNumber.phone_number ?: "",
                    scamScore = blockedNumber.scam_score.toDouble(),
                    reason = blockedNumber.reason ?: "User blocked"
                )
                Log.i(TAG, "✅ Blocked number synced")
                reportToScamDatabase(blockedNumber.phone_number, blockedNumber.scam_score)
                callback?.onSuccess(null)
            } catch (e: Exception) {
                Log.e(TAG, "Insert blocked number error", e)
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun fetchBlockedNumbers(callback: DataCallback<List<BlockedNumber>>?) {
        val uid = getUserId()
        if (uid == null) {
            callback?.onError("Not authenticated")
            return
        }

        scope.launch {
            try {
                val result = connector.getBlockedNumbers.execute(blockedBy = uid)
                val numbers = result.data.blockedNumbers.map { item ->
                    val bn = BlockedNumber()
                    bn.blockedBy = uid
                    bn.phone_number = item.phoneNumber
                    bn.scam_score = item.scamScore.toFloat()
                    bn.reason = item.reason
                    bn.createdAt = Date().toString()
                    bn
                }
                callback?.onSuccess(numbers)
            } catch (e: Exception) {
                Log.w(TAG, "Error getting blocked numbers.", e)
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SCAM DATABASE — Community crowd-sourced intelligence
    // ═══════════════════════════════════════════════════════════════

    fun checkScamDatabase(phoneNumber: String, callback: DataCallback<ScamNumber>?) {
        scope.launch {
            try {
                val result = connector.getScamNumber.execute(phoneNumber = phoneNumber)
                val scamData = result.data.scamNumber
                if (scamData != null) {
                    val scamNum = ScamNumber()
                    scamNum.phone_number = scamData.id
                    scamNum.total_reports = scamData.totalReports
                    scamNum.avg_scam_score = scamData.avgScamScore.toFloat()
                    scamNum.lastReported = Date().toString()
                    callback?.onSuccess(scamNum)
                } else {
                    callback?.onSuccess(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking scam database.", e)
                callback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    private fun reportToScamDatabase(phoneNumber: String?, scamScore: Float) {
        if (phoneNumber == null) return
        scope.launch {
            try {
                connector.upsertScamNumber.execute(
                    phoneNumber = phoneNumber,
                    avgScamScore = scamScore.toDouble(),
                    totalReports = 1, // Simplified for now
                    isVerifiedScam = scamScore > 0.8
                )
                Log.i(TAG, "Reported to community scam database")
            } catch (e: Exception) {
                Log.e(TAG, "Report to scam DB error", e)
            }
        }
    }

    fun getTopScamNumbers(limit: Int, callback: DataCallback<List<ScamNumber>>?) {
        // Not currently supported by generated GraphQL queries.
        // Needs a new operation added to the operations.gql file.
        callback?.onSuccess(emptyList())
    }

    // Stubbed AI analysis path kept for Java compatibility (ScamAnalyzerAI).
    fun analyzeCallWithAI(transcript: String, callback: DataCallback<CloudAnalysisResult>?) {
        if (!isAuthenticated()) {
            callback?.onError("Not authenticated")
            return
        }
        callback?.onError("AI Analysis via Firebase Functions is not yet implemented.")
    }

    class CloudAnalysisResult {
        @JvmField var is_scam: Boolean = false
        @JvmField var scam_score: Float = 0f
        @JvmField var scam_type: String? = null
        @JvmField var explanation: String? = null
        @JvmField var keywords: List<String> = emptyList()
    }

    interface DataCallback<T> {
        fun onSuccess(data: T?)
        fun onError(error: String)
    }
}
