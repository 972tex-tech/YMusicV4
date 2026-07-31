package kmpcommon

/**
 * Marks a declaration whose body performs blocking work (filesystem / network / database IO, or a long CPU
 * task) and therefore **must be called off the main/UI thread** — on an IO or worker dispatcher. The
 * commonMain counterpart of androidx's `@WorkerThread` (which isn't published for Kotlin Multiplatform),
 * so the same threading contract can be expressed in shared code. Annotating a class applies it to every
 * member.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class WorkerThread
