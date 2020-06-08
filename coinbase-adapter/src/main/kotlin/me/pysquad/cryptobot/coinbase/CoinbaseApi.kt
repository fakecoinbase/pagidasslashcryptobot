package me.pysquad.cryptobot.coinbase

import me.pysquad.cryptobot.CoinbaseAdapterJson
import me.pysquad.cryptobot.CoinbaseAdapterRepoImpl
import me.pysquad.cryptobot.CoinbaseAdapterRepository
import me.pysquad.cryptobot.CoinbaseSubscribeRequest
import me.pysquad.cryptobot.RealTimeDb
import me.pysquad.cryptobot.config.ConfigReader
import me.pysquad.cryptobot.security.SecurityProvider
import me.pysquad.cryptobot.security.SecurityProvider.Companion.hmacSHA256
import org.apache.commons.codec.binary.Base64
import org.http4k.client.ApacheClient
import org.http4k.client.WebsocketClient
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Uri
import org.http4k.format.Jackson
import org.http4k.websocket.WsMessage
import java.time.Instant
import kotlin.concurrent.thread

val coinbaseWsFeedUri = Uri.of(ConfigReader.coinbase.wsFeed)
val coinbaseSandboxUri = ConfigReader.coinbase.sandboxUri

interface CoinbaseApi {
    fun subscribe(subscribeRequest: CoinbaseSubscribeRequest): Thread
    fun getSandboxCoinbaseProfiles(): List<GetSandboxCoinbaseMessage>

    companion object {

        fun buildIt() = Client(
            CoinbaseAdapterRepoImpl(RealTimeDb),
            SecurityProvider.buildIt()
        )

        fun Client(coinbaseRepo: CoinbaseAdapterRepository, securityProvider: SecurityProvider) = object: CoinbaseApi {

            override fun subscribe(subscribeRequest: CoinbaseSubscribeRequest) = thread {
                with(WebsocketClient.blocking(coinbaseWsFeedUri)) {
                    send(WsMessage(subscribeRequest.toNativeCoinbaseRequest()))
                    received().first().apply(::println)
                    received().storeInChunks()
                }
            }

            override fun getSandboxCoinbaseProfiles(): List<GetSandboxCoinbaseMessage> = with(ApacheClient()) {
                CoinbaseAdapterJson {
                    this@with(Request(GET, "$coinbaseSandboxUri/profiles").asCoinbaseSandboxAuthenticated())
                            .bodyString()
                            .asJsonObject()
                            .asJsonArray()
                            .map { jsonPayload ->
                                GetSandboxCoinbaseMessage(
                                        jsonPayload["id"].asText(),
                                        jsonPayload["user_id"].asText(),
                                        jsonPayload["name"].asText(),
                                        jsonPayload["active"].asBoolean(),
                                        jsonPayload["is_default"].asBoolean(),
                                        jsonPayload["created_at"].asText()
                                )
                            }
                }
            }

            private fun Sequence<WsMessage>.storeInChunks(sizeOfChunk: Int = 6) {
                for (listOfMessages in chunked(sizeOfChunk)) {
                    coinbaseRepo.store(
                        listOfMessages.map { CoinbaseProductMessage.wsLens(it) }
                    )
                }
            }

            fun Request.asCoinbaseSandboxAuthenticated(): Request {
                val timestamp = String.format("%.3f", Instant.now().toEpochMilli() / 1000.0)
                val coinbaseSandbox = securityProvider.getCoinbaseSandboxApiCredentials()

                // build the message
                val message = (timestamp + method.name + uri.path + bodyString()).toByteArray()

                // base64 decode the secret
                val secret = Base64.decodeBase64(coinbaseSandbox.secret)

                // sign the message with the hmac key
                val signature = hmacSHA256(message, secret)

                // finally base64 encode the result
                val encodedSignature = String(Base64.encodeBase64(signature))

                return headers(listOf(
                        "CB-ACCESS-KEY" to coinbaseSandbox.key,
                        "CB-ACCESS-SIGN" to encodedSignature,
                        "CB-ACCESS-TIMESTAMP" to timestamp,
                        "CB-ACCESS-PASSPHRASE" to coinbaseSandbox.passphrase
                ))
            }

        }
    }
}

private fun CoinbaseSubscribeRequest.toNativeCoinbaseRequest() = Jackson {
    val productIdsJson = productIds.map { string(it.value) }
    val channelsJson = channels.map { string(it.value) }
    obj(
        "type" to type.name.toLowerCase().asJsonValue(),
        "product_ids" to array(productIdsJson),
        "channels" to array(channelsJson)
    ).toPrettyString()
}