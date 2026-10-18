package life.mosaic.fit.voicepoc

internal object BlueMath {
    fun guidance(cond: FloatArray, uncond: FloatArray, scale: Float): FloatArray {
        require(cond.size == uncond.size)
        return FloatArray(cond.size) { uncond[it] + scale * (cond[it] - uncond[it]) }
    }
    fun vocoderLatent(x: FloatArray, mean: FloatArray, std: FloatArray,
        scale: Float, channels: Int, factor: Int, time: Int): FloatArray {
        require(channels > 0 && factor > 0 && time > 0 && scale > 0)
        require(x.size == channels * factor * time && mean.size == channels * factor && std.size == mean.size)
        val z = FloatArray(x.size)
        for (channel in 0 until channels) for (t in 0 until time) for (f in 0 until factor) {
            val c = channel * factor + f
            z[channel * time * factor + t * factor + f] = x[c * time + t] / scale * std[c] + mean[c]
        }
        return z
    }
}
