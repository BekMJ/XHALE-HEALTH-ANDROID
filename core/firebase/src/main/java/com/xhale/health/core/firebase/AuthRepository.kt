package com.xhale.health.core.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class AuthState(
    val user: FirebaseUser? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@Singleton
class AuthRepository @Inject constructor(
    private val authLazy: Lazy<FirebaseAuth>,
    @Named("firebase_enabled") private val firebaseEnabled: Boolean
) {
    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        if (firebaseEnabled) {
            // Lazy.get() is only invoked when Firebase is enabled
            authLazy.get().addAuthStateListener { firebaseAuth ->
                _authState.value = AuthState(
                    user = firebaseAuth.currentUser,
                    isLoading = false,
                    error = null
                )
            }
        } else {
            _authState.value = AuthState(user = null, isLoading = false, error = null)
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        if (!firebaseEnabled) return Result.failure(IllegalStateException("Firebase disabled"))
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            authLazy.get().signInWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            Result.failure(e)
        }
    }

    suspend fun signUp(email: String, password: String): Result<Unit> {
        if (!firebaseEnabled) return Result.failure(IllegalStateException("Firebase disabled"))
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            authLazy.get().createUserWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(isLoading = false, error = e.message)
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> {
        if (!firebaseEnabled) return Result.success(Unit)
        return try {
            authLazy.get().signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        if (!firebaseEnabled) return Result.failure(IllegalStateException("Firebase disabled"))
        return try {
            authLazy.get().sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearError() {
        _authState.value = _authState.value.copy(error = null)
    }
}
