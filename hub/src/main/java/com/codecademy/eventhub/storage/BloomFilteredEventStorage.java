package com.codecademy.eventhub.storage;

import com.codecademy.eventhub.storage.filter.ExactMatch;
import com.codecademy.eventhub.base.BloomFilter;
import com.codecademy.eventhub.base.KeyValueCallback;
import com.codecademy.eventhub.list.DmaList;
import com.codecademy.eventhub.model.Event;
import com.codecademy.eventhub.storage.filter.Regex;
import com.codecademy.eventhub.storage.visitor.DelayedVisitorProxy;
import com.codecademy.eventhub.storage.visitor.Visitor;

import javax.inject.Provider;
import java.io.IOException;

public class BloomFilteredEventStorage extends DelegateEventStorage {
  private final DmaList<BloomFilter> bloomFilterDmaList;
  private final Provider<BloomFilter> bloomFilterProvider;
  private long numConditionCheck;
  private long numBloomFilterRejection;

  public BloomFilteredEventStorage(EventStorage eventStorage,
      DmaList<BloomFilter> bloomFilterDmaList, Provider<BloomFilter> bloomFilterProvider) {
    super(eventStorage);
    this.bloomFilterDmaList = bloomFilterDmaList;
    this.bloomFilterProvider = bloomFilterProvider;
    this.numConditionCheck = 0;
    this.numBloomFilterRejection = 0;
  }

  @Override
  public long addEvent(Event event, int userId, int eventTypeId) {
    final BloomFilter bloomFilter = bloomFilterProvider.get();
    event.enumerate(new KeyValueCallback() {
      @Override
      public void callback(String key, String value) {
        bloomFilter.add(getBloomFilterKey(key, value));
      }
    });
    bloomFilterDmaList.add(bloomFilter);
    return super.addEvent(event, userId, eventTypeId);
  }

  @Override
  public Visitor getFilterVisitor(final long eventId) {
    return new DelayedVisitorProxy(new Provider<Visitor>() {
      @Override
      public Visitor get() {
        final BloomFilter bloomFilter = bloomFilterDmaList.get(eventId);
        final Visitor visitorFromSuper = BloomFilteredEventStorage.super.getFilterVisitor(eventId);
        numConditionCheck++;
        return new BloomFilteredFilterVisitor(bloomFilter, visitorFromSuper);
      }
    });
  }

  @Override
  public String getVarz(int indentation) {
    String indent  = new String(new char[indentation]).replace('\0', ' ');
    return String.format(
        "%s\n\n" +
        indent + this.getClass().getName() + "\n" +
        indent + "==================\n" +
        indent + "num condition check: %d\n" +
        indent + "num bloomfilter rejection: %d",
        super.getVarz(indentation), numConditionCheck, numBloomFilterRejection);
  }

  @Override
  public void close() throws IOException {
    bloomFilterDmaList.close();
    super.close();
  }

  private static String getBloomFilterKey(String key, String value) {
    return key + value;
  }

  private class BloomFilteredFilterVisitor implements Visitor {
    private final BloomFilter bloomFilter;
    private final Visitor visitor;

    public BloomFilteredFilterVisitor(BloomFilter bloomFilter, Visitor visitor) {
      this.bloomFilter = bloomFilter;
      this.visitor = visitor;
    }

    @Override
    public boolean visit(ExactMatch exactMatch) {
      String bloomFilterKey = getBloomFilterKey(exactMatch.getKey(), exactMatch.getValue());
      if (!bloomFilter.isPresent(bloomFilterKey)) {
        numBloomFilterRejection++;
        return false;
      }
      return visitor.visit(exactMatch);
    }

    @Override
    public boolean visit(Regex regex) {
      return visitor.visit(regex);
    }
  }
}
