package com.codecademy.eventhub.list;

import com.codecademy.eventhub.base.ByteBufferUtil;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

/**
 * Currently, it can contain up to MAX_NUM_RECORDS records. ((2^31 - 1) - 4) / 8, i.e. largest
 * indexable address in (MappedByteBuffer - size of metadata) / size of a long typed id.
 * Since it's used in IndividualEventIndex and UserEventIndex, this implied that no single date
 * nor single user can have number of events exceeding this limit.
 */
public class DmaIdList implements IdList, Closeable {
  static final int META_DATA_SIZE = 4; // offset for numRecords
  static final int SIZE_OF_DATA = 8; // each data is a long number
  private static final int MAX_NUM_RECORDS = (Integer.MAX_VALUE - META_DATA_SIZE) / SIZE_OF_DATA;

  private final String filename;
  private MappedByteBuffer buffer;
  private int numRecords;
  private long capacity;

  public DmaIdList(String filename, MappedByteBuffer buffer, int numRecords, int capacity) {
    this.filename = filename;
    this.buffer = buffer;
    this.numRecords = numRecords;
    this.capacity = capacity;
  }

  @Override
  public void add(long id) {
    if (numRecords == MAX_NUM_RECORDS) {
      throw new IllegalStateException(
          String.format("DmaIdList reaches its maximum number of records: %d", numRecords));
    }
    if (numRecords == capacity) {
      buffer = ByteBufferUtil.expandBuffer(filename, buffer,
          META_DATA_SIZE + Math.min(MAX_NUM_RECORDS, 2 * capacity) * SIZE_OF_DATA);
      capacity *= 2;
    }
    buffer.putLong(id);
    buffer.putInt(0, ++numRecords);
  }

  @Override
  public int getStartOffset(long eventId) {
    ByteBuffer duplicate = buffer.duplicate();
    duplicate.position(META_DATA_SIZE);
    duplicate = duplicate.slice();
    return ByteBufferUtil.binarySearchOffset(duplicate, 0, numRecords, eventId, SIZE_OF_DATA);
  }

  @Override
  public Iterator subList(int startOffset, int maxRecords) {
    int endOffset = startOffset + maxRecords;
    endOffset = Math.min(endOffset < 0 ? Integer.MAX_VALUE : endOffset, numRecords);
    return new Iterator(buffer, startOffset, endOffset);
  }

  @Override
  public Iterator iterator() {
    return new Iterator(buffer, 0, numRecords);
  }

  @Override
  public void close() {
    buffer.force();
    buffer = null;
  }

  public interface Factory {
    DmaIdList build(String filename);
    void setDefaultCapacity(int defaultCapacity);
  }

  public static class Iterator implements IdList.Iterator {
    private final MappedByteBuffer buffer;
    private final long start;
    private final long end;
    private long offset;

    public Iterator(MappedByteBuffer buffer, long start, long end) {
      this.buffer = buffer;
      this.start = start;
      this.end = end;
      this.offset = 0;
    }

    @Override
    public boolean hasNext() {
      return start + offset < end;
    }

    @Override
    public long next() {
      long kthRecord = start + (offset++);
      return buffer.getLong(META_DATA_SIZE + (int) kthRecord * SIZE_OF_DATA);
    }
  }
}
