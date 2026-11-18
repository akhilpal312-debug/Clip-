package com.aiclipcreator.core.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import com.aiclipcreator.core.audio.AudioNormalizer
import com.aiclipcreator.core.audio.PcmExtractor
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.AspectRatio
import com.aiclipcreator.core.models.CaptionCue
import com.aiclipcreator.core.models.CaptionStyle
import com.aiclipcreator.core.models.RenderResult
import com.aiclipcreator.core.models.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class RenderRequest(
    val sourceUri: Uri,
    val outputFile: File,
    val startMs: Long,
    val endMs: Long,
    val aspect: AspectRatio,
    val geometry: ExportGeometry,
    val sourceMetadata: VideoMetadata,
    val captions: List<CaptionCue> = emptyList(),
    val captionStyle: CaptionStyle = CaptionStyle(),
    val burnCaptions: Boolean = true,
    val normalizeAudio: Boolean = true,
    val cropCenterX: Float = 0.5f,
    val cropCenterY: Float = 0.5f
)

/**
 * Renders a clip with MediaCodec + OpenGL:
 * decode video onto a SurfaceTexture, cover-fit it into the requested aspect, burn captions,
 * re-encode H.264 and mux with a re-encoded AAC track.
 *
 * Frames are pulled one at a time so no frame is dropped and memory stays flat.
 */
class ClipRenderer(private val context: Context) {

    private val captionOverlay = CaptionOverlay()
    private val log = "ClipRenderer"

