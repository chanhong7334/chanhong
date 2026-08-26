package com.phonewebcam.app

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private lateinit var switchCameraButton: Button

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private lateinit var httpClient: OkHttpClient

    private var discoverySocket: DatagramSocket? = null
    private val discoveryRunning = AtomicBoolean(false)

    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)

    private val prefs by lazy { getSharedPreferences("phone_webcam", MODE_PRIVATE) }

    companion object {
        private const val WS_PORT = 8765
        private const val DISCOVERY_PORT = 47777
        private const val REQUEST_PERMISSIONS_CODE = 10
        private const val TAG = "PhoneWebcam"

        // 1-byte message type prefix, so video and audio can share one WebSocket connection.
        private const val MSG_TYPE_VIDEO: Byte = 0x01
        private const val MSG_TYPE_AUDIO: Byte = 0x02

        private const val AUDIO_SAMPLE_RATE = 44100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        ipInput = findViewById(R.id.ipInput)
        connectButton = findViewById(R.id.connectButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)

        ipInput.setText(prefs.getString("last_ip", ""))

        cameraExecutor = Executors.newSingleThreadExecutor()
        httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val neededPermissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, neededPermissions.toTypedArray(), REQUEST_PERMISSIONS_CODE
            )
        } else {
            startCamera()
        }

        connectButton.setOnClickListener {
            if (isConnected.get()) disconnect() else connect()
        }

        switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCameraUseCases()
        }

        startDiscoveryListener()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val cameraIndex = permissions.indexOf(android.Manifest.permission.CAMERA)
            val cameraGranted = cameraIndex == -1 ||
                (grantResults.getOrNull(cameraIndex) == PackageManager.PERMISSION_GRANTED)
            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show()
            }

            val audioIndex = permissions.indexOf(android.Manifest.permission.RECORD_AUDIO)
            val audioGranted = audioIndex == -1 ||
                (grantResults.getOrNull(audioIndex) == PackageManager.PERMISSION_GRANTED)
            if (!audioGranted) {
                Toast.makeText(this, "마이크 권한이 없어 오디오는 전송되지 않습니다", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { imageProxy -> processFrame(imageProxy) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "카메라 바인딩 실패", e)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!isConnected.get() || webSocket == null) {
            imageProxy.close()
            return
        }
        try {
            val jpegBytes = imageProxyToJpeg(imageProxy, quality = 65)
            val packet = ByteArray(jpegBytes.size + 1)
            packet[0] = MSG_TYPE_VIDEO
            System.arraycopy(jpegBytes, 0, packet, 1, jpegBytes.size)
            webSocket?.send(packet.toByteString(0, packet.size))
        } catch (e: Exception) {
            Log.e(TAG, "프레임 변환 실패", e)
        } finally {
            imageProxy.close()
        }
    }

    /** YUV_420_888(with arbitrary row/pixel strides) -> NV21 -> JPEG, then rotated to upright. */
    private fun imageProxyToJpeg(image: ImageProxy, quality: Int): ByteArray {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)

        val rotation = image.imageInfo.rotationDegrees
        val jpeg = out.toByteArray()
        return if (rotation != 0) rotateJpeg(jpeg, rotation, quality) else jpeg
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height + 2 * (width / 2) * (height / 2))

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var pos = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (yPixelStride == 1) {
                yBuffer.position(rowStart)
                yBuffer.get(nv21, pos, width)
                pos += width
            } else {
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(rowStart + col * yPixelStride)
                }
            }
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }

        return nv21
    }

    private fun rotateJpeg(jpeg: ByteArray, degrees: Int, quality: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        rotated.recycle()
        return out.toByteArray()
    }

    // ---------- Audio (microphone) ----------

    private fun startAudioStreaming() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "마이크 권한이 없어 오디오 스트리밍을 시작하지 않습니다")
            return
        }
        if (audioRunning.getAndSet(true)) return

        audioThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, encoding)
                if (minBufSize <= 0) {
                    Log.e(TAG, "AudioRecord 버퍼 크기 계산 실패")
                    return@Thread
                }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE, channelConfig, encoding, minBufSize * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 초기화 실패")
                    return@Thread
                }

                recorder.startRecording()
                val chunk = ByteArray(2048)
                while (audioRunning.get()) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read > 0 && isConnected.get()) {
                        val packet = ByteArray(read + 1)
                        packet[0] = MSG_TYPE_AUDIO
                        System.arraycopy(chunk, 0, packet, 1, read)
                        webSocket?.send(packet.toByteString(0, packet.size))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "오디오 캡처 실패", e)
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }
                recorder?.release()
            }
        }
        audioThread?.start()
    }

    private fun stopAudioStreaming() {
        audioRunning.set(false)
        audioThread = null
    }

    // ---------- Networking ----------

    private fun connect() {
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "PC IP 주소를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("last_ip", ip).apply()

        val request = Request.Builder().url("ws://$ip:$WS_PORT/ws").build()
        statusText.text = "연결 중..."
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                startAudioStreaming()
                runOnUiThread {
                    statusText.text = "✅ 연결됨: $ip"
                    connectButton.text = "연결 끊기"
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected.set(false)
                stopAudioStreaming()
                runOnUiThread {
                    statusText.text = "❌ 연결 실패: ${t.message}"
                    connectButton.text = "연결"
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected.set(false)
                stopAudioStreaming()
                runOnUiThread {
                    statusText.text = "연결 끊김"
                    connectButton.text = "연결"
                }
            }
        })
    }

    private fun disconnect() {
        webSocket?.close(1000, "user disconnect")
        webSocket = null
        isConnected.set(false)
        stopAudioStreaming()
        statusText.text = "연결 안됨"
        connectButton.text = "연결"
    }

    /** Listens for UDP broadcast advertisements sent by pc_server.py to auto-fill the PC's IP. */
    private fun startDiscoveryListener() {
        if (discoveryRunning.getAndSet(true)) return
        Thread {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                }
                discoverySocket = socket
                val buf = ByteArray(512)
                while (discoveryRunning.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val json = JSONObject(text)
                        if (json.optString("app") == "phone-webcam") {
                            val senderIp = packet.address.hostAddress ?: continue
                            runOnUiThread {
                                if (ipInput.text.isNullOrBlank() && !isConnected.get()) {
                                    ipInput.setText(senderIp)
                                    statusText.text = "PC 자동 검색됨: $senderIp"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed packets / timeouts
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "검색 리스너 시작 실패 (수동으로 IP를 입력하세요)", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryRunning.set(false)
        discoverySocket?.close()
        stopAudioStreaming()
        cameraExecutor.shutdown()
        webSocket?.close(1000, "activity destroyed")
    }
}
