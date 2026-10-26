package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import android.os.DeadObjectException

/** Local operation outcomes; a file or storage error is not evidence that the framework is disabled. */
internal sealed interface FrameworkServiceCall<out T> {
    data class Success<T>(val value: T, val connectionId: Long) : FrameworkServiceCall<T>
    data class Failure(val reason: Reason) : FrameworkServiceCall<Nothing>
    enum class Reason { NOT_CONNECTED, METADATA_UNAVAILABLE, UNSUPPORTED, CONNECTION_CHANGED, SERVICE_DIED, OPERATION_FAILED }
}

internal fun isFrameworkServiceDeath(
    failure: Throwable?,
    isDead: (Throwable) -> Boolean = { it is DeadObjectException }
): Boolean {
    var current = failure
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    repeat(8) {
        val cause = current ?: return false
        if (!seen.add(cause)) return false
        if (isDead(cause)) return true
        current = cause.cause
    }
    return false
}

internal fun <S : Any, T> callFrameworkService(
    service: S?,
    status: ModernFrameworkStatus,
    isCurrent: () -> Boolean,
    onDeath: () -> Unit,
    action: (S) -> T
): FrameworkServiceCall<T> {
    service ?: return FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.NOT_CONNECTED)
    if (!status.capable) return FrameworkServiceCall.Failure(
        if (status.properties == null || status.apiVersion == 0 ||
            status.failureCode == "framework_metadata_unavailable" &&
            status.apiVersion >= com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModernApiSupport.MIN_API &&
            status.properties and io.github.libxposed.service.XposedService.PROP_CAP_REMOTE != 0L)
            FrameworkServiceCall.Reason.METADATA_UNAVAILABLE
        else FrameworkServiceCall.Reason.UNSUPPORTED)
    if (!isCurrent()) return FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.CONNECTION_CHANGED)
    return try {
        val value = action(service)
        if (isCurrent()) FrameworkServiceCall.Success(value, status.connectionId)
        else FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.CONNECTION_CHANGED)
    } catch (failure: Exception) {
        if (isFrameworkServiceDeath(failure)) {
            onDeath()
            FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.SERVICE_DIED)
        } else FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.OPERATION_FAILED)
    }
}
