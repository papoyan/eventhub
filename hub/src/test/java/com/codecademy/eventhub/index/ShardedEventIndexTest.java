package com.codecademy.eventhub.index;

import com.google.inject.Injector;
import com.codecademy.eventhub.integration.GuiceTestCase;
import com.codecademy.eventhub.list.DmaIdListModule;
import org.junit.Assert;
import org.junit.Test;

import javax.inject.Provider;
import java.util.Properties;

public class ShardedEventIndexTest extends GuiceTestCase {
  @Test
  public void testEnsureEventType() throws Exception {
    Provider<ShardedEventIndex> shardedEventIndexProvider = getShardedEventIndexProvider();

    ShardedEventIndex shardedEventIndex = shardedEventIndexProvider.get();
    Assert.assertEquals(0, shardedEventIndex.ensureEventType("foo"));
    Assert.assertEquals(1, shardedEventIndex.ensureEventType("bar"));
    Assert.assertEquals(1, shardedEventIndex.ensureEventType("bar"));
    Assert.assertEquals(0, shardedEventIndex.ensureEventType("foo"));
    Assert.assertEquals(2, shardedEventIndex.ensureEventType("foo2"));
  }

  @Test
  public void testAll() throws Exception {
    Provider<ShardedEventIndex> shardedEventIndexProvider = getShardedEventIndexProvider();

    ShardedEventIndex shardedEventIndex = shardedEventIndexProvider.get();
    String[] eventTypes = new String[] { "a", "b" };
    String[] dates = new String[] { "20130101", "20130102", "20131111", "20131201" };

    for (String eventType : eventTypes) {
      shardedEventIndex.ensureEventType(eventType);
    }
    shardedEventIndex.addEvent(1, eventTypes[0], dates[0]);
    shardedEventIndex.addEvent(2, eventTypes[1], dates[0]);
    shardedEventIndex.addEvent(3, eventTypes[0], dates[1]);
    shardedEventIndex.addEvent(4, eventTypes[0], dates[1]);
    shardedEventIndex.addEvent(5, eventTypes[1], dates[1]);
    shardedEventIndex.addEvent(15, eventTypes[1], dates[1]);
    shardedEventIndex.addEvent(16, eventTypes[0], dates[2]);
    shardedEventIndex.addEvent(17, eventTypes[1], dates[2]);
    shardedEventIndex.addEvent(18, eventTypes[0], dates[3]);
    shardedEventIndex.addEvent(19, eventTypes[1], dates[3]);

    for (int i = 0; i < eventTypes.length; i++) {
      Assert.assertEquals(i, shardedEventIndex.getEventTypeId(eventTypes[i]));
    }

    IdVerificationCallback callback = new IdVerificationCallback(new int[] { 3, 4, 16 });
    shardedEventIndex.enumerateEventIds(eventTypes[0], dates[1], dates[3], callback);
    callback.verify();

    shardedEventIndex.close();
    shardedEventIndex = shardedEventIndexProvider.get();

    for (int i = 0; i < eventTypes.length; i++) {
      Assert.assertEquals(i, shardedEventIndex.getEventTypeId(eventTypes[i]));
    }

    callback = new IdVerificationCallback(new int[] { 3, 4, 16 });
    shardedEventIndex.enumerateEventIds(eventTypes[0], dates[1], dates[3], callback);
    callback.verify();
  }

  private Provider<ShardedEventIndex> getShardedEventIndexProvider() {
    Properties prop = new Properties();
    prop.put("eventhub.directory", getTempDirectory());
    prop.put("eventhub.eventindex.initialNumEventIdsPerDay", "1");

    Injector injector = createInjectorFor(
        prop, new DmaIdListModule(), new ShardedEventIndexModule());
    return injector.getProvider(ShardedEventIndex.class);
  }

  private static class IdVerificationCallback implements EventIndex.Callback {
    private final int[] expectedIds;
    private int counter;

    public IdVerificationCallback(int[] expectedIds) {
      this.expectedIds = expectedIds;
      this.counter = 0;
    }

    @Override
    public void onEventId(long eventId) {
      Assert.assertEquals(expectedIds[counter++], eventId);
    }

    public void verify() {
      Assert.assertEquals(expectedIds.length, counter);
    }
  }
}
