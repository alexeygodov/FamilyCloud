package app.familycloud.ui

import java.util.Base64

fun encodeNav(path: String): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
}

fun decodeNav(value: String): String {
    if (value.isEmpty()) return ""
    return String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
