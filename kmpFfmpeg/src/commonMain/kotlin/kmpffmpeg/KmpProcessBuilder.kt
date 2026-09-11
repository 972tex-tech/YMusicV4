package kmpffmpeg

/**
 * Builds and launches one child process — the entire public surface of this module. Modeled on
 * `java.lang.ProcessBuilder`: set the binary path, arguments, and environment, then [start].
 *
 * Intentionally minimal: it only *launches* a process. Anything richer — feeding a custom source into
 * stdin, serving bytes over a unix-domain socket, capturing piped output — is the caller's job, done
 * against the returned [KmpProcess]: set up your side channel, [start], drive the process's
 * [KmpProcess.stdin]/[KmpProcess.stdout]/[KmpProcess.stderr], and tear the side channel down once
 * [KmpProcess.exitCode] completes.
 *
 * Arguments are passed as a real argv array (never a shell), so values with spaces or shell
 * metacharacters need no escaping — each [appendArg] token reaches the child verbatim.
 */
class KmpProcessBuilder(
    /** Absolute path to the executable to launch. */
    val binaryPath: String,
) {
    /** Process arguments in order; the program name is prepended automatically at launch. */
    val arguments: MutableList<String> = mutableListOf()

    /** Extra environment variables for the child. The child runs with exactly these — there is no
     *  inheritance of the parent environment. Packed ffmpeg finds libav via rpath, not
     *  `LD_LIBRARY_PATH`. */
    val environment: MutableMap<String, String> = mutableMapOf()

    /** Whether stdout is sent to `/dev/null` instead of a pipe. Set via [discardStdout]. */
    var isStdoutDiscarded: Boolean = false
        private set

    /** Whether stderr is sent to `/dev/null` instead of a pipe. Set via [discardStderr]. */
    var isStderrDiscarded: Boolean = false
        private set

    /** Append one or more arguments. Chainable. */
    fun appendArg(vararg args: String): KmpProcessBuilder = apply { arguments.addAll(args) }

    /** Append a list of arguments. Chainable. */
    fun appendArgs(args: Iterable<String>): KmpProcessBuilder = apply { arguments.addAll(args) }

    /** Set an environment variable for the child. Chainable. */
    fun setEnv(key: String, value: String): KmpProcessBuilder = apply { environment[key] = value }
    fun setEnv(map: Map<String, String>): KmpProcessBuilder = apply {
        map.entries.forEach { entry ->
            environment[entry.key] = entry.value
        }
    }

    /**
     * Send the child's stdout to `/dev/null` instead of a pipe. Use when you won't read it — an
     * un-drained stdout pipe stalls the child once its OS buffer fills. [KmpProcess.stdout] is then
     * empty. Chainable.
     */
    fun discardStdout(discard: Boolean = true): KmpProcessBuilder =
        apply { isStdoutDiscarded = discard }

    /**
     * Send the child's stderr to `/dev/null` instead of a pipe. Use when you won't read it.
     * [KmpProcess.stderr] is then empty. Chainable.
     */
    fun discardStderr(discard: Boolean = true): KmpProcessBuilder =
        apply { isStderrDiscarded = discard }

    /**
     * Launch the process and return it. This is a quick `fork`/`exec` — it does not block on the
     * child's work; await [KmpProcess.exitCode] for that. Run it off the main thread if you're strict
     * about the brief launch syscall.
     */
    fun start(): KmpProcess = startProcess(this)
}

/** Platform spawn for [KmpProcessBuilder.start]. Throws on iOS (child processes are not permitted). */
internal expect fun startProcess(builder: KmpProcessBuilder): KmpProcess