    suspend fun render(
        request: RenderRequest,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Float) -> Unit = {}
    ): Result<RenderResult> = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        require(request.endMs > request.startMs) { "Invalid clip range" }
        request.outputFile.parentFile?.mkdirs()
        if (request.outputFile.exists()) request.outputFile.delete()

        var muxer: MediaMuxer? = null
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var encoderSurface: Surface? = null
        var decoderSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var egl: EglCore? = null
        var eglSurface: EGLSurface? = null
        var videoProgram: Program? = null
        var overlayProgram: Program? = null
        var outputStarted = false

        try {
            val geometry = request.geometry
            val startUs = request.startMs * 1000
            val endUs = request.endMs * 1000

            // ---------------- audio ----------------
            var audioSamples = ShortArray(0)
            var audioRate = 44_100
            if (request.sourceMetadata.hasAudio) {
                val pcmResult = PcmExtractor(context)
                    .decodeRange(request.sourceUri, request.startMs, request.endMs, 44_100, cancelled = cancelled)
                if (pcmResult.isSuccess) {
                    val pcm = pcmResult.getOrThrow()
                    audioSamples = pcm.samples
                    audioRate = pcm.sampleRate
                    // Trim to the exact clip length so audio and video end together.
                    val wanted = ((request.endMs - request.startMs) / 1000.0 * audioRate).toInt()
                    if (audioSamples.size > wanted && wanted > 0) audioSamples = audioSamples.copyOf(wanted)
                } else {
                    Logx.w("Audio range decode failed, rendering video only: ${pcmResult.exceptionOrNull()?.message}", log)
                }
            }
            val audioGain = if (request.normalizeAudio && audioSamples.isNotEmpty()) {
                AudioNormalizer.gainFor(audioSamples)
            } else 1f
            val hasAudio = audioSamples.isNotEmpty()

            // ---------------- source ----------------
            val srcExtractor = MediaExtractor()
            srcExtractor.setDataSource(context, request.sourceUri, null)
            extractor = srcExtractor
            val videoTrackIndex = (0 until srcExtractor.trackCount).firstOrNull { i ->
                srcExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("Source has no video track")
            val inputFormat = srcExtractor.getTrackFormat(videoTrackIndex)
            val srcMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            srcExtractor.selectTrack(videoTrackIndex)
            srcExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // ---------------- video encoder ----------------
            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                geometry.width,
                geometry.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, geometry.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, geometry.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoderSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            muxer = MediaMuxer(request.outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoTrackRef = intArrayOf(-1)
            val audioTrackRef = intArrayOf(-1)
            var muxerStarted = false
            val pendingTracks = ArrayList<Int>()
            val pendingBuffers = ArrayList<ByteBuffer>()
            val pendingInfos = ArrayList<MediaCodec.BufferInfo>()

            fun tryStartMuxer() {
                if (muxerStarted) return
                val needAudio = hasAudio && audioEncoder != null
                if (videoTrackRef[0] < 0) return
                if (needAudio && audioTrackRef[0] < 0) return
                muxer!!.start()
                muxerStarted = true
                outputStarted = true
                for (i in pendingBuffers.indices) {
                    val track = if (pendingTracks[i] == 0) videoTrackRef[0] else audioTrackRef[0]
                    val info = pendingInfos[i]
                    if (track >= 0 && info.size > 0) muxer!!.writeSampleData(track, pendingBuffers[i], info)
                }
                pendingTracks.clear(); pendingBuffers.clear(); pendingInfos.clear()
            }

            fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                if (muxerStarted) {
                    val target = if (track == 0) videoTrackRef[0] else audioTrackRef[0]
                    if (target >= 0 && info.size > 0) muxer!!.writeSampleData(target, buffer, info)
                } else {
                    val copy = ByteBuffer.allocate(info.size)
                    copy.put(buffer)
                    copy.flip()
                    pendingTracks += track
                    pendingBuffers += copy
                    pendingInfos += MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                }
            }

            fun drainVideoEncoder(endOfStream: Boolean) {
                val encoder = videoEncoder ?: return
                val bufferInfo = MediaCodec.BufferInfo()
                var idleSpins = 0
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++idleSpins > 50) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoTrackRef[0] < 0) {
                                videoTrackRef[0] = muxer!!.addTrack(encoder.outputFormat)
                                tryStartMuxer()
                            }
                        }
                        outIndex >= 0 -> {
                            idleSpins = 0
                            val buf = encoder.getOutputBuffer(outIndex)!!
                            if (bufferInfo.size > 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    writeSample(0, buf, bufferInfo)
                                }
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        else -> if (!endOfStream) return
                    }
                }
            }

            // ---------------- audio encoder ----------------
            var audioFed = 0
            if (hasAudio) {
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, geometry.audioBitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()
            }

            fun drainAudioEncoder(endOfStream: Boolean) {
                val encoder = audioEncoder ?: return
                val bufferInfo = MediaCodec.BufferInfo()
                var idleSpins = 0
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++idleSpins > 50) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (audioTrackRef[0] < 0) {
                                audioTrackRef[0] = muxer!!.addTrack(encoder.outputFormat)
                                tryStartMuxer()
                            }
                        }
                        outIndex >= 0 -> {
                            idleSpins = 0
                            val buf = encoder.getOutputBuffer(outIndex)!!
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                writeSample(1, buf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        else -> if (!endOfStream) return
                    }
                }
            }

            fun feedAudio(maxSampleIndex: Int) {
                val encoder = audioEncoder ?: return
                var guard = 0
                while (audioFed < maxSampleIndex && audioFed < audioSamples.size) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex < 0) {
                        if (++guard > 100) return
                        continue
                    }
                    guard = 0
                    val buffer = encoder.getInputBuffer(inIndex)!!
                    buffer.clear()
                    val capacity = buffer.capacity() / 2
                    val count = minOf(capacity, maxSampleIndex - audioFed, audioSamples.size - audioFed)
                    if (count <= 0) {
                        encoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        return
                    }
                    val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    for (i in 0 until count) {
                        val v = audioSamples[audioFed + i] * audioGain
                        shorts.put(v.coerceIn(-32768f, 32767f).toInt().toShort())
                    }
                    val ptsUs = audioFed.toLong() * 1_000_000L / audioRate
                    encoder.queueInputBuffer(inIndex, 0, count * 2, ptsUs, 0)
                    audioFed += count
                    drainAudioEncoder(false)
                }
            }

            // ---------------- GL setup ----------------
            val core = EglCore()
            egl = core
            eglSurface = core.createWindowSurface(encoderSurface)
            core.makeCurrent(eglSurface)

            val vp = Program.video().also { it.create() }
            val op = Program.overlay().also { it.create() }
            videoProgram = vp
            overlayProgram = op

            val quad = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 0f, 1f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 0f, 1f
            )
            val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            quadBuffer.put(quad).position(0)

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val videoTexId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(videoTexId)
            surfaceTexture = st
            val frameReady = java.util.concurrent.atomic.AtomicBoolean(false)
            st.setOnFrameAvailableListener { frameReady.set(true) }
            st.setDefaultBufferSize(
                inputFormat.getInteger(MediaFormat.KEY_WIDTH),
                inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            )
            val decSurface = Surface(st)
            decoderSurface = decSurface
            decoder = MediaCodec.createDecoderByType(srcMime).apply {
                configure(inputFormat, decSurface, null, 0)
                start()
            }

            val srcW = request.sourceMetadata.displayWidth.toFloat().coerceAtLeast(1f)
            val srcH = request.sourceMetadata.displayHeight.toFloat().coerceAtLeast(1f)
            val srcAspect = srcW / srcH
            val dstAspect = geometry.width.toFloat() / geometry.height.toFloat()
            val scaleX = if (srcAspect > dstAspect) srcAspect / dstAspect else 1f
            val scaleY = if (srcAspect > dstAspect) 1f else dstAspect / srcAspect
            val panX = (0.5f - request.cropCenterX.coerceIn(0f, 1f)) * (scaleX - 1f)
            val panY = (0.5f - request.cropCenterY.coerceIn(0f, 1f)) * (scaleY - 1f)
            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)
            Matrix.translateM(mvp, 0, panX, panY, 0f)
            Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f)

            val rotation = request.sourceMetadata.rotationDegrees
            val rotationMatrix = FloatArray(16)
            Matrix.setIdentityM(rotationMatrix, 0)
            Matrix.translateM(rotationMatrix, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(rotationMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(rotationMatrix, 0, -0.5f, -0.5f, 0f)

            val stMatrix = FloatArray(16)
            val texMatrix = FloatArray(16)
            Matrix.setIdentityM(texMatrix, 0)

            GLES20.glViewport(0, 0, geometry.width, geometry.height)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            var captionTexId = -1
            var currentCueKey: String? = null
            var captionTexReady = false

            fun renderCaption(timeMs: Long) {
                if (!request.burnCaptions) return
                val cue = request.captions.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }
                val key = cue?.let { "${it.startMs}:${it.endMs}:${it.text}" }
                if (key != currentCueKey) {
                    currentCueKey = key
                    val bitmap = captionOverlay.bitmapFor(cue, request.captionStyle, geometry.width, geometry.height)
                    if (bitmap == null) {
                        captionTexReady = false
                    } else {
                        if (captionTexId < 0) {
                            val ids = IntArray(1)
                            GLES20.glGenTextures(1, ids, 0)
                            captionTexId = ids[0]
                        }
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captionTexId)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                        bitmap.recycle()
                        captionTexReady = true
                    }
                }
                if (!captionTexReady || captionTexId < 0) return
                val bandHalf = 0.16f
                val centerY = 1f - 2f * request.captionStyle.verticalPosition.coerceIn(0.1f, 0.95f)
                val overlay = floatArrayOf(
                    -1f, centerY - bandHalf, 0f, 1f, 0f, 1f,
                    1f, centerY - bandHalf, 0f, 1f, 1f, 1f,
                    -1f, centerY + bandHalf, 0f, 1f, 0f, 0f,
                    1f, centerY + bandHalf, 0f, 1f, 1f, 0f
                )
                val ob = ByteBuffer.allocateDirect(overlay.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                ob.put(overlay).position(0)
                op.use()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captionTexId)
                GLES20.glUniform1i(op.samplerLoc, 0)
                GLES20.glEnableVertexAttribArray(op.positionLoc)
                GLES20.glVertexAttribPointer(op.positionLoc, 4, GLES20.GL_FLOAT, false, 24, ob)
                ob.position(4)
                GLES20.glEnableVertexAttribArray(op.texCoordLoc)
                GLES20.glVertexAttribPointer(op.texCoordLoc, 2, GLES20.GL_FLOAT, false, 24, ob)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(op.positionLoc)
                GLES20.glDisableVertexAttribArray(op.texCoordLoc)
            }

            // ---------------- main loop ----------------
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var renderedFrames = 0
            var lastPtsUs = -1L
            var lastProgress = -1f
            var eosQueued = false

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (cancelled.get()) throw CancellationException("Render cancelled")

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (eosQueued) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val buf = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            val time = extractor.sampleTime
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex >= 0 -> {
                        val ptsUs = info.presentationTimeUs
                        val shouldRender = info.size > 0 && ptsUs >= startUs - 20_000 && ptsUs < endUs
                        if (shouldRender) {
                            decoder.releaseOutputBuffer(outIndex, true)
                            var waited = 0
                            while (!frameReady.get() && waited < 2_000) {
                                Thread.sleep(1)
                                waited++
                            }
                            if (!frameReady.get()) {
                                Logx.w("Frame wait timed out at ${ptsUs / 1000}ms", log)
                            }
                            frameReady.set(false)
                            st.updateTexImage()
                            st.getTransformMatrix(stMatrix)
                            Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rotationMatrix, 0)

                            GLES20.glClearColor(0f, 0f, 0f, 1f)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            vp.use()
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
                            GLES20.glUniform1i(vp.samplerLoc, 0)
                            GLES20.glUniformMatrix4fv(vp.mvpLoc, 1, false, mvp, 0)
                            GLES20.glUniformMatrix4fv(vp.texMatrixLoc, 1, false, texMatrix, 0)
                            quadBuffer.position(0)
                            GLES20.glEnableVertexAttribArray(vp.positionLoc)
                            GLES20.glVertexAttribPointer(vp.positionLoc, 4, GLES20.GL_FLOAT, false, 16, quadBuffer)
                            GLES20.glEnableVertexAttribArray(vp.texCoordLoc)
                            GLES20.glVertexAttribPointer(vp.texCoordLoc, 4, GLES20.GL_FLOAT, false, 16, quadBuffer)
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                            GLES20.glDisableVertexAttribArray(vp.positionLoc)
                            GLES20.glDisableVertexAttribArray(vp.texCoordLoc)

                            val clipTimeMs = ((ptsUs - startUs).coerceAtLeast(0L)) / 1000
                            renderCaption(clipTimeMs)
                            feedAudio(((ptsUs - startUs).coerceAtLeast(0L) * audioRate / 1_000_000L).toInt())

                            val safePts = if (ptsUs - startUs <= lastPtsUs) lastPtsUs + 1_000 else ptsUs - startUs
                            lastPtsUs = safePts
                            core.setPresentationTime(eglSurface, safePts * 1000)
                            core.swapBuffers(eglSurface)
                            drainVideoEncoder(false)
                            renderedFrames++

                            val progress = ((ptsUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f)
                            if (progress - lastProgress >= 0.02f) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                            if (ptsUs >= endUs - 20_000) eosQueued = true
                        } else {
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (ptsUs >= endUs) eosQueued = true
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            check(renderedFrames > 0) { "No frames could be decoded for this range" }
            if (videoTrackRef[0] < 0) throw IllegalStateException("Video encoder produced no output")

            if (hasAudio) {
                feedAudio(Int.MAX_VALUE)
                drainAudioEncoder(true)
            }
            videoEncoder.signalEndOfInputStream()
            drainVideoEncoder(true)
            tryStartMuxer()
            check(muxerStarted) { "Muxer did not receive both tracks" }
            onProgress(1f)

            vp.release()
            op.release()
            if (captionTexId >= 0) GLES20.glDeleteTextures(1, intArrayOf(captionTexId), 0)
            GLES20.glDeleteTextures(1, texIds, 0)

            st.release()
            surfaceTexture = null
            decSurface.release()
            decoderSurface = null
            core.releaseSurface(eglSurface)
            eglSurface = null
            core.release()
            egl = null

            muxer.stop()
            muxer.release()
            muxer = null

            val bytes = request.outputFile.length()
            Logx.i(
                "Rendered ${request.outputFile.name}: ${geometry.width}x${geometry.height} " +
                    "${bytes / 1024}KB frames=$renderedFrames in ${System.currentTimeMillis() - startedAt}ms",
                log
            )
            Result.success(
                RenderResult(
                    outputUri = request.outputFile.absolutePath,
                    durationMs = request.endMs - request.startMs,
                    bytes = bytes,
                    width = geometry.width,
                    height = geometry.height,
                    reEncoded = true
                )
            )
        } catch (c: CancellationException) {
            runCatching { request.outputFile.delete() }
            throw c
        } catch (t: Throwable) {
            Logx.e("Render failed", t, log)
            runCatching { request.outputFile.delete() }
            Result.failure(t)
        } finally {
            runCatching { surfaceTexture?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { extractor?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { encoderSurface?.release() }
            runCatching { videoProgram?.release() }
            runCatching { overlayProgram?.release() }
            if (egl != null) {
                runCatching { eglSurface?.let { egl!!.releaseSurface(it) } }
                runCatching { egl!!.release() }
            }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!outputStarted) runCatching { request.outputFile.delete() }
        }
    }

    /** Small GL program wrapper. */
    private class Program(private val vertexSrc: String, private val fragmentSrc: String) {
        var programId = 0
            private set
        var positionLoc = 0
            private set
        var texCoordLoc = 0
            private set
        var samplerLoc = 0
            private set
        var mvpLoc = 0
            private set
        var texMatrixLoc = 0
            private set

        fun create() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            programId = GLES20.glCreateProgram()
            GLES20.glAttachShader(programId, vs)
            GLES20.glAttachShader(programId, fs)
            GLES20.glLinkProgram(programId)
            val status = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(programId)
                GLES20.glDeleteProgram(programId)
                programId = 0
                throw IllegalStateException("GL link failed: $log")
            }
            positionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            texCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            samplerLoc = GLES20.glGetUniformLocation(programId, "uSampler")
            mvpLoc = GLES20.glGetUniformLocation(programId, "uMvp")
            texMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        }

        fun use() = GLES20.glUseProgram(programId)

        fun release() {
            if (programId != 0) GLES20.glDeleteProgram(programId)
            programId = 0
        }

        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Shader compile failed: $log")
            }
            return shader
        }

        companion object {
            private const val VIDEO_VERTEX = """
                uniform mat4 uMvp;
                uniform mat4 uTexMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = uMvp * aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """

            private const val VIDEO_FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uSampler;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uSampler, vTexCoord);
                }
            """

            private const val OVERLAY_VERTEX = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """

            private const val OVERLAY_FRAGMENT = """
                precision mediump float;
                uniform sampler2D uSampler;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uSampler, vTexCoord);
                }
            """

            fun video() = Program(VIDEO_VERTEX, VIDEO_FRAGMENT)
            fun overlay() = Program(OVERLAY_VERTEX, OVERLAY_FRAGMENT)
        }
    }
}
