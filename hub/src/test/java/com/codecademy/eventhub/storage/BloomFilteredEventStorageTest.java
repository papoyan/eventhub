package com.codecademy.eventhub.storage;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.inject.Injector;
import com.codecademy.eventhub.storage.filter.And;
import com.codecademy.eventhub.storage.filter.ExactMatch;
import com.codecademy.eventhub.storage.filter.Filter;
import com.codecademy.eventhub.integration.GuiceTestCase;
import com.codecademy.eventhub.model.Event;
import org.junit.Assert;
import org.junit.Test;

import javax.inject.Provider;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class BloomFilteredEventStorageTest extends GuiceTestCase {
  @Test
  public void testAll() throws Exception {
    Provider<BloomFilteredEventStorage> bloomFilteredEventStorageProvider = getBloomFilteredEventStorageProvider();
    BloomFilteredEventStorage eventStorage = bloomFilteredEventStorageProvider.get();
    String[] eventTypes = new String[] { "a", "b", "c" };
    String[] externalUserIds = new String[] { "x", "y", "z" };
    String[] dates = new String[] { "20130101", "20130102", "20131111" };
    @SuppressWarnings("unchecked")
    Map<String, String>[] properties = (Map<String, String>[]) new Map[] {
        ImmutableMap.<String, String>builder().put("foo1", "bar1").put("foo2", "bar2").build(),
        ImmutableMap.<String, String>builder().put("foo2", "bar2").put("foo3", "bar3").build(),
        ImmutableMap.<String, String>builder().put("foo3", "bar3").build()
    };
    int[] userIds = new int[] { 1, 2, 3 };
    int[] eventTypeIds = new int[] { 4, 5, 6 };

    for (int i = 0; i < eventTypes.length - 1; i++) {
      eventStorage.addEvent(new Event.Builder(
          eventTypes[i], externalUserIds[i], dates[i], properties[i]).build(),
          userIds[i], eventTypeIds[i]);
    }

    List<Filter> matchedFilters = Lists.newArrayList(
        And.of(new ExactMatch("foo1", "bar1"), new ExactMatch("foo2", "bar2")),
        new ExactMatch("foo2", "bar2"),
        new ExactMatch("foo3", "bar3"));
    List<Filter> unmatchedFilters = Lists.newArrayList(
        And.of(new ExactMatch("foo1", "bar1"), new ExactMatch("foo2", "bar2"), new ExactMatch("foo3", "bar3")),
        new ExactMatch("foo2", "bar1"),
        new ExactMatch("foo1", "bar1"));
    for (int i = 0; i < eventTypes.length - 1; i++) {
      Assert.assertTrue(matchedFilters.get(i).accept(eventStorage.getFilterVisitor(i)));
      Assert.assertFalse(unmatchedFilters.get(i).accept(eventStorage.getFilterVisitor(i)));
      Assert.assertEquals(eventTypes[i], eventStorage.getEvent(i).getEventType());
      Assert.assertEquals(externalUserIds[i], eventStorage.getEvent(i).getExternalUserId());
      Assert.assertEquals(dates[i], eventStorage.getEvent(i).getDate());
      for (Map.Entry<String, String> entry : properties[i].entrySet()) {
        Assert.assertEquals(entry.getValue(), eventStorage.getEvent(i).get(entry.getKey()));
      }
      Assert.assertEquals(userIds[i], eventStorage.getUserId(i));
      Assert.assertEquals(eventTypeIds[i], eventStorage.getEventTypeId(i));
    }
    eventStorage.close();

    eventStorage = bloomFilteredEventStorageProvider.get();
    eventStorage.addEvent(new Event.Builder(
        eventTypes[eventTypes.length - 1], externalUserIds[eventTypes.length - 1],
        dates[eventTypes.length - 1], properties[eventTypes.length - 1]).build(),
        userIds[eventTypes.length - 1], eventTypeIds[eventTypes.length - 1]);
    for (int i = 0; i < eventTypes.length; i++) {
      Assert.assertTrue(matchedFilters.get(i).accept(eventStorage.getFilterVisitor(i)));
      Assert.assertFalse(unmatchedFilters.get(i).accept(eventStorage.getFilterVisitor(i)));
      Assert.assertEquals(eventTypes[i], eventStorage.getEvent(i).getEventType());
      Assert.assertEquals(externalUserIds[i], eventStorage.getEvent(i).getExternalUserId());
      Assert.assertEquals(dates[i], eventStorage.getEvent(i).getDate());
      Assert.assertEquals(userIds[i], eventStorage.getUserId(i));
      Assert.assertEquals(eventTypeIds[i], eventStorage.getEventTypeId(i));
    }
  }

  private Provider<BloomFilteredEventStorage> getBloomFilteredEventStorageProvider() {
    Properties prop = new Properties();
    prop.put("eventhub.directory", getTempDirectory());
    prop.put("eventhub.journaleventstorage.numMetaDataPerFile", "1");
    prop.put("eventhub.journaleventstorage.metaDataFileCacheSize", "1");
    prop.put("eventhub.journaleventstorage.journalFileSize", "1024");
    prop.put("eventhub.journaleventstorage.journalWriteBatchSize", "1024");
    prop.put("eventhub.cachedeventstorage.recordCacheSize", "1");
    prop.put("eventhub.bloomfilteredeventstorage.bloomFilterSize", "64");
    prop.put("eventhub.bloomfilteredeventstorage.numHashes", "1");
    prop.put("eventhub.bloomfilteredeventstorage.numMetaDataPerFile", "1");
    prop.put("eventhub.bloomfilteredeventstorage.metaDataFileCacheSize", "1");

    Injector injector = createInjectorFor(
        prop, new EventStorageModule());
    return injector.getProvider(BloomFilteredEventStorage.class);
  }
}
