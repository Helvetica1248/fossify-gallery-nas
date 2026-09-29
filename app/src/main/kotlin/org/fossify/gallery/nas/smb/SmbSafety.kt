package org.fossify.gallery.nas.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.settings.NasCredentials
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal class SmbFailureException(val failure: NasFailure) : IOException("NAS operation failed")

internal object SmbSafety {
    val access = setOf(AccessMask.GENERIC_READ)
    val disposition = SMB2CreateDisposition.FILE_OPEN
    // Deny concurrent writes/renames while checking ancestors and reading.
    val sharing = setOf(SMB2ShareAccess.FILE_SHARE_READ)
    val noFollow = setOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)

    fun config(sockets: SocketFactory, socketIdleMillis: Int = SMB_TIMEOUT_MILLIS): SmbConfig = SmbConfig.builder()
        .withDialects(SMB2Dialect.SMB_2_0_2, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0,
            SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_1_1)
        .withMultiProtocolNegotiate(false)
        .withSigningEnabled(true)
        .withSigningRequired(true)
        .withDfsEnabled(false)
        .withEncryptData(false)
        .withAuthenticators(NtlmAuthenticator.Factory())
        .withSocketFactory(sockets)
        .withSoTimeout(socketIdleMillis)
        .withTimeout(SMB_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    fun authentication(credentials: NasCredentials): AuthenticationContext {
        if (credentials.username.isBlank()) throw SmbFailureException(NasFailure.AUTHENTICATION_FAILED)
        val separator = credentials.username.indexOf('\\')
        val domain = if (separator < 0) null else credentials.username.substring(0, separator)
        val user = credentials.username.substring(separator + 1)
        if (user.isBlank()) throw SmbFailureException(NasFailure.AUTHENTICATION_FAILED)
        return AuthenticationContext(user, credentials.password.toCharArray(), domain)
    }

    fun failure(error: Exception): NasFailure {
        // SMBJ wraps transport exceptions. Never inspect or publish their messages.
        val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES).toList()
        causes.filterIsInstance<SmbFailureException>().firstOrNull()?.let { return it.failure }
        causes.filterIsInstance<SMBApiException>().firstOrNull()?.let { return statusFailure(it.statusCode) }
        return when {
            causes.any { it is SMB1NotSupportedException } -> NasFailure.UNSUPPORTED_PROTOCOL
            causes.any { it is SMB2GuestSigningRequiredException } -> NasFailure.AUTHENTICATION_FAILED
            causes.any { it is SocketTimeoutException || it is TimeoutException } -> NasFailure.TIMED_OUT
            causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
                NasFailure.UNREACHABLE
            causes.any { it is IllegalArgumentException || it is IndexOutOfBoundsException } ->
                NasFailure.INVALID_RESPONSE
            else -> NasFailure.IO_ERROR
        }
    }

    fun statusFailure(status: Long): NasFailure = when (NtStatus.valueOf(status)) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED, NtStatus.STATUS_PASSWORD_EXPIRED -> NasFailure.AUTHENTICATION_FAILED
        NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_SHARING_VIOLATION -> NasFailure.ACCESS_DENIED
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_NO_SUCH_FILE -> NasFailure.NOT_FOUND
        NtStatus.STATUS_NOT_SUPPORTED, NtStatus.STATUS_PATH_NOT_COVERED,
        NtStatus.STATUS_STOPPED_ON_SYMLINK -> NasFailure.UNSUPPORTED_PROTOCOL
        NtStatus.STATUS_IO_TIMEOUT -> NasFailure.TIMED_OUT
        NtStatus.STATUS_BAD_NETWORK_PATH, NtStatus.STATUS_CONNECTION_DISCONNECTED,
        NtStatus.STATUS_CONNECTION_RESET -> NasFailure.UNREACHABLE
        else -> NasFailure.IO_ERROR
    }

    private const val MAX_CAUSES = 8
}
