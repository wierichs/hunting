package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import java.util.Locale

internal object NrwAerialImagery {
    const val MIN_ZOOM = 5
    const val MAX_ZOOM = 20
    const val CACHE_NAME = "GeobasisNRW_DOP_EPSG3857_v1"
    const val ATTRIBUTION = "Geobasis NRW (dl-de/zero-2-0)"
    const val LICENSE_URL = "https://www.govdata.de/dl-de/zero-2-0"
    const val CAPABILITIES_URL = "https://www.wmts.nrw.de/geobasis/wmts_nw_dop/1.0.0/WMTSCapabilities.xml"

    fun tileUrl(zoom: Int, column: Int, row: Int): String {
        require(zoom in MIN_ZOOM..MAX_ZOOM)
        require(column in 0 until (1 shl zoom) && row in 0 until (1 shl zoom))
        val matrix = String.format(Locale.ROOT, "%02d", zoom - MIN_ZOOM)
        return "https://www.wmts.nrw.de/geobasis/wmts_nw_dop/tiles/nw_dop/EPSG_3857_16/$matrix/$column/$row"
    }

    fun withinServiceExtent(boundary: JSONObject?): Boolean = boundary != null && runCatching {
        val extent = validatedCoverage(boundary).envelopeInternal
        extent.minX >= 5.6989 && extent.maxX <= 9.6835 && extent.minY >= 50.2651 && extent.maxY <= 52.5704
    }.getOrDefault(false)
}