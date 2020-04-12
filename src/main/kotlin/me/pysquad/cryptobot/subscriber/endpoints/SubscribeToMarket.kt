package me.pysquad.cryptobot.subscriber.endpoints

import me.pysquad.cryptobot.Channel
import me.pysquad.cryptobot.CoinbaseMessageType.SUBSCRIBE
import me.pysquad.cryptobot.ProductId
import me.pysquad.cryptobot.coinbase.CoinbaseApi
import me.pysquad.cryptobot.common.Endpoint
import me.pysquad.cryptobot.subscriber.CoinbaseSubscribeRequest
import org.http4k.contract.meta
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK

class SubscribeToMarket(val coinbase: CoinbaseApi): Endpoint {
    private val exampleSubscribeRequest =
        CoinbaseSubscribeRequest(
            type = SUBSCRIBE,
            productIds = listOf(ProductId("ETH-GBP")),
            channels = listOf(Channel("ticker"))
        )

    override val contractRoute =
        "/market/subscribe" meta {
            summary = "Subscribes to the real-time market data flow on orders and trades."
            receiving(CoinbaseSubscribeRequest.lens to exampleSubscribeRequest)
            returning(OK)
        } bindContract POST to handler()

    private fun handler(): HttpHandler = { req: Request ->
        with(CoinbaseSubscribeRequest.lens(req)) {
            coinbase.subscribe(this)
            Response(OK)
        }
    }
}