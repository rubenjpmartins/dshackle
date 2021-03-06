package io.emeraldpay.dshackle.cache

import io.infinitape.etherjar.domain.BlockHash
import io.infinitape.etherjar.domain.TransactionId
import io.infinitape.etherjar.rpc.json.BlockJson
import io.infinitape.etherjar.rpc.json.TransactionJson
import io.infinitape.etherjar.rpc.json.TransactionRefJson
import spock.lang.Specification

class BlocksWithTxCacheSpec extends Specification {

    // sorted
    String hash1 = "0x40d15edaff9acdabd2a1c96fd5f683b3300aad34e7015f34def3c56ba8a7ffb5"
    String hash2 = "0x4aabdaff9acd2f30d15e00ab5dfd5f6c56ba4ea1c968a7ff8d3f34de70153b33"
    String hash3 = "0xa4e7a75dfd5f6a83b3304dc56bfa0abfd3fef01540d15edafc9683f9acd2a13b"
    String hash4 = "0xd3f34def3c56ba4e701540d15edaff9acd2a1c968a7ff83b3300ab5dfd5f6aab"

    def tx1 = new TransactionJson().with {
        it.blockNumber = 100
        it.blockHash = BlockHash.from(hash1)
        it.hash = TransactionId.from(hash1)
        it.nonce = 1
        it
    }
    def tx2 = new TransactionJson().with {
        it.blockNumber = 100
        it.blockHash = BlockHash.from(hash1)
        it.hash = TransactionId.from(hash2)
        it.nonce = 2
        it
    }
    def tx3 = new TransactionJson().with {
        it.blockNumber = 101
        it.blockHash = BlockHash.from(hash3)
        it.hash = TransactionId.from(hash3)
        it.nonce = 3
        it
    }

    // no block
    def tx4 = new TransactionJson().with {
        it.blockNumber = 102
        it.blockHash = BlockHash.from(hash4)
        it.hash = TransactionId.from(hash4)
        it.nonce = 4
        it
    }

    // two transactiosn, tx1 and tx2
    def block1 = new BlockJson().with {
        it.number = 100
        it.hash = BlockHash.from(hash1)
        it.transactions = [
                new TransactionRefJson(tx1.hash),
                new TransactionRefJson(tx2.hash)
        ]
        it
    }

    // one transaction, tx3
    def block2 = new BlockJson().with {
        it.number = 101
        it.hash = BlockHash.from(hash3)
        it.transactions = [
                new TransactionRefJson(tx3.hash)
        ]
        it
    }

    // no transactions
    def block3 = new BlockJson().with {
        it.number = 102
        it.hash = BlockHash.from(hash4)
        it.transactions = []
        it
    }


    def "Read transactions and return full block"() {
        setup:
        def txes = new TxMemCache()
        def blocks = new BlocksMemCache()

        txes.add(tx1)
        txes.add(tx2)
        txes.add(tx3)
        txes.add(tx4)
        blocks.add(block1)
        blocks.add(block2)
        blocks.add(block3)

        def full = new BlocksWithTxCache(blocks, txes)

        when:
        def act = full.read(block1.hash).block()

        then:
        act != null
        act.hash == BlockHash.from(hash1)
        act.number == 100
        act.transactions.size() == 2

        when:
        def transactions = act.transactions.sort { it.hash }
        then:
        transactions[0] instanceof TransactionJson
        transactions[1] instanceof TransactionJson
        //verify it didn't update original block
        block1.transactions[0] instanceof TransactionRefJson
        block1.transactions[1] instanceof TransactionRefJson
        with(transactions[0]) {
            hash == TransactionId.from(hash1)
            nonce == 1
        }
        with(transactions[1]) {
            hash == TransactionId.from(hash2)
            nonce == 2
        }

        // request second block
        when:
        act = full.read(block2.hash).block()
        then:
        act != null
        act.hash == BlockHash.from(hash3)
        act.number == 101
        act.transactions.size() == 1
        with(act.transactions[0]) {
            hash == TransactionId.from(hash3)
            nonce == 3
        }
    }

    def "Read block without transactions"() {
        setup:
        def txes = new TxMemCache()
        def blocks = new BlocksMemCache()

        txes.add(tx1)
        txes.add(tx2)
        txes.add(tx3)
        txes.add(tx4)
        blocks.add(block1)
        blocks.add(block2)
        blocks.add(block3)

        def full = new BlocksWithTxCache(blocks, txes)

        when:
        def act = full.read(block3.hash).block()

        then:
        act != null
        act.hash == BlockHash.from(hash4)
        act.number == 102
        act.transactions.size() == 0
    }

    def "Return nothing if no transactions"() {
        setup:
        def txes = new TxMemCache()
        def blocks = new BlocksMemCache()

        txes.add(tx1)
        blocks.add(block1) //missing tx2 in cache

        def full = new BlocksWithTxCache(blocks, txes)

        when:
        def act = full.read(block1.hash).block()

        then:
        act == null
    }

    def "Return nothing if no block"() {
        setup:
        def txes = new TxMemCache()
        def blocks = new BlocksMemCache()

        txes.add(tx1)
        txes.add(tx2)
        txes.add(tx3)

        def full = new BlocksWithTxCache(blocks, txes)

        when:
        def act = full.read(block1.hash).block()

        then:
        act == null
    }
}
