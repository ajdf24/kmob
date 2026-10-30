// Absichtlich verletzend – wird nicht kompiliert, nur von checkArchitectureSelfTest gelesen.
package de.kmob.domain.bad

import de.kmob.adapter.git.GitCli // expect: layer
import okio.FileSystem // expect: filesystem
import okio.* // expect: filesystem
import platform.posix.getenv // expect: os-import
import kotlinx.cinterop.memScoped // expect: os-import

fun home(): String = getenv("HOME").toString() // expect: os-token
fun trim(path: String) = path.trimEnd('/') // expect: os-token
fun windows(path: String) = path.split('\\') // expect: os-token
fun qualified() = platform.windows.GetLastError() // expect: os-import
fun qualifiedLayer() = de.kmob.adapter.git.GitCli(TODO(), TODO()) // expect: layer
fun qualifiedFs() = okio.FileSystem.SYSTEM // expect: filesystem
val url = "https://example.org/path" // kein Verstoß: // in einem String
