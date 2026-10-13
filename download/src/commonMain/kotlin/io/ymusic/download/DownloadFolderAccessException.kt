package io.ymusic.download

import projectcommon.error.CodedError

/** The app lost (or never held) write access to the user's download folder — e.g. an Android SAF tree
 *  grant that was revoked. A well-known category ([code] `"download_folder_access"`): the user fixes it by
 *  picking the folder again, so it renders as its own message and is never a crash record. */
class DownloadFolderAccessException(folder: String, cause: Throwable) :
    Exception("no write access to download folder $folder", cause),
    CodedError {
    override val code: String = CODE
    override val userMessage: String? = null

    companion object {
        const val CODE = "download_folder_access"
    }
}
