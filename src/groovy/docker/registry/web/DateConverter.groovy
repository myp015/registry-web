package docker.registry.web

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

class DateConverter {

  static Date convert(String date) {
    if (!date) {
      return null
    }

    try {
      // Handles docker timestamps like: 2026-03-28T13:27:28.123662Z
      return Date.from(Instant.parse(date))
    } catch (ignored) {
      try {
        // Fallback for offsets like +08:00
        return Date.from(OffsetDateTime.parse(date, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant())
      } catch (ignored2) {
        return null
      }
    }
  }
}
