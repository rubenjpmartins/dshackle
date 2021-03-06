/**
 * Copyright (c) 2020 ETCDEV GmbH
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
package io.emeraldpay.dshackle.proxy

import com.google.protobuf.ByteString
import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.dshackle.test.TestingCommons
import reactor.core.publisher.Flux
import spock.lang.Specification

import java.time.Duration

class WriteRpcJsonSpec extends Specification {

    WriteRpcJson writer = new WriteRpcJson(TestingCommons.objectMapper())

    def "Write empty array"() {
        when:
        def act = Flux.empty().transform(writer.asArray())
                .collectList()
                .block(Duration.ofSeconds(1))
                .join("")
        then:
        act == "[]"
    }

    def "Write single item array"() {
        when:
        def act = Flux.just('{"id": 1}').transform(writer.asArray())
                .collectList()
                .block(Duration.ofSeconds(1))
                .join("")
        then:
        act == '[{"id": 1}]'
    }

    def "Write two item array"() {
        setup:
        def data = [
                '{"id": 1}',
                '{"id": 2}',
        ]
        when:
        def act = Flux.fromIterable(data).transform(writer.asArray())
                .collectList()
                .block(Duration.ofSeconds(1))
                .join("")
        then:
        act == '[{"id": 1},{"id": 2}]'
    }

    def "Write few items array"() {
        setup:
        def data = [
                '{"id": 1}',
                '{"id": 2, "foo": "bar"}',
                '{"id": 3, "foo": "baz"}',
                '{"id": 4}',
                '{"id": 5, "x": 5}',
        ]
        when:
        def act = Flux.fromIterable(data).transform(writer.asArray())
                .collectList()
                .block(Duration.ofSeconds(1))
                .join("")
        then:
        act == '[{"id": 1},{"id": 2, "foo": "bar"},{"id": 3, "foo": "baz"},{"id": 4},{"id": 5, "x": 5}]'
    }

    def "Convert basic to JSON"() {
        setup:
        def call = new ProxyCall(ProxyCall.RpcType.SINGLE)
        call.ids[1] = "aaa"
        def data = [
                BlockchainOuterClass.NativeCallReplyItem.newBuilder()
                        .setId(1)
                        .setSucceed(true)
                        .setPayload(ByteString.copyFrom('{"jsonrpc": "2.0", "id": 1, "result": "0x98dbb1"}', 'UTF-8'))
                        .build()
        ]
        when:
        def act = Flux.fromIterable(data)
                .transform(writer.toJsons(call))
                .collectList()
                .block(Duration.ofSeconds(1))
        then:
        act == ['{"jsonrpc":"2.0","id":"aaa","result":"0x98dbb1"}']
    }

    def "Convert error to JSON"() {
        setup:
        def call = new ProxyCall(ProxyCall.RpcType.SINGLE)
        call.ids[1] = 1
        def data = [
                BlockchainOuterClass.NativeCallReplyItem.newBuilder()
                        .setId(1)
                        .setSucceed(true)
                        .setPayload(ByteString.copyFrom('{"jsonrpc": "2.0", "id": 1, "error": {"code": -32001, "message": "oops"}}', 'UTF-8'))
                        .build()
        ]
        when:
        def act = Flux.fromIterable(data)
                .transform(writer.toJsons(call))
                .collectList()
                .block(Duration.ofSeconds(1))
        then:
        act == ['{"jsonrpc":"2.0","id":1,"error":{"code":-32001,"message":"oops"}}']
    }

    def "Convert gRPC error to JSON"() {
        setup:
        def call = new ProxyCall(ProxyCall.RpcType.SINGLE)
        call.ids[1] = 1
        def data = [
                BlockchainOuterClass.NativeCallReplyItem.newBuilder()
                        .setId(1)
                        .setSucceed(false)
                        .setErrorMessage("Internal Error")
                        .build()
        ]
        when:
        def act = Flux.fromIterable(data)
                .transform(writer.toJsons(call))
                .collectList()
                .block(Duration.ofSeconds(1))
        then:
        act == ['{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Internal Error"}}']
    }

    def "Convert few items to JSON"() {
        setup:
        def call = new ProxyCall(ProxyCall.RpcType.SINGLE)
        call.ids[1] = 10
        call.ids[2] = 11
        call.ids[3] = 15
        def data = [
                BlockchainOuterClass.NativeCallReplyItem.newBuilder()
                        .setId(1)
                        .setSucceed(true)
                        .setPayload(ByteString.copyFrom('{"jsonrpc": "2.0", "id": 1, "result": "0x98dbb1"}', 'UTF-8'))
                        .build(),
                BlockchainOuterClass.NativeCallReplyItem.newBuilder()
                        .setId(2)
                        .setSucceed(true)
                        .setPayload(ByteString.copyFrom('{"jsonrpc": "2.0", "id": 2, "error": {"code": -32001, "message": "oops"}}', 'UTF-8'))
                        .build(),
                BlockchainOuterClass.NativeCallReplyItem.newBuilder()
                        .setId(3)
                        .setSucceed(true)
                        .setPayload(ByteString.copyFrom('{"jsonrpc": "2.0", "id": 3, "result": {"hash": "0x2484f459dc"}}', 'UTF-8'))
                        .build(),
        ]
        when:
        def act = Flux.fromIterable(data)
                .transform(writer.toJsons(call))
                .collectList()
                .block(Duration.ofSeconds(1))
        then:
        act == [
                '{"jsonrpc":"2.0","id":10,"result":"0x98dbb1"}',
                '{"jsonrpc":"2.0","id":11,"error":{"code":-32001,"message":"oops"}}',
                '{"jsonrpc":"2.0","id":15,"result":{"hash":"0x2484f459dc"}}'
        ]
    }
}
