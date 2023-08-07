/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.queue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.rocketmq.common.Pair;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.DispatchRequest;
import org.apache.rocketmq.store.queue.RocksDBConsumeQueueOffsetTable.PhyAndCQOffset;
import org.apache.rocketmq.store.rocksdb.ConsumeQueueRocksDBStorage;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import static org.apache.rocketmq.store.queue.RocksDBConsumeQueueStore.CHARSET_UTF8;
import static org.apache.rocketmq.store.queue.RocksDBConsumeQueueStore.CTRL_0;
import static org.apache.rocketmq.store.queue.RocksDBConsumeQueueStore.CTRL_2;
import static org.apache.rocketmq.store.queue.RocksDBConsumeQueueStore.CTRL_1;

/**
 * We use RocksDBConsumeQueueTable to store cqUnit.
 */
public class RocksDBConsumeQueueTable {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static final Logger ROCKSDB_LOG = LoggerFactory.getLogger(LoggerName.ROCKSDB_LOGGER_NAME);

    /**
     * Rocksdb ConsumeQueue's store unit. Format:
     *
     * <pre>
     * ┌─────────────────────────┬───────────┬───────────────────────┬───────────┬───────────┬───────────┬───────────────────────┐
     * │ Topic Bytes Array Size  │  CTRL_1   │   Topic Bytes Array   │  CTRL_1   │  QueueId  │  CTRL_1   │  ConsumeQueue Offset  │
     * │        (4 Bytes)        │ (1 Bytes) │       (n Bytes)       │ (1 Bytes) │ (4 Bytes) │ (1 Bytes) │     (8 Bytes)         │
     * ├─────────────────────────┴───────────┴───────────────────────┴───────────┴───────────┴───────────┴───────────────────────┤
     * │                                                    Key Unit                                                             │
     * │                                                                                                                         │
     * </pre>
     *
     * <pre>
     * ┌─────────────────────────────┬───────────────────┬──────────────────┬──────────────────┬───────────────────────┐
     * │  CommitLog Physical Offset  │      Body Size    │   Tag HashCode   │  Msg Store Time  │  ConsumeQueue Offset  │
     * │        (8 Bytes)            │      (4 Bytes)    │    (8 Bytes)     │    (8 Bytes)     │      (8 Bytes)        │
     * ├─────────────────────────────┴───────────────────┴──────────────────┴──────────────────┴───────────────────────┤
     * │                                                    Value Unit                                                 │
     * │                                                                                                               │
     * </pre>
     * ConsumeQueue's store unit. Size:
     * CommitLog Physical Offset(8) + Body Size(4) + Tag HashCode(8) + Msg Store Time(8) + ConsumeQueue Offset(8) =  36 Bytes
     */
    public static final int PHY_OFFSET_OFFSET = 0;
    public static final int PHY_MSG_LEN_OFFSET = 8;
    public static final int MSG_TAG_HASHCODE_OFFSET = 12;
    public static final int MSG_STORE_TIME_SIZE_OFFSET = 20;
    public static final int CQ_OFFSET_OFFSET = 28;
    public static final int CQ_UNIT_SIZE = 36;

    private final ConsumeQueueRocksDBStorage rocksDBStorage;
    private final DefaultMessageStore messageStore;

    private ColumnFamilyHandle defaultCFH;

    public RocksDBConsumeQueueTable(ConsumeQueueRocksDBStorage rocksDBStorage, DefaultMessageStore messageStore) {
        this.rocksDBStorage = rocksDBStorage;
        this.messageStore = messageStore;
    }

    public void load() {
        this.defaultCFH = this.rocksDBStorage.getDefaultCFHandle();
    }

    public void buildAndPutCQByteBuffer(final Pair<ByteBuffer, ByteBuffer> cqBBPair,
        final byte[] topicBytes, final DispatchRequest request, final WriteBatch writeBatch) throws RocksDBException {
        final ByteBuffer cqKey = cqBBPair.getObject1();
        buildCQKeyBB(cqKey, topicBytes, request.getQueueId(), request.getConsumeQueueOffset());

        final ByteBuffer cqValue = cqBBPair.getObject2();
        buildCQValueBB(cqValue, request.getCommitLogOffset(), request.getMsgSize(), request.getTagsCode(),
            request.getStoreTimestamp(), request.getConsumeQueueOffset());

        writeBatch.put(defaultCFH, cqKey, cqValue);
    }

    public ByteBuffer getCQInKV(final String topic, final int queueId, final long cqOffset) throws RocksDBException {
        final byte[] topicBytes = topic.getBytes(CHARSET_UTF8);
        final ByteBuffer keyBB = buildCQKeyBB(topicBytes, queueId, cqOffset);
        byte[] value = this.rocksDBStorage.getCQ(keyBB.array());
        return (value != null) ? ByteBuffer.wrap(value) : null;
    }

