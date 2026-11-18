package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FreeAerialImageryTest {
    @Test fun wmtsMatricesStartAtXyzZoomFiveAndUseColumnBeforeRow() {
        assertEquals("https://www.wmts.nrw.de/geobasis/wmts_nw_dop/tiles/nw_dop/EPSG_3857_16/00/16/10",
            NrwAerialImagery.tileUrl(5, 16, 10))
        assertTrue(NrwAerialImagery.tileUrl(12, 2156, 1439).endsWith("/07/2156/1439"))
        assertTrue(NrwAerialImagery.tileUrl(20, 544492, 344557).endsWith("/15/544492/344557"))
        assertFalse(NrwAerialImagery.tileUrl(12, 2156, 1439).contains("key="))
    }

    @Test fun rejectsUnsupportedZoomAndInvalidTileCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { NrwAerialImagery.tileUrl(4, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { NrwAerialImagery.tileUrl(21, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { NrwAerialImagery.tileUrl(5, 32, 0) }
        assertThrows(IllegalArgumentException::class.java) { NrwAerialImagery.tileUrl(5, 0, -1) }
    }

    @Test fun checksWholeDistrictAgainstPublishedServiceExtent() {
        val nrw = JSONObject("""{"type":"Polygon","coordinates":[[[6.7,51.1],[6.8,51.1],[6.8,51.2],[6.7,51.2],[6.7,51.1]]]}""")
        val outside = JSONObject("""{"type":"Polygon","coordinates":[[[6,49],[7,49],[7,50],[6,50],[6,49]]]}""")
        val partiallyOutside = JSONObject("""{"type":"Polygon","coordinates":[[[6,50],[7,50],[7,51],[6,51],[6,50]]]}""")
        assertTrue(NrwAerialImagery.withinServiceExtent(nrw))
        assertFalse(NrwAerialImagery.withinServiceExtent(outside))
        assertFalse(NrwAerialImagery.withinServiceExtent(partiallyOutside))
        assertFalse(NrwAerialImagery.withinServiceExtent(null))
        assertFalse(NrwAerialImagery.withinServiceExtent(JSONObject()))
    }
}