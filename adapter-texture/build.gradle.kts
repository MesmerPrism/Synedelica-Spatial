// SPDX-License-Identifier: AGPL-3.0-or-later
plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies { api(project(":core")); testImplementation("junit:junit:4.13.2") }
