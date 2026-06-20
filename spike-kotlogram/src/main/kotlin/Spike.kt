import com.github.badoualy.telegram.api.Kotlogram
import com.github.badoualy.telegram.api.TelegramApp
import com.github.badoualy.telegram.tl.api.TLAbsInputUser
import com.github.badoualy.telegram.tl.api.TLInputUserSelf
import com.github.badoualy.telegram.tl.api.TLUser
import com.github.badoualy.telegram.tl.core.TLVector
import com.github.badoualy.telegram.tl.exception.RpcErrorException

/**
 * Spike: does kotlogram (API layer 66, built 2017) still authenticate against
 * the CURRENT Telegram servers and make an authorized call?
 *
 * This is the make-or-break test for using kotlogram as the raw-MTProto foundation.
 * It performs a real login on YOUR account (the code is sent to your Telegram).
 *
 * Run:
 *   TG_API_ID=<id> TG_API_HASH=<hash> TG_PHONE=+39xxxxxxxxxx \
 *     ./gradlew -q --console=plain run
 * then type the login code when prompted.
 */
private fun env(name: String): String =
    System.getenv(name) ?: error("Missing env var $name")

private fun prompt(label: String): String {
    print(label)
    System.out.flush()
    return readlnOrNull()?.trim().orEmpty()
}

fun main() {
    val apiId = env("TG_API_ID").toInt()
    val apiHash = env("TG_API_HASH")
    val phone = env("TG_PHONE")

    println("kotlogram API_LAYER = ${Kotlogram.API_LAYER}")
    val app = TelegramApp(apiId, apiHash, "tgstream-spike", "Android", "0.1", "it")
    val client = Kotlogram.getDefaultClient(app, MemoryStorage())

    try {
        println("→ authSendCode for $phone ...")
        val sent = client.authSendCode(false, phone, true)
        println("  phoneCodeHash=${sent.phoneCodeHash} type=${sent.type}")

        val code = prompt("Enter the login code you received: ")

        val authorized = try {
            client.authSignIn(phone, sent.phoneCodeHash, code)
            true
        } catch (e: RpcErrorException) {
            if (e.message?.contains("SESSION_PASSWORD_NEEDED") == true) {
                println("  2FA is enabled on this account.")
                println("  NOTE: kotlogram RC3 uses the pre-2018 SRP algorithm; checkPassword will most likely FAIL.")
                println("  Attempting authCheckPassword anyway to confirm...")
                val pwd = prompt("Enter your 2FA password (will likely be rejected): ")
                try {
                    client.authCheckPassword(pwd)
                    true
                } catch (e2: Exception) {
                    println("  authCheckPassword FAILED: ${e2.message}")
                    false
                }
            } else {
                throw e
            }
        }

        if (!authorized) {
            println("RESULT: AUTH INCOMPLETE (2FA path failed). kotlogram is NOT viable as-is for 2FA accounts.")
            return
        }

        // Authorized RPC: fetch self.
        val inputs = TLVector<TLAbsInputUser>().apply { add(TLInputUserSelf()) }
        val users = client.usersGetUsers(inputs)
        val me = users.firstOrNull() as? TLUser
        println("RESULT: AUTH OK ✅  Logged in as id=${me?.id} firstName=${me?.firstName} username=${me?.username}")
        println("kotlogram CAN authenticate and make authorized calls against current servers.")
    } catch (e: Exception) {
        println("RESULT: FAILED ❌  ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        client.close()
        Kotlogram.shutdown()
    }
}
