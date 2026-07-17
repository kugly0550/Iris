package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ReplyRequest(
    val type: ReplyType = ReplyType.TEXT,
    val room: String,
    val data: JsonElement,
    val threadId: String? = null,
    // FILE 은 문자열, FILE_MULTIPLE 은 data 와 같은 순서의 문자열 배열.
    // 생략하면 매직 바이트로 추정한 확장자에 타임스탬프 이름이 붙는다.
    val name: JsonElement? = null,
)