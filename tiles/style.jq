# Turns OSM Bright's style-local.json into our style template (see Dockerfile). It points the style
# at our own tiles, sprites and glyphs, so it needs no external host and no key.
# __TILES_BASE_URL__ is replaced at run time with TILES_BASE_URL (build-tiles.sh).
.name = "UrbanPulse base map (OSM Bright)"
| del(.metadata)
| .center = [-4.42, 36.72]
| .zoom = 12
| .sources = {
    openmaptiles: {
      type: "vector",
      url: "pmtiles://__TILES_BASE_URL__/malaga.pmtiles",
      attribution: "<a href=\"https://openmaptiles.org/\">© OpenMapTiles</a> <a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"
    }
  }
| .sprite = "__TILES_BASE_URL__/sprite"
| .glyphs = "__TILES_BASE_URL__/fonts/{fontstack}/{range}.pbf"
