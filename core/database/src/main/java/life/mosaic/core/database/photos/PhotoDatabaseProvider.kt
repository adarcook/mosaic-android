package life.mosaic.core.database.photos

import android.content.Context
import life.mosaic.core.database.MosaicDatabase

fun photoDao(context: Context): PhotoDao = MosaicDatabase.get(context).photoDao()
