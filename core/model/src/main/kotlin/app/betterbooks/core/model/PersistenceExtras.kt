package app.betterbooks.core.model

/** Lets Setup toggle which sections are narrated and counted towards the frontier. Implemented by :core:database. */
interface SectionEditor {
    suspend fun setIncluded(sectionId: Long, included: Boolean)
}
