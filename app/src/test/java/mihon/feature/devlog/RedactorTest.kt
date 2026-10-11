package mihon.feature.devlog

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The dev log is meant to be emailed, and the cloud engines' keys travel in
 * places an exception message can quote. None of them may reach the file.
 */
@Execution(ExecutionMode.CONCURRENT)
class RedactorTest {

    private val googleKey = "AIzaSyFAKEfakeFAKEfakeFAKEfakeFAKEfake12"

    @Test
    fun `masks a key in a Google Vision or Gemini URL`() {
        val line = "HTTP 403 for https://vision.googleapis.com/v1/images:annotate?key=$googleKey&alt=json"
        val redacted = Redactor.redact(line)
        redacted shouldNotContain googleKey
        redacted shouldBe "HTTP 403 for https://vision.googleapis.com/v1/images:annotate?key=***&alt=json"
    }

    @Test
    fun `masks a Google key quoted on its own`() {
        Redactor.redact("key was $googleKey.") shouldNotContain googleKey
    }

    @Test
    fun `masks DeepL keys, free and paid`() {
        val free = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b:fx"
        val paid = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5c"
        Redactor.redact("Authorization: DeepL-Auth-Key $free") shouldNotContain "0f1e2d3c"
        Redactor.redact("bad key $paid rejected") shouldNotContain "0f1e2d3c"
    }

    @Test
    fun `masks an Azure subscription key header`() {
        val key = "0123456789abcdef0123456789abcdef"
        Redactor.redact("Ocp-Apim-Subscription-Key: $key") shouldNotContain key
    }

    @Test
    fun `leaves ordinary lines alone`() {
        val line = "region read: THE LADY SAID SHE | WOULD COME BACK"
        Redactor.redact(line) shouldBe line
    }
}
