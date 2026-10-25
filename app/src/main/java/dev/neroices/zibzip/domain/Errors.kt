package dev.neroices.zibzip.domain

/** Typed failures so the UI can show precise, actionable messages. */
sealed class ZibzipException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PermissionDenied(val path: String) : ZibzipException("Permission denied: $path")
    class NotFound(val path: String) : ZibzipException("Not found: $path")
    class AlreadyExists(val name: String) : ZibzipException("\"$name\" already exists")
    class InvalidName(val name: String) : ZibzipException("\"$name\" is not a valid name")
    class InsufficientSpace(val needed: Long, val available: Long) :
        ZibzipException("Not enough free space")
    class CorruptedArchive(val archive: String, cause: Throwable? = null) :
        ZibzipException("\"$archive\" is damaged or not a valid archive", cause)
    class PasswordRequired(val archive: String) : ZibzipException("\"$archive\" is password protected")
    class WrongPassword(val archive: String) : ZibzipException("Wrong password for \"$archive\"")
    class Unsupported(message: String) : ZibzipException(message)
    class Io(message: String, cause: Throwable? = null) : ZibzipException(message, cause)
}
