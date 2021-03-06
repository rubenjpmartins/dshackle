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
package io.emeraldpay.dshackle.upstream.calls

import com.fasterxml.jackson.databind.ObjectMapper
import io.emeraldpay.dshackle.quorum.*
import io.emeraldpay.grpc.Chain
import io.infinitape.etherjar.rpc.JacksonRpcConverter
import io.infinitape.etherjar.rpc.RpcException
import java.util.*

/**
 * Default configuration for Ethereum based RPC. Defines optimal Quorum strategies for different methods, and provides
 * hardcoded results for base methods, such as `net_version`, `web3_clientVersion` and similar
 */
class QuorumBasedMethods(
        private val objectMapper: ObjectMapper,
        private val chain: Chain
) : CallMethods {

    private val jacksonRpcConverter = JacksonRpcConverter(objectMapper)

    private val anyResponseMethods = listOf(
            "eth_gasPrice",
            "eth_call",
            "eth_estimateGas"
    )

    private val firstValueMethods = listOf(
            "eth_getBlockTransactionCountByHash",
            "eth_getUncleCountByBlockHash",
            "eth_getBlockByHash",
            "eth_getTransactionByHash",
            "eth_getTransactionByBlockHashAndIndex",
            "eth_getStorageAt",
            "eth_getCode",
            "eth_getUncleByBlockHashAndIndex"
    )

    private val specialMethods = listOf(
            "eth_getTransactionCount",
            "eth_blockNumber",
            "eth_getBalance",
            "eth_sendRawTransaction"
    )

    private val headVerifiedMethods = listOf(
            "eth_getBlockTransactionCountByNumber",
            "eth_getUncleCountByBlockNumber",
            "eth_getBlockByNumber",
            "eth_getTransactionByBlockNumberAndIndex",
            "eth_getTransactionReceipt",
            "eth_getUncleByBlockNumberAndIndex"
    )

    private val allowedMethods = anyResponseMethods + firstValueMethods + specialMethods + headVerifiedMethods

    private val hardcodedMethods = listOf(
            "net_version",
            "net_peerCount",
            "net_listening",
            "web3_clientVersion",
            "eth_protocolVersion",
            "eth_syncing",
            "eth_coinbase",
            "eth_mining",
            "eth_hashrate",
            "eth_accounts"
    )

    override fun getQuorumFor(method: String): CallQuorum {
        return when {
            hardcodedMethods.contains(method) -> AlwaysQuorum()
            firstValueMethods.contains(method) -> AlwaysQuorum()
            anyResponseMethods.contains(method) -> NotLaggingQuorum(6)
            headVerifiedMethods.contains(method) -> NotLaggingQuorum(1)
            specialMethods.contains(method) -> {
                when (method) {
                    "eth_getTransactionCount" -> NonceQuorum(jacksonRpcConverter)
                    "eth_getBalance" -> NotLaggingQuorum(1)
                    "eth_sendRawTransaction" -> BroadcastQuorum(jacksonRpcConverter)
                    else -> AlwaysQuorum()
                }
            }
            else -> AlwaysQuorum()
        }
    }

    override fun isAllowed(method: String): Boolean {
        return allowedMethods.contains(method)
    }
    override fun isHardcoded(method: String): Boolean {
        return hardcodedMethods.contains(method)
    }

    override fun executeHardcoded(method: String): Any {
        if ("net_version" == method) {
            if (Chain.ETHEREUM == chain) {
                return "1"
            }
            if (Chain.ETHEREUM_CLASSIC == chain) {
                return "1"
            }
            if (Chain.TESTNET_MORDEN == chain) {
                return "2"
            }
            if (Chain.TESTNET_KOVAN == chain) {
                return "42"
            }
            throw RpcException(-32602, "Invalid chain")
        }
        if ("net_peerCount" == method) {
            return "0x2a"
        }
        if ("net_listening" == method) {
            return true
        }
        if ("web3_clientVersion" == method) {
            return "EmeraldDshackle/v0.2"
        }
        if ("eth_protocolVersion" == method) {
            return "0x3f"
        }
        if ("eth_syncing" == method) {
            return false
        }
        if ("eth_coinbase" == method) {
            return "0x0000000000000000000000000000000000000000"
        }
        if ("eth_mining" == method) {
            return "false"
        }
        if ("eth_hashrate" == method) {
            return "0x0"
        }
        if ("eth_accounts" == method) {
            return Collections.emptyList<String>()
        }
        throw RpcException(-32601, "Method not found")
    }

    override fun getSupportedMethods(): Set<String> {
        return allowedMethods.plus(hardcodedMethods).toSortedSet()
    }
}