    public List<ByteBuffer> rangeQuery(final String topic, final int queueId, final long startIndex, final int num) throws RocksDBException {
        final byte[] topicBytes = topic.getBytes(CHARSET_UTF8);
        final List<ColumnFamilyHandle> defaultCFHList = new ArrayList(num);
        final ByteBuffer[] resultList = new ByteBuffer[num];
        final List<Integer> kvIndexList = new ArrayList(num);
        final List<byte[]> kvKeyList = new ArrayList(num);
        for (int i = 0; i < num; i++) {
            final ByteBuffer keyBB = buildCQKeyBB(topicBytes, queueId, startIndex + i);
            kvIndexList.add(i);
            kvKeyList.add(keyBB.array());
            defaultCFHList.add(defaultCFH);
        }
        int keyNum = kvIndexList.size();
        if (keyNum > 0) {
            List<byte[]> kvValueList = this.rocksDBStorage.multiGet(defaultCFHList, kvKeyList);
            final int valueNum = kvValueList.size();
            if (keyNum != valueNum) {
                throw new RocksDBException("rocksdb bug, multiGet");
            }
            for (int i = 0; i < valueNum; i++) {
                byte[] value = kvValueList.get(i);
                if (value == null) {
                    continue;
                }
                ByteBuffer byteBuffer = ByteBuffer.wrap(value);
                resultList[kvIndexList.get(i)] = byteBuffer;
            }
        }

        final int resultSize = resultList.length;
        List<ByteBuffer> bbValueList = new ArrayList(resultSize);
        long preQueueOffset = 0;
        for (int i = 0; i < resultSize; i++) {
            ByteBuffer byteBuffer = resultList[i];
            if (byteBuffer == null) {
                break;
            }
            long queueOffset = byteBuffer.getLong(CQ_OFFSET_OFFSET);
            if (i > 0 && queueOffset != preQueueOffset + 1) {
                throw new RocksDBException("rocksdb bug, data damaged");
            }
            preQueueOffset = queueOffset;
            bbValueList.add(byteBuffer);
        }
        return bbValueList;
    }

    /**
     * When topic is deleted, we clean up its CqUnit in rocksdb.
     * @param topic
     * @param queueId
     * @throws RocksDBException
     */
    public void destroyCQ(final String topic, final int queueId, WriteBatch writeBatch) throws RocksDBException {
        final byte[] topicBytes = topic.getBytes(CHARSET_UTF8);
        final ByteBuffer cqStartKey = buildDeleteCQKey(true, topicBytes, queueId);
        final ByteBuffer cqEndKey = buildDeleteCQKey(false, topicBytes, queueId);

        writeBatch.deleteRange(defaultCFH, cqStartKey.array(), cqEndKey.array());

        log.info("Rocksdb consumeQueue table delete topic. {}, {}", topic, queueId);
    }

    public PhyAndCQOffset binarySearchInCQ(String topic, int queueId, long high, long low,
        long targetPhyOffset, boolean min) throws RocksDBException {
        long resultCQOffset = -1L;
        long resultPhyOffset = -1L;
        while (high >= low) {
            long midOffset = low + ((high - low) >>> 1);
            ByteBuffer byteBuffer = getCQInKV(topic, queueId, midOffset);
            if (this.messageStore.getMessageStoreConfig().isEnableRocksDBLog()) {
                ROCKSDB_LOG.warn("binarySearchInCQ. {}, {}, {}, {}, {}", topic, queueId, midOffset, low, high);
            }
            if (byteBuffer == null) {
                low = midOffset + 1;
                continue;
            }

            final long phyOffset = byteBuffer.getLong(PHY_OFFSET_OFFSET);
            if (phyOffset == targetPhyOffset) {
                if (min) {
                    resultCQOffset =  midOffset;
                    resultPhyOffset = phyOffset;
                }
                break;
            } else if (phyOffset > targetPhyOffset) {
                high = midOffset - 1;
                if (min) {
                    resultCQOffset = midOffset;
                    resultPhyOffset = phyOffset;
                }
            } else {
                low = midOffset + 1;
                if (!min) {
                    resultCQOffset = midOffset;
                    resultPhyOffset = phyOffset;
                }
            }
        }
        return new PhyAndCQOffset(resultPhyOffset, resultCQOffset);
    }


    private ByteBuffer buildCQKeyBB(final byte[] topicBytes, final int queueId, final long cqOffset) {
        final ByteBuffer bb = ByteBuffer.allocate(19 + topicBytes.length);
        buildCQKeyBB0(bb, topicBytes, queueId, cqOffset);
        return bb;
    }

    private void buildCQKeyBB(final ByteBuffer bb, final byte[] topicBytes,
        final int queueId, final long cqOffset) {
        bb.position(0).limit(19 + topicBytes.length);
        buildCQKeyBB0(bb, topicBytes, queueId, cqOffset);
    }

    private void buildCQKeyBB0(final ByteBuffer bb, final byte[] topicBytes,
        final int queueId, final long cqOffset) {
        bb.putInt(topicBytes.length).put(CTRL_1).put(topicBytes).put(CTRL_1).putInt(queueId).put(CTRL_1).putLong(cqOffset);
        bb.flip();
    }

    private void buildCQValueBB(final ByteBuffer bb, final long phyOffset, final int msgSize,
        final long tagsCode, final long storeTimestamp, final long cqOffset) {
        bb.position(0).limit(CQ_UNIT_SIZE);
        buildCQValueBB0(bb, phyOffset, msgSize, tagsCode, storeTimestamp, cqOffset);
    }

    private void buildCQValueBB0(final ByteBuffer bb, final long phyOffset, final int msgSize,
        final long tagsCode, final long storeTimestamp, final long cqOffset) {
        bb.putLong(phyOffset).putInt(msgSize).putLong(tagsCode).putLong(storeTimestamp).putLong(cqOffset);
        bb.flip();
    }

    private ByteBuffer buildDeleteCQKey(final boolean start, final byte[] topicBytes, final int queueId) {
        final ByteBuffer bb = ByteBuffer.allocate(11 + topicBytes.length);

        bb.putInt(topicBytes.length).put(CTRL_1).put(topicBytes).put(CTRL_1).putInt(queueId).put(start ? CTRL_0 : CTRL_2);
        bb.flip();
        return bb;
    }
}
