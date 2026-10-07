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
 *  2 – movement-window trimming (indoor GPS noise at arrival), speed profile can overrule an
 *      activity-recognition hint (walks), plausibility check for altitude, place groups
 *      (e.g. Mensa counts as Uni for routes)
 *  3 – a recording with a long stay in the middle (≥ 8 min within 150 m, travel on both sides)
 *      is split into separate trips (ride to Uni + ride home recorded as one)
 */
object AnalysisVersion {
    const val CURRENT = 3
}
