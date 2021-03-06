package io.emeraldpay.dshackle.upstream

import io.emeraldpay.dshackle.cache.BlockByHeight
import io.emeraldpay.dshackle.cache.BlocksMemCache
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.cache.HeightCache
import io.emeraldpay.dshackle.cache.TxMemCache
import io.emeraldpay.dshackle.reader.EmptyReader
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.upstream.ethereum.EthereumHead
import io.infinitape.etherjar.domain.BlockHash
import io.infinitape.etherjar.domain.TransactionId
import io.infinitape.etherjar.rpc.json.BlockJson
import io.infinitape.etherjar.rpc.json.TransactionRefJson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import spock.lang.Specification

import java.time.Duration

class CachingEthereumApiSpec extends Specification {

    def "Get blockNumber from head"() {
        setup:
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.default(),
                head
        )
        1 * head.getFlux() >> Flux.just(new BlockJson<TransactionRefJson>(number: 100))
        when:
        def act = api.execute(1, "eth_blockNumber", []).map { new String(it)}

        then:
        StepVerifier.create(act)
            .expectNext('{"jsonrpc":"2.0","id":1,"result":"0x64"}')
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    def "Return empty if block is not cached"() {
        setup:
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.default(),
                head
        )
        when:
        def act = api.execute(1, "eth_getBlockByHash", ["0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58", false]).map { new String(it)}

        then:
        StepVerifier.create(act)
                .expectComplete()
                .verify(Duration.ofSeconds(3))
    }

    def "Return block by hash when cached"() {
        setup:
        def cache = new BlocksMemCache();
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.newBuilder().setBlockByHash(cache).build(),
                head
        )
        cache.add(new BlockJson<TransactionRefJson>(number: 100, hash: BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58")))

        when:
        def act = api.execute(1, "eth_getBlockByHash", ["0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58", false]).map { new String(it)}

        then:
        StepVerifier.create(act)
                .expectNext('{"jsonrpc":"2.0","id":1,"result":{"number":"0x64","hash":"0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58","transactions":[],"uncles":[]}}')
                .expectComplete()
                .verify(Duration.ofSeconds(3))
    }

    def "Return block by height when cached"() {
        setup:
        def blocksCache = new BlocksMemCache()
        def heightCache = new HeightCache()
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.newBuilder().setBlockByHash(blocksCache).setBlockByHeight(heightCache).build(),
                head
        )
        def block = new BlockJson<TransactionRefJson>(number: 100, hash: BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58"))
        heightCache.add(block)
        blocksCache.add(block)

        when:
        def act = api.execute(1, "eth_getBlockByNumber", ["0x64", false]).map { new String(it)}

        then:
        StepVerifier.create(act)
                .expectNext('{"jsonrpc":"2.0","id":1,"result":{"number":"0x64","hash":"0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58","transactions":[],"uncles":[]}}')
                .expectComplete()
                .verify(Duration.ofSeconds(3))
    }

    def "Uses base cache when requested, by hash"() {
        setup:
        def blocksCache = Mock(BlocksMemCache)
        def txCache = Mock(TxMemCache)
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.newBuilder().setBlockByHash(blocksCache).setTxByHash(txCache).build(),
                head
        )
        def block = new BlockJson<TransactionRefJson>(number: 100, hash: BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58"))

        when:
        def act = api.readBlockByHash(1, "eth_getBlockByHash", ["0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58", false]).block()

        then:
        act != null
        1 * blocksCache.read(BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58")) >> Mono.just(block)
        0 * txCache.read(_)
    }

    def "Uses full cache when requested, by hash"() {
        setup:
        def blocksCache = Mock(BlocksMemCache)
        def txCache = Mock(TxMemCache)
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.newBuilder().setBlockByHash(blocksCache).setTxByHash(txCache).build(),
                head
        )
        def block = new BlockJson<TransactionRefJson>(number: 100, hash: BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58"))
        block.transactions = [
                new TransactionRefJson(TransactionId.from("0x0500219f2b147f3013e9030d585e8e5d45401ebd2620a42c879c0d5d1b754073"))
        ]

        when:
        def act = api.readBlockByHash(1, "eth_getBlockByHash", ["0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58", true]).block()

        then:
        act == null
        1 * blocksCache.read(BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58")) >> Mono.just(block)
        1 * txCache.read(TransactionId.from("0x0500219f2b147f3013e9030d585e8e5d45401ebd2620a42c879c0d5d1b754073")) >> Mono.empty()
    }

    def "Uses base cache when requested, by height"() {
        setup:
        def blocksCache = Mock(BlocksMemCache)
        def txCache = Mock(TxMemCache)
        def heightCache = Mock(HeightCache)
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.newBuilder().setBlockByHash(blocksCache).setTxByHash(txCache).setBlockByHeight(heightCache).build(),
                head
        )
        def block = new BlockJson<TransactionRefJson>(number: 100, hash: BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58"))

        when:
        def act = api.readBlockByNumber(1, "eth_getBlockByNumber", ["0x64", false]).block()

        then:
        act != null
        1 * heightCache.read(100) >> Mono.just(BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58"))
        1 * blocksCache.read(BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58")) >> Mono.just(block)
        0 * txCache.read(_)
    }

    def "Uses full cache when requested, by height"() {
        setup:
        def blocksCache = Mock(BlocksMemCache)
        def txCache = Mock(TxMemCache)
        def heightCache = Mock(HeightCache)
        def head = Mock(EthereumHead.class)
        def api = new CachingEthereumApi(
                TestingCommons.objectMapper(),
                Caches.newBuilder().setBlockByHash(blocksCache).setTxByHash(txCache).setBlockByHeight(heightCache).build(),
                head
        )
        def block = new BlockJson<TransactionRefJson>(number: 100, hash: BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58"))
        block.transactions = [
                new TransactionRefJson(TransactionId.from("0x0500219f2b147f3013e9030d585e8e5d45401ebd2620a42c879c0d5d1b754073"))
        ]

        when:
        def act = api.readBlockByNumber(1, "eth_getBlockByNumber", ["0x64", true]).block()

        then:
        act == null
        1 * heightCache.read(100) >> Mono.just(BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58"))
        1 * blocksCache.read(BlockHash.from("0x5b4590a9905fa1c9cc273f32e6dc63b4c512f0ee14edc6fa41c26b416a7b5d58")) >> Mono.just(block)
        1 * txCache.read(TransactionId.from("0x0500219f2b147f3013e9030d585e8e5d45401ebd2620a42c879c0d5d1b754073")) >> Mono.empty()
    }
}
