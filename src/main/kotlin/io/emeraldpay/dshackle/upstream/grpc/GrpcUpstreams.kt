/**
 * Copyright (c) 2019 ETCDEV GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.grpc

import com.fasterxml.jackson.databind.ObjectMapper
import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.ReactorBlockchainGrpc
import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.FileResolver
import io.emeraldpay.dshackle.config.AuthConfig
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.startup.UpstreamChange
import io.emeraldpay.grpc.Chain
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.NettyChannelBuilder
import io.infinitape.etherjar.rpc.emerald.ReactorEmeraldClient
import io.netty.handler.ssl.*
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import java.net.ConnectException
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GrpcUpstreams(
        private val id: String,
        private val host: String,
        private val port: Int,
        private val objectMapper: ObjectMapper,
        private val auth: AuthConfig.ClientTlsAuth? = null,
        private val fileResolver: FileResolver
) {
    private val log = LoggerFactory.getLogger(GrpcUpstreams::class.java)

    var timeout = Defaults.timeout

    private var client: ReactorBlockchainGrpc.ReactorBlockchainStub? = null
    private val known = HashMap<Chain, GrpcUpstream>()
    private val lock = ReentrantLock()
    private var grpcTransport: ReactorEmeraldClient? = null

    fun start(): Flux<UpstreamChange> {
        val channel: ManagedChannelBuilder<*> = if (auth != null && StringUtils.isNotEmpty(auth.ca)) {
            NettyChannelBuilder.forAddress(host, port)
                    .useTransportSecurity()
                    .enableRetry()
                    .maxRetryAttempts(3)
                    .sslContext(withTls(auth))
        } else {
            log.warn("Using insecure connection to $host:$port")
            ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
        }

        val client = ReactorBlockchainGrpc.newReactorStub(channel.build())
        this.client = client
        this.grpcTransport = ReactorEmeraldClient.newBuilder()
                .connectUsing(client.channel)
                .objectMapper(objectMapper)
                .build()

        val statusSubscription = AtomicReference<Disposable>()

        val updates = Flux.interval(Duration.ZERO, Duration.ofMinutes(1))
                .flatMap {
                    client.describe(BlockchainOuterClass.DescribeRequest.newBuilder().build())
                }.onErrorContinue { t, u ->
                    if (ExceptionUtils.indexOfType(t, ConnectException::class.java) >= 0) {
                        log.warn("gRPC upstream $host:$port is unavailable")
                        known.values.forEach {
                            it.setStatus(UpstreamAvailability.UNAVAILABLE)
                        }
                    } else {
                        log.error("Failed to get description from $host:$port", t)
                    }
                }.flatMap { value ->
                    processDescription(value)
                }.doOnNext {
                    val subscription = client.subscribeStatus(BlockchainOuterClass.StatusRequest.newBuilder().build())
                            .subscribe { value ->
                                val chain = Chain.byId(value.chain.number)
                                if (chain != Chain.UNSPECIFIED) {
                                    known[chain]?.onStatus(value)
                                }
                            }
                    statusSubscription.updateAndGet { prev ->
                        prev?.dispose()
                        subscription
                    }
                }

        return updates
    }

    fun processDescription(value: BlockchainOuterClass.DescribeResponse): Flux<UpstreamChange> {
        val current = value.chainsList.filter {
            Chain.byId(it.chain.number) != Chain.UNSPECIFIED
        }.map { chainDetails ->
            val chain = Chain.byId(chainDetails.chain.number)
            val up = getOrCreate(chain)
            (up.upstream as GrpcUpstream).init(chainDetails)
            up
        }

        val added = current.filter {
            it.type == UpstreamChange.ChangeType.ADDED
        }

        val removed = known.filterNot { kv ->
            val stillCurrent = current.any { c -> c.chain == kv.key }
            stillCurrent
        }.map {
            UpstreamChange(it.key, known.remove(it.key)!!, UpstreamChange.ChangeType.REMOVED)
        }
        return Flux.fromIterable(removed + added)
    }

    internal fun withTls(auth: AuthConfig.ClientTlsAuth): SslContext {
        val sslContext = SslContextBuilder.forClient()
                .clientAuth(ClientAuth.REQUIRE)
        sslContext.trustManager(fileResolver.resolve(auth.ca!!).inputStream())
        if (StringUtils.isNotEmpty(auth.key) && StringUtils.isNoneEmpty(auth.certificate)) {
            sslContext.keyManager(
                    fileResolver.resolve(auth.certificate!!).inputStream(),
                    fileResolver.resolve(auth.key!!).inputStream())
        } else {
            log.warn("Connect to remote using only CA certificate")
        }
        val alpn = ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                "grpc-exp", "h2")
        sslContext.applicationProtocolConfig(alpn)
        return sslContext.build()
    }

    fun getOrCreate(chain: Chain): UpstreamChange {
        lock.withLock {
            val current = known[chain]
            return if (current == null) {
                val created = GrpcUpstream(id, chain, client!!, objectMapper, grpcTransport!!.copyForChain(chain))
                created.timeout = this.timeout
                known[chain] = created
                created.start()
                UpstreamChange(chain, created, UpstreamChange.ChangeType.ADDED)
            } else {
                UpstreamChange(chain, current, UpstreamChange.ChangeType.REVALIDATED)
            }
        }
    }

    fun get(chain: Chain): GrpcUpstream {
        return known[chain]!!
    }
}