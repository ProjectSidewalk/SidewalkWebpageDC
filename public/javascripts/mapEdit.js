function MapEdit(mapboxAPIKey) {
    L.mapbox.accessToken = mapboxAPIKey;

    // Construct a bounding box for this map that the user cannot move out of
    // https://www.mapbox.com/mapbox.js/example/v1.0.0/maxbounds/
    var southWest = L.latLng(38.761, -77.262),
        northEast = L.latLng(39.060, -76.830),
        bounds = L.latLngBounds(southWest, northEast),

        map = L.mapbox.map('map', null, {
            // set that bounding box as maxBounds to restrict moving the map
            // see full maxBounds documentation:
            // http://leafletjs.com/reference.html#map-maxbounds
            maxBounds: bounds,
            maxZoom: 19,
            minZoom: 9
        })
            .addLayer(L.mapbox.styleLayer('mapbox://styles/mapbox/streets-v11'))
            .fitBounds(bounds)
            .setView([38.912651, -76.993827], 16);

    var svg = d3.select(map.getPanes().overlayPane).append("svg"),
        g = svg.append("g").attr("class", "leaflet-zoom-hide");

    var currentBounds = map.getBounds(),
        southWest = currentBounds.getSouthWest(),
        northEast = currentBounds.getNorthEast(),
        minLat = southWest.lat,
        minLng = southWest.lng,
        maxLat = northEast.lat,
        maxLng = northEast.lng,
        url = "/geometry/streets?minLat=" + minLat + "&minLng=" + minLng + "&maxLat=" + maxLat + "&maxLng=" + maxLng,
        graph = new Graph(_),
        graphEdit;

    //$.getJSON(url, function (data) {
    //    console.log(data)
    //    L.geoJson(data).addTo(map);
    //})

    d3.json(url, function(error, collection) {
        if (error) throw error;
        var vertices = {};
        var vid = 0, eid = 0;
        for (var i = collection.features.length - 1; i >= 0; i--) {
            var coordinates = collection.features[i].geometry.coordinates,
                j,
                len = coordinates.length,
                vertices = [];

            for (j = 0; j < len; j++) {
                vertices.push(graph.addVertex(vid++, coordinates[j][0], coordinates[j][1]));
            }
            for (j = 0; j < len - 1; j++) {
                graph.addEdge(eid++, vertices[j], vertices[j + 1]);
            }
        }

        graphEdit = new GraphEdit(d3, _, map, graph);
    });
}
