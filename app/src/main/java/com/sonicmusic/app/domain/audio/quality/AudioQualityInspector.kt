package com.sonicmusic.app.domain.audio.quality

import com.sonicmusic.app.domain.audio.models.QualityEvidence
import com.sonicmusic.app.domain.model.AudioStreamCandidate

/**
 * Technical inspection component that verifies container format, audio codec,
 * genuine lossless vs lossy classification, transcode status, and source provenance.
 */
interface AudioQualityInspector {
    /**
     * Inspects candidate technical parameters, metadata, and provenance tags.
     */
    fun inspect(
        candidate: AudioStreamCandidate,
        metadata: Map<String, Any?> = emptyMap()
    ): QualityEvidence
}
