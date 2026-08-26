package io.roadbook.karoo.util

/**
 * Decode a Google encoded polyline (precision 5) to a list of (lat, lng) pairs.
 * karoo-ext delivers the route as a precision-5 encoded polyline.
 */
fun decodePolyline(encoded: String, precision: Int = 5): List<Pair<Double, Double>> {
    val factor = Math.pow(10.0, precision.toDouble())
    val points = ArrayList<Pair<Double, Double>>()
    var index = 0
    var lat = 0
    var lng = 0

    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        points.add(Pair(lat / factor, lng / factor))
    }
    return points
}
