package life.mosaic.fit

import life.mosaic.fit.data.MealAnalysis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun MealAnalysis.formattedMealTime(): String =
    Instant.ofEpochMilli(createdAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
