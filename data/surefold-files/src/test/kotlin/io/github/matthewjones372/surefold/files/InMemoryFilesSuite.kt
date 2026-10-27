package io.github.matthewjones372.surefold.files

/** The fake keeps the contract every store keeps. */
object InMemoryFilesSuite : FilesContract() {
    override fun files(): Files = InMemoryFiles(maxSize = 1_000_000)
}
