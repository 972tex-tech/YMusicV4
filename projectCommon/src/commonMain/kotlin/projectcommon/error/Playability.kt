package projectcommon.error

/** A YouTube playabilityStatus verdict on the video for this viewer: the user acts on it (sign in, pick
 *  another video), so it renders as its own localized message and is never a crash record. */
enum class Playability { SignInRequired, Unavailable, RetryLater }

/** The verdict an error [code] carries, or null for any other code. */
fun playabilityOf(code: String?): Playability? = when {
    code == null -> null
    code in SIGN_IN_STATUSES -> Playability.SignInRequired
    // YouTube answers ERROR for transient server-side failures too; the same request often succeeds later.
    code == "ERROR" -> Playability.RetryLater
    // Any other playabilityStatus (UNPLAYABLE, CONTENT_CHECK_REQUIRED, future ones): all-caps,
    // unlike site codes (E#####) and categories ("network").
    PLAYABILITY_STATUS.matches(code) -> Playability.Unavailable
    else -> null
}

private val SIGN_IN_STATUSES = setOf("LOGIN_REQUIRED", "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED")
private val PLAYABILITY_STATUS = Regex("[A-Z][A-Z_]*")
