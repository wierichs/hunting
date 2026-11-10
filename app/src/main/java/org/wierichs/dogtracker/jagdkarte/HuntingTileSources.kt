package org.wierichs.dogtracker.jagdkarte

import android.app.Application
import android.content.Context
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import org.json.JSONObject

internal fun aerialTileSource() = object : XYTileSource(
    NrwAerialImagery.CACHE_NAME, NrwAerialImagery.MIN_ZOOM, NrwAerialImagery.MAX_ZOOM, 256, ".jpg",
    arrayOf("https://www.wmts.nrw.de/geobasis/wmts_nw_dop/tiles/"), NrwAerialImagery.ATTRIBUTION
) {
    override fun getTileURLString(index: Long): String = NrwAerialImagery.tileUrl(
        MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
}

internal fun emptyOfflineProvider(context: Context): MapTileProviderBase = MapTileProviderArray(
    TileSourceFactory.MAPNIK, SimpleRegisterReceiver(context), emptyArray<MapTileModuleProviderBase>()
).apply { setUseDataConnection(false) }

internal fun huntingTileProvider(context: Context, style: HuntingMapStyle, packages: List<OfflineMapPackage>,
    boundary: JSONObject? = null): MapTileProviderBase = when (style) {
    HuntingMapStyle.OPEN_STREET_MAP -> MapTileProviderBasic(context, TileSourceFactory.MAPNIK)
    HuntingMapStyle.AERIAL -> {
        check(NrwAerialImagery.withinServiceExtent(boundary)) { "Fuer dieses Revier sind noch keine freien Luftbilder eingerichtet." }
        MapTileProviderBasic(context, aerialTileSource())
    }
    HuntingMapStyle.OFFLINE -> {
        if (packages.isEmpty()) emptyOfflineProvider(context)
        else {
            MapsForgeTileSource.createInstance(context.applicationContext as Application)
            val source = MapsForgeTileSource.createFromFiles(packages.map { it.file }.toTypedArray(), null,
                "JagdkarteOffline-${packages.map { it.id }.sorted().joinToString("-")}")
            object : MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null) {
                private var disposed = false
                override fun detach() {
                    if (disposed) return
                    disposed = true
                    super.detach()
                    synchronized(source) { source.dispose() }
                }
            }.apply { setUseDataConnection(false) }
        }
    }
}