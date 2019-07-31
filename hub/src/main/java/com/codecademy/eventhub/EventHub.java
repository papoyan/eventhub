package com.codecademy.eventhub;

import com.google.common.collect.ArrayTable;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.codecademy.eventhub.index.DatedEventIndex;
import com.codecademy.eventhub.index.EventIndex;
import com.codecademy.eventhub.index.PropertiesIndex;
import com.codecademy.eventhub.index.ShardedEventIndex;
import com.codecademy.eventhub.index.UserEventIndex;
import com.codecademy.eventhub.list.DummyIdList;
import com.codecademy.eventhub.list.IdList;
import com.codecademy.eventhub.list.MemIdList;
import com.codecademy.eventhub.model.Event;
import com.codecademy.eventhub.model.User;
import com.codecademy.eventhub.storage.EventStorage;
import com.codecademy.eventhub.storage.UserStorage;
import com.codecademy.eventhub.storage.filter.Filter;
import com.codecademy.eventhub.storage.filter.TrueFilter;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

// TODO: query language
// TODO: integrate com.codahale.metrics
// TODO: refactor to have IdManager & Id class
// TODO: consider column oriented storage
// TODO: separate cache for previously computed result? same binary or redis?
// TODO: move synchronization responsibility to low level
// TODO: compression of DmaIdList
// TODO: native byte order for performance
public class EventHub implements Closeable {
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormat.forPattern("yyyyMMdd");

  private final String directory;
  private final ShardedEventIndex shardedEventIndex;
  private final DatedEventIndex datedEventIndex;
  private final PropertiesIndex propertiesIndex;
  private final UserEventIndex userEventIndex;
  private final EventStorage eventStorage;
  private final UserStorage userStorage;

  public EventHub(String directory, ShardedEventIndex shardedEventIndex,
      DatedEventIndex datedEventIndex, PropertiesIndex propertiesIndex,
      UserEventIndex userEventIndex, EventStorage eventStorage, UserStorage userStorage) {
    this.directory = directory;
    this.shardedEventIndex = shardedEventIndex;
    this.datedEventIndex = datedEventIndex;
    this.propertiesIndex = propertiesIndex;
    this.userEventIndex = userEventIndex;
    this.eventStorage = eventStorage;
    this.userStorage = userStorage;
  }

  public int[][] getRetentionTable(String startDateString,
      String endDateString, int numDaysPerCohort, int numColumns, String rowEventType,
      String columnEventType, Filter rowEventFilter, Filter columnEventFilter) {
    DateTime startDate = DATE_TIME_FORMATTER.parseDateTime(startDateString);
    DateTime endDate = DATE_TIME_FORMATTER.parseDateTime(endDateString);
    int numRows = (Days.daysBetween(startDate, endDate).getDays() + 1) / numDaysPerCohort;

    List<Set<Integer>> rowIdSets = getUserIdsSets(rowEventType, startDate, rowEventFilter,
        numDaysPerCohort, numRows);
    List<Set<Integer>> columnIdSets = getUserIdsSets(columnEventType, startDate, columnEventFilter,
        numDaysPerCohort,
        numColumns + numRows);

    Table<Integer, Integer, Integer> retentionTable = ArrayTable.create(
        ContiguousSet.create(Range.closedOpen(0, numRows), DiscreteDomain.integers()),
        ContiguousSet.create(Range.closedOpen(0, numColumns + 1), DiscreteDomain.integers()));
    retentionTable.put(0, 0, 0);
    for (int i = 0; i < numRows; i++) {
      retentionTable.put(i, 0, rowIdSets.get(i).size());
    }
    for (int i = 0; i < numRows; i++) {
      for (int j = 0; j < numColumns; j++) {
        Set<Integer> rowSet = rowIdSets.get(i);
        Set<Integer> columnSet = columnIdSets.get(j + i);
        int count = 0;
        for (Integer columnValue : columnSet) {
          if (rowSet.contains(columnValue)) {
            count++;
          }
        }
        retentionTable.put(i, j + 1, count);
      }
    }
    int[][] result = new int[numRows][numColumns + 1];
    for (int i = 0; i < numRows; i++) {
      for (int j = 0; j < numColumns + 1; j++) {
        result[i][j] = retentionTable.get(i, j);
      }
    }
    return result;
  }

