package de.flexy.pendel.core.analysis

/**
 * Version of the analysis algorithms. Every derived record (trip analysis, stops, wait events,
 * routes, intersection passes) is stamped with it.
 *
 * Bump this whenever an algorithm changes in a way that alters results – the app then re-derives
 * all historical trips from their raw GPS points automatically. Raw data is never modified.
 *
 * History:
 *  1 – initial algorithms (anchor stop detection, kernel time model, coverage route clustering)
 */
object AnalysisVersion {
    const val CURRENT = 1
}
