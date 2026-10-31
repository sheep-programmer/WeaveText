package com.weavetext.ime.captions

import kotlin.math.sqrt

/**
 * 在线说话人分配：每段话的说话人嵌入与已知说话人的质心比较，余弦相似度达到阈值就归入该说话人并更新质心，
 * 否则开一个新说话人。说话人编号从 1 开始。
 *
 * Online speaker assignment: an utterance embedding is compared with each known speaker's centroid by cosine
 * similarity. At or above the threshold it joins that speaker and nudges the centroid; otherwise a new speaker starts.
 * Speaker numbers start at 1.
 */
class SpeakerTracker(private val threshold: Float = 0.6f) {
    private val centroids = ArrayList<FloatArray>()
    private val counts = ArrayList<Int>()

    val speakers: Int get() = centroids.size

    /** 返回说话人编号（从 1 开始）。Returns the speaker number, starting from 1. */
    fun assign(embedding: FloatArray): Int {
        val e = unit(embedding)
        var best = -1
        var bestSim = -2f
        for (i in centroids.indices) {
            val sim = cosine(e, centroids[i])
            if (sim > bestSim) { bestSim = sim; best = i }
        }
        if (best >= 0 && bestSim >= threshold) {
            val n = counts[best]
            val c = centroids[best]
            for (d in c.indices) c[d] = (c[d] * n + e[d]) / (n + 1)
            centroids[best] = unit(c)
            counts[best] = n + 1
            return best + 1
        }
        centroids += e
        counts += 1
        return centroids.size
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
        }

        private fun unit(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            n = sqrt(n)
            return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
        }
    }
}
