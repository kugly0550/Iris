// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.util.concurrent.TimeUnit

// 이미지·영상·파일을 카톡에 넘기기 전에 써 두는 폴더. 카톡이 file:// 로 읽어야 하니 그 사용자 카톡의
// 앱 전용 외부 저장소여야 한다. 듀얼앱(user 97) 인스턴스는 IRIS_MEDIA_DIR 로 그 사용자 경로를 준다
// (/storage/emulated/97/Android/data/com.kakao.talk/files) — user 0 의 /sdcard 는 그 카톡이 못 읽는다.
val IMAGE_DIR_PATH: String = System.getenv("IRIS_MEDIA_DIR") ?: "/sdcard/Android/data/com.kakao.talk/files"

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val wsEventFlow = MutableSharedFlow<String>()

                val notificationReferer = readNotificationReferer()

                Replier.startMessageSender()
                println("Message sender thread started")

                val kakaoDb = KakaoDB()
                val observerHelper = ObserverHelper(kakaoDb, wsEventFlow)

                val dbObserver = DBObserver(kakaoDb, observerHelper)
                dbObserver.startPolling()
                println("DBObserver started")

                val notificationPoller = NotificationPoller()
                notificationPoller.startPolling()
                println("Notification Poller started")

                val imageDeleter = ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1))
                imageDeleter.startDeletion()
                println("ImageDeleter started, and will delete images older than 1 hour.")

                val irisServer = IrisServer(
                    kakaoDb, dbObserver, observerHelper, notificationReferer, wsEventFlow
                )
                irisServer.startServer()
                println("Iris Server started")

                kakaoDb.closeConnection()
            } catch (e: Exception) {
                System.err.println("Iris Error")
                e.printStackTrace()
            }
        }

        private fun readNotificationReferer(): String {
            val appPath = PathUtils.getAppPath()
            val prefsFile = File("${appPath}shared_prefs/KakaoTalk.hw.perferences.xml")
            val data = prefsFile.bufferedReader().use {
                it.readText()
            }
            val regex = Regex("""<string name="NotificationReferer">(.*?)</string>""")
            val match = regex.find(data) ?: throw Exception("failed to extract referer from data")

            val referer =
                match.groups[1]?.value ?: throw Exception("failed to extract referer from data")

            return referer
        }
    }
}