  public synchronized int[] getFunnelCounts(String startDate, String endDate, String[] funnelStepsEventTypes,
      int numDaysToCompleteFunnel, List<Filter> eventFilters, Filter userFilter) {
    IdList firstStepEventIdList = new MemIdList(new long[10000], 0);
    int[] funnelStepsEventTypeIds = getEventTypeIds(funnelStepsEventTypes);

    List<Integer> userIdsList = Lists.newArrayList();
    Set<Integer> userIdsSet = Sets.newHashSet();
    EventIndex.Callback aggregateUserIdsCallback = new AggregateUserIds(eventStorage, userStorage,
        firstStepEventIdList, eventFilters.get(0), userFilter, userIdsList, userIdsSet);
    shardedEventIndex.enumerateEventIds(funnelStepsEventTypes[0], startDate, endDate,
        aggregateUserIdsCallback);
    int[] numFunnelStepsMatched = new int[funnelStepsEventTypes.length];
    IdList.Iterator firstStepEventIdIterator = firstStepEventIdList.iterator();
    if (funnelStepsEventTypes.length == 1) {
      for (int userId : userIdsList) {
        numFunnelStepsMatched[0]++;
      }
    } else {
      for (int userId : userIdsList) {
        long firstStepEventId = firstStepEventIdIterator.next();
        long maxLastStepEventId = datedEventIndex.findFirstEventIdOnDate(firstStepEventId, numDaysToCompleteFunnel);
        CountMatchedFunnelSteps countMatchedFunnelSteps = new CountMatchedFunnelSteps(
            eventStorage, userStorage, funnelStepsEventTypeIds, 1 /* first step already matched*/,
            maxLastStepEventId, eventFilters, userFilter);
        userEventIndex.enumerateEventIds(userId, userEventIndex.getEventOffset(userId, firstStepEventId),
            Integer.MAX_VALUE, countMatchedFunnelSteps);
        for (int i = 0; i < countMatchedFunnelSteps.getNumMatchedSteps(); i++) {
          numFunnelStepsMatched[i]++;
        }
      }
    }
    return numFunnelStepsMatched;
  }

  public synchronized void aliasUser(String fromExternalUserId, String toExternalUserId) {
    userStorage.ensureUser(toExternalUserId);
    int id = userStorage.getId(toExternalUserId);
    if (id == UserStorage.USER_NOT_FOUND) {
      throw new IllegalArgumentException(String .format("User: %s does not exist!!!", toExternalUserId));
    }
    userStorage.alias(fromExternalUserId, id);
  }

  public synchronized int addOrUpdateUser(User user) {
    userStorage.ensureUser(user.getExternalId());
    int userId = userStorage.updateUser(user);
    propertiesIndex.addUser(user);
    return userId;
  }

  public User getUser(int userId) {
    return userStorage.getUser(userId);
  }

  public Event getEvent(long eventId) {
    return eventStorage.getEvent(eventId);
  }

  public synchronized long addEvent(Event event) {
    // ensure the given event type has an id associated
    int eventTypeId = shardedEventIndex.ensureEventType(event.getEventType());
    // ensure the given user has an id associated
    int userId = userStorage.ensureUser(event.getExternalUserId());

    long eventId = eventStorage.addEvent(event, userId, eventTypeId);
    String date = event.getDate();
    datedEventIndex.addEvent(eventId, date);
    shardedEventIndex.addEvent(eventId, event.getEventType(), date);
    userEventIndex.addEvent(userId, eventId);
    propertiesIndex.addEvent(event);
    return eventId;
  }

  public List<String> getEventTypes() {
    return shardedEventIndex.getEventTypes();
  }

  public List<Event> getUserEvents(String externalUserId, int offset, int numRecords) {
    List<Event> events = Lists.newArrayList();
    int userId = userStorage.getId(externalUserId);
    userEventIndex.enumerateEventIds(userId, offset, numRecords,
        new CollectEvents(events, eventStorage));
    return events;
  }

  @Override
  public void close() throws IOException {
    //noinspection ResultOfMethodCallIgnored
    new File(directory).mkdirs();
    eventStorage.close();
    userStorage.close();
    shardedEventIndex.close();
    propertiesIndex.close();
    datedEventIndex.close();
    userEventIndex.close();
  }

  public String getVarz() {
    return String.format(
        "current date: %s\n" +
        "Event Storage:\n==============\n%s\n\n" +
        "User Storage:\n==============\n%s\n\n" +
        "Event Index:\n==============\n%s\n\n" +
        "User Event Index:\n==============\n%s",
        datedEventIndex.getCurrentDate(),
        eventStorage.getVarz(1),
        userStorage.getVarz(1),
        shardedEventIndex.getVarz(1),
        userEventIndex.getVarz(1));
  }

  private int[] getEventTypeIds(String[] eventTypes) {
    int[] eventTypeIds = new int[eventTypes.length];
    for (int i = 0; i < eventTypeIds.length; i++) {
      eventTypeIds[i] = shardedEventIndex.getEventTypeId(eventTypes[i]);
    }
    return eventTypeIds;
  }

  private List<Set<Integer>> getUserIdsSets(String groupByEventType, DateTime startDate,
      Filter eventFilter, int numDaysPerCohort, int numCohorts) {
    List<Set<Integer>> rows = Lists.newArrayListWithCapacity(numCohorts);
    for (int i = 0; i < numCohorts; i++) {
      DateTime currentStartDate = startDate.plusDays(i * numDaysPerCohort);
      DateTime currentEndDate = startDate.plusDays((i + 1) * numDaysPerCohort);
      List<Integer> userIdsList = Lists.newArrayList();
      Set<Integer> userIdsSet = Sets.newHashSet();
      EventIndex.Callback aggregateUserIdsCallback = new AggregateUserIds(eventStorage, userStorage,
          new DummyIdList(), eventFilter, TrueFilter.INSTANCE, userIdsList, userIdsSet);
      shardedEventIndex.enumerateEventIds(
          groupByEventType,
          currentStartDate.toString(DATE_TIME_FORMATTER),
          currentEndDate.toString(DATE_TIME_FORMATTER),
          aggregateUserIdsCallback);
      rows.add(userIdsSet);
    }
    return rows;
  }

