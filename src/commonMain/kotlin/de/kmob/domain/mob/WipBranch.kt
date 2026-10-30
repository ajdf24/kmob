package de.kmob.domain.mob

/** Präfix der WIP-Branches von mob; wird in M6 aus `mob config` gelesen (tasks 6.1). */
const val DEFAULT_WIP_PREFIX = "mob/"

/** Nur auf einem WIP-Branch gibt es etwas zu übergeben (R2.15). Detached HEAD (leerer Name) zählt nicht. */
fun isWipBranch(branch: String, prefix: String = DEFAULT_WIP_PREFIX): Boolean = branch.startsWith(prefix)
