// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies { testImplementation("junit:junit:4.13.2") }

tasks.test { useJUnit() }