  public List<String> getEventKeys(String eventType) {
    return propertiesIndex.getEventKeys(eventType);
  }

  public List<String> getEventValues(String eventType, String eventKey, String prefix) {
    return propertiesIndex.getEventValues(eventType, eventKey, prefix);
  }

  public List<String> getUserKeys() {
    return propertiesIndex.getUserKeys();
  }

  public List<String> getUserValues(String eventKey, String prefix) {
    return propertiesIndex.getUserValues(eventKey, prefix);
  }

  public List<User> findUsers(Filter filter) {
    List<User> users = Lists.newArrayList();
    for (int userId = 0; userId < userStorage.getNumRecords(); userId++) {
      if (filter.accept(userStorage.getFilterVisitor(userId))) {
        users.add(getUser(userId));
      }
    }
    return users;
  }

  private static class AggregateUserIds implements EventIndex.Callback {
    private final EventStorage eventStorage;
    private final UserStorage userStorage;
    private final IdList earliestEventIdList;
    private final Filter eventFilter;
    private final Filter userFilter;
    private final List<Integer> seenUserIdList;
    private final Set<Integer> seenUserIdSet;

    public AggregateUserIds(EventStorage eventStorage, UserStorage userStorage,
        IdList earliestEventIdList, Filter eventFilter, Filter userFilter,
        List<Integer> seenUserIdList, Set<Integer> seenUserIdSet) {
      this.eventStorage = eventStorage;
      this.userStorage = userStorage;
      this.earliestEventIdList = earliestEventIdList;
      this.eventFilter = eventFilter;
      this.userFilter = userFilter;
      this.seenUserIdList = seenUserIdList;
      this.seenUserIdSet = seenUserIdSet;
    }

    @Override
    public void onEventId(long eventId) {
      if (seenUserIdSet.contains(eventStorage.getUserId(eventId))) {
        return;
      }

      if (!eventFilter.accept(eventStorage.getFilterVisitor(eventId))) {
        return;
      }
      int userId = eventStorage.getUserId(eventId);
      if (!userFilter.accept(userStorage.getFilterVisitor(userId))) {
        return;
      }
      // TODO: consider other higher performing Set implementation
      if (!seenUserIdSet.contains(userId)) {
        seenUserIdSet.add(userId);
        seenUserIdList.add(userId);
        earliestEventIdList.add(eventId);
      }
    }
  }

  private static class CountMatchedFunnelSteps implements UserEventIndex.Callback {
    private final EventStorage eventStorage;
    private final UserStorage userStorage;
    private final int[] funnelStepsEventTypeIds;
    private int numMatchedSteps;
    private final List<Filter> eventFilters;
    private final Filter userFilter;
    private final long maxEventId;

    public CountMatchedFunnelSteps(EventStorage eventStorage, UserStorage userStorage,
        int[] funnelStepsEventTypeIds, int numMatchedSteps, long maxEventId, List<Filter> eventFilters,
        Filter userFilter) {
      this.eventStorage = eventStorage;
      this.userStorage = userStorage;
      this.funnelStepsEventTypeIds = funnelStepsEventTypeIds;
      this.numMatchedSteps = numMatchedSteps;
      this.maxEventId = maxEventId;
      this.eventFilters = eventFilters;
      this.userFilter = userFilter;
    }

    @Override
    public boolean shouldContinueOnEventId(long eventId) {
      if (eventId >= maxEventId) {
        return false;
      }
      int eventTypeId = eventStorage.getEventTypeId(eventId);
      if (eventTypeId != funnelStepsEventTypeIds[numMatchedSteps]) {
        return true;
      }

      if (!eventFilters.get(numMatchedSteps).accept(eventStorage.getFilterVisitor(eventId))) {
        return true;
      }
      // TODO: user ctriteria filter should be at higher level
      int userId = eventStorage.getUserId(eventId);
      if (!userFilter.accept(userStorage.getFilterVisitor(userId))) {
        return true;
      }
      numMatchedSteps++;
      return numMatchedSteps != funnelStepsEventTypeIds.length;
    }

    public int getNumMatchedSteps() {
      return numMatchedSteps;
    }
  }

  private static class CollectEvents implements UserEventIndex.Callback, EventIndex.Callback {
    private final List<Event> events;
    private final EventStorage eventStorage;

    private CollectEvents(List<Event> events, EventStorage eventStorage) {
      this.events = events;
      this.eventStorage = eventStorage;
    }

    @Override
    public boolean shouldContinueOnEventId(long eventId) {
      events.add(eventStorage.getEvent(eventId));
      return true;
    }

    @Override
    public void onEventId(long eventId) {
      events.add(eventStorage.getEvent(eventId));
    }
  }
}
