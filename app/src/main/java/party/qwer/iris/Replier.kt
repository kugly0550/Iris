package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.qwer.iris.Replier.Companion.SendMessageRequest
import java.io.File

// SendMsg : ye-seola/go-kdb

class Replier {
    companion object {
        private val messageChannel = Channel<SendMessageRequest>(Channel.CONFLATED)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        init {
            startMessageSender()
        }

        fun startMessageSender() {
            coroutineScope.launch {
                if (messageSenderJob?.isActive == true) {
                    messageSenderJob?.cancelAndJoin()
                }
                messageSenderJob = launch {
                    for (request in messageChannel) {
                        try {
                            mutex.withLock {
                                request.send()
                                delay(Configurable.messageSendRate)
                            }
                        } catch (e: Exception) {
                            System.err.println("Error sending message from channel: $e")
                        }
                    }
                }
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        private fun sendMessageInternal(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?
        ) {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"
                )
                putExtra("noti_referer", referer)
                putExtra("chat_id", chatId)

                putExtra("is_chat_thread_notification", threadId != null)
                if (threadId != null) {
                    putExtra("thread_id", threadId)
                }

                action = "com.kakao.talk.notification.REPLY_MESSAGE"

                val results = Bundle().apply {
                    putCharSequence("reply_message", msg)
                }

                val remoteInput = RemoteInput.Builder("reply_message").build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }

            AndroidHiddenApi.startService(intent)
        }

        fun sendMessage(referer: String, chatId: Long, msg: String, threadId: Long?) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMessageInternal(
                        referer, chatId, msg, threadId
                    )
                })
            }
        }


        fun sendPhoto(room: Long, base64ImageDataString: String) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendPhotoInternal(
                        room, base64ImageDataString
                    )
                })
            }
        }

        fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultiplePhotosInternal(
                        room, base64ImageDataStrings
                    )
                })
            }
        }

        fun sendVideo(room: Long, base64VideoDataString: String) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultipleVideosInternal(room, listOf(base64VideoDataString))
                })
            }
        }

        fun sendMultipleVideos(room: Long, base64VideoDataStrings: List<String>) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultipleVideosInternal(room, base64VideoDataStrings)
                })
            }
        }

        fun sendFile(room: Long, base64FileDataString: String, fileName: String?) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultipleFilesInternal(
                        room, listOf(base64FileDataString), fileName?.let { listOf(it) }
                    )
                })
            }
        }

        fun sendMultipleFiles(
            room: Long,
            base64FileDataStrings: List<String>,
            fileNames: List<String>?
        ) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultipleFilesInternal(room, base64FileDataStrings, fileNames)
                })
            }
        }

        private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
            val picDir = File(IMAGE_DIR_PATH).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            val uris = base64ImageDataStrings.mapIndexed { idx, base64ImageDataString ->
                val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
                val timestamp = System.currentTimeMillis().toString()

                val imageFile = File(picDir, "${timestamp}_${idx}.png").apply {
                    writeBytes(decodedImage)
                }

                val imageUri = Uri.fromFile(imageFile)
                mediaScan(imageUri)
                imageUri
            }

            if (uris.isEmpty()) {
                System.err.println("No image URIs created, cannot send multiple photos.")
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                AndroidHiddenApi.startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending multiple photos: $e")
                throw e
            }
        }


        /**
         * base64 디코딩된 미디어 바이트의 매직 바이트를 보고
         * (확장자, MIME 타입) 쌍을 추정한다. 모르면 mp4 로 폴백.
         */
        private fun detectVideoFormat(data: ByteArray): Pair<String, String> {
            if (data.size >= 6 &&
                data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte()
            ) {
                return "gif" to "image/gif"
            }
            if (data.size >= 4 &&
                data[0] == 0x1A.toByte() && data[1] == 0x45.toByte() &&
                data[2] == 0xDF.toByte() && data[3] == 0xA3.toByte()
            ) {
                return "webm" to "video/webm"
            }
            if (data.size >= 12 &&
                data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
                data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte()
            ) {
                return "mp4" to "video/mp4"
            }
            return "mp4" to "video/*"
        }

        private fun sendMultipleVideosInternal(room: Long, base64VideoDataStrings: List<String>) {
            val mediaDir = File(IMAGE_DIR_PATH).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            var firstMime: String? = null
            var mixed = false

            val uris = base64VideoDataStrings.mapIndexed { idx, base64Str ->
                val decoded = Base64.decode(base64Str, Base64.DEFAULT)
                val (ext, mime) = detectVideoFormat(decoded)
                if (firstMime == null) firstMime = mime
                else if (firstMime != mime) mixed = true

                val timestamp = System.currentTimeMillis().toString()
                val videoFile = File(mediaDir, "${timestamp}_${idx}.${ext}").apply {
                    writeBytes(decoded)
                }
                val uri = Uri.fromFile(videoFile)
                mediaScan(uri)
                uri
            }

            if (uris.isEmpty()) {
                System.err.println("No video URIs created, cannot send videos.")
                return
            }

            // 단일 GIF 는 image/gif 가 정확하지만, 여러 개거나 형식 섞인 경우
            // 안드로이드/카톡이 더 관대하게 받는 video/* 로 폴백한다.
            val intentType = when {
                uris.size == 1 && firstMime != null -> firstMime!!
                mixed -> "video/*"
                else -> firstMime ?: "video/*"
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = intentType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                AndroidHiddenApi.startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending videos: $e")
                throw e
            }
        }


        /**
         * 파일 전송 인텐트의 MIME. 확장자별로 정확한 MIME 을 넣지 않고 항상 이걸 쓴다.
         *
         * 카톡은 MIME 이 아니라 **파일명**으로 타입을 정한다 — 같은 PDF 를
         * application/pdf 로 보내든 application/octet-stream 으로 보내든 방에 찍히는
         * 결과(name, CDN 의 .pdf 확장자)가 동일한 걸 실측으로 확인했다.
         *
         * 반대로 text/* 는 **쓰면 안 된다**. 카톡 다이렉트 셰어가 text/plain·text/csv 를
         * 텍스트 공유 흐름으로 보내버려서 EXTRA_STREAM 을 무시하고 조용히 아무것도 안
         * 보낸다(에러도 없음). .txt 를 octet-stream 으로 보내면 정상 전송된다.
         * 그래서 확장자→MIME 표는 이득이 없고 함정만 있다.
         */
        private const val FILE_INTENT_MIME = "application/octet-stream"

        /** 이름이 없을 때만 쓰는 최소한의 매직 바이트 추정. 모르면 bin. */
        private fun detectFileExtension(data: ByteArray): String {
            if (data.size >= 4 &&
                data[0] == '%'.code.toByte() && data[1] == 'P'.code.toByte() &&
                data[2] == 'D'.code.toByte() && data[3] == 'F'.code.toByte()
            ) {
                return "pdf"
            }
            // PK.. — zip 계열(docx/xlsx/apk 도 여기 걸리지만 구분 불가하므로 zip)
            if (data.size >= 2 &&
                data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()
            ) {
                return "zip"
            }
            return "bin"
        }

        /**
         * 카톡에 표시될 파일명이 곧 디스크의 파일명이므로(file:// 의 마지막 경로 조각),
         * 경로 조작과 파일시스템에서 문제되는 문자를 제거한다. 이름이 비면 타임스탬프로 폴백.
         */
        private val UNSAFE_NAME_CHARS = Regex("""[\\/:*?"<>|\x00-\x1F]""")

        private fun safeFileName(raw: String?, idx: Int, data: ByteArray): String {
            val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = base.replace(UNSAFE_NAME_CHARS, "_").take(120)
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                return "${System.currentTimeMillis()}_$idx.${detectFileExtension(data)}"
            }
            return cleaned
        }

        /** 같은 이름이 이미 있으면 name_1.ext, name_2.ext … 로 비켜 쓴다. */
        private fun uniqueFile(dir: File, fileName: String): File {
            if (!File(dir, fileName).exists()) {
                return File(dir, fileName)
            }
            val stem = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "")
            for (i in 1 until 1000) {
                val next = if (ext.isEmpty()) "${stem}_$i" else "${stem}_$i.$ext"
                val candidate = File(dir, next)
                if (!candidate.exists()) {
                    return candidate
                }
            }
            return File(dir, "${System.currentTimeMillis()}_$fileName")
        }

        private fun sendMultipleFilesInternal(
            room: Long,
            base64FileDataStrings: List<String>,
            fileNames: List<String>?
        ) {
            if (base64FileDataStrings.isEmpty()) {
                System.err.println("No file data given, cannot send files.")
                return
            }

            val fileDir = File(IMAGE_DIR_PATH).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            val uris = base64FileDataStrings.mapIndexed { idx, base64Str ->
                val decoded = Base64.decode(base64Str, Base64.DEFAULT)
                val name = safeFileName(fileNames?.getOrNull(idx), idx, decoded)

                // 이미지/영상과 달리 mediaScan 을 하지 않는다. 카톡은 file:// 을 직접 읽고,
                // 문서·zip 은 MediaStore 에 색인될 것도 아니다. (am start 로 스캔 없이
                // 전송되는 걸 실측 확인함.)
                val target = uniqueFile(fileDir, name).apply { writeBytes(decoded) }
                Uri.fromFile(target)
            }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = FILE_INTENT_MIME
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = FILE_INTENT_MIME
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }.apply {
                setPackage("com.kakao.talk")
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                AndroidHiddenApi.startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending files: $e")
                throw e
            }
        }


        internal fun interface SendMessageRequest {
            suspend fun send()
        }

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = uri
            }
            AndroidHiddenApi.broadcastIntent(mediaScanIntent)
        }
    }
}