package io.penguinstats.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.Document;
import org.javatuples.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import io.penguinstats.constant.Constant.LastUpdateMapKeyName;
import io.penguinstats.constant.Constant.SystemPropertyKey;
import io.penguinstats.dao.ItemDropDao;
import io.penguinstats.enums.ErrorCode;
import io.penguinstats.enums.Server;
import io.penguinstats.model.DropMatrixElement;
import io.penguinstats.model.ItemDrop;
import io.penguinstats.model.QueryConditions;
import io.penguinstats.model.TimeRange;
import io.penguinstats.util.DropMatrixElementUtil;
import io.penguinstats.util.HashUtil;
import io.penguinstats.util.LastUpdateTimeUtil;
import io.penguinstats.util.exception.DatabaseException;
import io.penguinstats.util.exception.NotFoundException;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Service("itemDropService")
public class ItemDropServiceImpl implements ItemDropService {

	@Autowired
	private ItemDropDao itemDropDao;

	@Autowired
	private DropInfoService dropInfoService;

	@Autowired
	private TimeRangeService timeRangeService;

	@Autowired
	private SystemPropertyService systemPropertyService;

	@Override
	public void saveItemDrop(ItemDrop itemDrop) {
		itemDropDao.save(itemDrop);
	}

	@Override
	public void batchSaveItemDrops(Collection<ItemDrop> itemDrops) {
		itemDropDao.saveAll(itemDrops);
	}

	@Override
	public void deleteItemDrop(String userID, String itemDropId) throws Exception {
		ItemDrop itemDrop = itemDropDao.findById(itemDropId).orElse(null);
		if (itemDrop == null || !itemDrop.getUserID().equals(userID)) {
			throw new NotFoundException(ErrorCode.NOT_FOUND,
					"ItemDrop[" + itemDropId + "] not found for user with ID[" + userID + "]", Optional.empty());
		}

		itemDrop.setIsDeleted(true);
		itemDropDao.save(itemDrop);
	}

	@Override
	public void recallItemDrop(String userID, String itemDropHashId) throws Exception {
		Pageable pageable = PageRequest.of(0, 1, new Sort(Sort.Direction.DESC, "timestamp"));
		List<ItemDrop> itemDropList = getVisibleItemDropsByUserID(userID, pageable).getContent();
		if (itemDropList.size() == 0) {
			throw new NotFoundException(ErrorCode.NOT_FOUND,
					"Visible ItemDrop not found for user with ID[" + userID + "]", Optional.empty());
		}

		ItemDrop lastItemDrop = itemDropList.get(0);
		String lastItemDropHashId = HashUtil.getHash(lastItemDrop.getId().toString());
		if (!lastItemDropHashId.equals(itemDropHashId)) {
			throw new DatabaseException(ErrorCode.ITEM_DROP_HASH_ID_NOT_MATCH, "ItemDropHashId doesn't match!",
					Optional.ofNullable(itemDropHashId));
		}

		lastItemDrop.setIsDeleted(true);
		itemDropDao.save(lastItemDrop);
	}

	@Override
	public List<ItemDrop> getAllItemDrops() {
		return itemDropDao.findAll();
	}

	@Override
	public Page<ItemDrop> getAllItemDrops(Pageable pageable) {
		return itemDropDao.findAll(pageable);
	}

	@Override
	public List<ItemDrop> getAllReliableItemDrops() {
		return itemDropDao.findByIsReliable(true);
	}

	@Override
	public Page<ItemDrop> getVisibleItemDropsByUserID(String userID, Pageable pageable) {
		return itemDropDao.findByIsDeletedAndUserID(false, userID, pageable);
	}

	@Override
	public List<ItemDrop> getItemDropsByUserID(String userID) {
		return itemDropDao.findByUserID(userID);
	}

	@Override
	public Page<ItemDrop> getValidItemDropsByStageId(String stageId, Pageable pageable) {
		return itemDropDao.findValidItemDropByStageId(stageId, pageable);
	}

	@Override
	public List<DropMatrixElement> generateGlobalDropMatrixElements(Server server, String userID, boolean isPast) {
		Long startTime = System.currentTimeMillis();

		Map<String, List<Pair<String, List<TimeRange>>>> latestMaxAccumulatableTimeRangesMap =
				timeRangeService.getLatestMaxAccumulatableTimeRangesMapByServer(server);

		Map<String, List<Pair<TimeRange, List<String>>>> convertedMap = new HashMap<>();
		latestMaxAccumulatableTimeRangesMap.forEach((stageId, pairs) -> {
			List<Pair<TimeRange, List<String>>> subList = new ArrayList<>();
			Map<TimeRange, List<String>> subMap = new HashMap<>();
			pairs.forEach(pair -> {
				String itemId = pair.getValue0();
				List<TimeRange> ranges = pair.getValue1();
				Iterator<TimeRange> iter = ranges.iterator();
				while (iter.hasNext()) {
					TimeRange range = iter.next();
					boolean isCurrentTimeInRange = range.isIn(System.currentTimeMillis());
					if (isPast && isCurrentTimeInRange || !isPast && !isCurrentTimeInRange)
						continue;
					List<String> itemIds = subMap.getOrDefault(range, new ArrayList<>());
					itemIds.add(itemId);
					subMap.put(range, itemIds);
				}
			});
			subMap.forEach((range, itemIds) -> subList.add(Pair.with(range, itemIds)));
			convertedMap.put(stageId, subList);
		});

		Integer maxSize = null;
		for (String stageId : convertedMap.keySet()) {
			List<Pair<TimeRange, List<String>>> pairs = convertedMap.get(stageId);
			if (maxSize == null || maxSize < pairs.size())
				maxSize = pairs.size();
		}

		List<String> userIDs = userID != null ? Collections.singletonList(userID) : new ArrayList<>();
		Map<String, Map<String, List<DropMatrixElement>>> allElementsMap = new HashMap<>();

		if (maxSize != null) {
			for (int i = 0; i < maxSize; i++) {
				Map<String, List<TimeRange>> timeRangeMap = new HashMap<>();
				for (String stageId : convertedMap.keySet()) {
					List<Pair<TimeRange, List<String>>> pairs = convertedMap.get(stageId);
					if (i >= pairs.size())
						continue;
					Pair<TimeRange, List<String>> pair = pairs.get(i);
					TimeRange range = pair.getValue0();
					timeRangeMap.put(stageId, Collections.singletonList(range));
				}
				List<DropMatrixElement> elements = generateDropMatrixElementsFromTimeRangeMapByStageId(server,
						timeRangeMap, new ArrayList<>(), userIDs);

				for (String stageId : convertedMap.keySet()) {
					Map<String, List<DropMatrixElement>> subMap = allElementsMap.getOrDefault(stageId, new HashMap<>());
					List<Pair<TimeRange, List<String>>> pairs = convertedMap.get(stageId);
					if (i >= pairs.size())
						continue;
					Pair<TimeRange, List<String>> pair = pairs.get(i);
					Set<String> itemIdSet = new HashSet<>(pair.getValue1());
					List<DropMatrixElement> filteredElements = elements.stream()
							.filter(el -> el.getStageId().equals(stageId) && itemIdSet.contains(el.getItemId()))
							.collect(toList());
					filteredElements.forEach(el -> {
						String itemId = el.getItemId();
						List<DropMatrixElement> subList = subMap.getOrDefault(itemId, new ArrayList<>());
						subList.add(el);
						subMap.put(itemId, subList);
						itemIdSet.remove(itemId);
					});
					allElementsMap.put(stageId, subMap);
				}
			}
		}

		List<DropMatrixElement> result = allElementsMap.values().stream()
				.flatMap(m -> m.values().stream().map(els -> DropMatrixElementUtil.combineElements(els)))
				.collect(toList());

		if (userID == null) {
			LastUpdateTimeUtil.setCurrentTimestamp(
					(isPast ? LastUpdateMapKeyName.PAST_MATRIX_RESULT : LastUpdateMapKeyName.CURRENT_MATRIX_RESULT)
							+ "_" + server);
			log.info("generateGlobalDropMatrixElements done in {} ms for server {}, isPast = {}",
					System.currentTimeMillis() - startTime, server, isPast);
		}

		return result;
	}

	@Override
	public List<DropMatrixElement> refreshGlobalDropMatrixElements(Server server, boolean isPast) {
		return generateGlobalDropMatrixElements(server, null, isPast);
	}

	private List<DropMatrixElement> generateDropMatrixElementsFromTimeRangeMapByStageId(Server server,
			Map<String, List<TimeRange>> timeRangeMap, List<String> itemIds, List<String> userIDs) {
		Integer maxSize = null;
		for (String stageId : timeRangeMap.keySet()) {
			List<TimeRange> ranges = timeRangeMap.get(stageId);
			if (maxSize == null || maxSize < ranges.size())
				maxSize = ranges.size();
		}

		Map<String, Map<String, List<DropMatrixElement>>> mapByStageIdAndItemId = new HashMap<>();
		for (int i = 0; i < maxSize; i++) {
			QueryConditions conditions = new QueryConditions();
			if (server != null)
				conditions.addServer(server);
			if (Optional.ofNullable(userIDs).map(list -> !list.isEmpty()).orElse(false)) {
				userIDs.forEach(userID -> conditions.addUserID(userID));
			}
			if (Optional.ofNullable(itemIds).map(list -> !list.isEmpty()).orElse(false)) {
				itemIds.forEach(itemId -> conditions.addItemId(itemId));
			}

			Map<String, TimeRange> currentRangesByStageId = new HashMap<>();
			for (String stageId : timeRangeMap.keySet()) {
				List<TimeRange> ranges = timeRangeMap.get(stageId);
				if (i < ranges.size()) {
					TimeRange range = ranges.get(i);
					conditions.addStage(stageId, range.getStart(), range.getEnd());
					currentRangesByStageId.put(stageId, range);
				}
			}

			List<Document> docs = itemDropDao.aggregateItemDrops(conditions);
			Map<String, List<Document>> docsGroupByStageId =
					docs.stream().collect(groupingBy(doc -> doc.getString("stageId")));
			for (String stageId : docsGroupByStageId.keySet()) {
				List<Document> docsForOneStage = docsGroupByStageId.get(stageId);
				TimeRange currentRange = currentRangesByStageId.get(stageId);
				Integer timesForStage = docsForOneStage.get(0).getInteger("times");
				Set<String> dropSet = dropInfoService.getDropSet(server, stageId, currentRange.getStart());
				Map<String, List<DropMatrixElement>> mapByItemId =
						mapByStageIdAndItemId.getOrDefault(stageId, new HashMap<>());

				docsForOneStage.forEach(doc -> {
					if (doc.containsKey("itemId")) {
						String itemId = doc.getString("itemId");
						if (!dropSet.contains(itemId))
							log.warn("Item " + itemId + " is invalid in stage " + stageId);
						else {
							dropSet.remove(itemId);
							Integer quantity = doc.getInteger("quantity");
							Integer times = doc.getInteger("times");
							DropMatrixElement element = new DropMatrixElement(stageId, itemId, quantity, times,
									currentRange.getStart(), currentRange.getEnd());
							List<DropMatrixElement> elements = mapByItemId.getOrDefault(itemId, new ArrayList<>());
							elements.add(element);
							mapByItemId.put(itemId, elements);
						}
					}
				});

				if (!dropSet.isEmpty()) {
					dropSet.forEach(itemId -> {
						if (itemIds == null || itemIds.isEmpty() || itemIds.contains(itemId)) {
							DropMatrixElement element = new DropMatrixElement(stageId, itemId, 0, timesForStage,
									currentRange.getStart(), currentRange.getEnd());
							List<DropMatrixElement> elements = mapByItemId.getOrDefault(itemId, new ArrayList<>());
							elements.add(element);
							mapByItemId.put(itemId, elements);
						}
					});
				}
				mapByStageIdAndItemId.put(stageId, mapByItemId);
			}
		}

		List<DropMatrixElement> result = new ArrayList<>();
		mapByStageIdAndItemId.forEach((stageId, mapByItemId) -> {
			mapByItemId.forEach((itemId, elements) -> {
				DropMatrixElement newElement = DropMatrixElementUtil.combineElements(elements);
				result.add(newElement);
			});
		});
		return result;
	}

	@Override
	public List<DropMatrixElement> generateSegmentedGlobalDropMatrixElements(Server server, Long interval, Long range) {
		Long end = System.currentTimeMillis();
		Long start = end - range;
		List<DropMatrixElement> result =
				generateSegmentedDropMatrixElements(server, null, null, start, end, null, interval);
		LastUpdateTimeUtil
				.setCurrentTimestamp(LastUpdateMapKeyName.TREND_RESULT + "_" + server + "_" + interval + "_" + range);
		log.info("generateSegmentedGlobalDropMatrixElementMap done in {} ms", System.currentTimeMillis() - end);
		return result;
	}

	private List<DropMatrixElement> generateSegmentedDropMatrixElements(Server server, String stageId,
			List<String> itemIds, Long start, Long end, List<String> userIDs, Long interval) {
		if (end == null)
			end = System.currentTimeMillis();
		if (start == null || start.compareTo(end) >= 0)
			return new ArrayList<>();
		int sectionNum = new Double(Math.ceil(new Double((end - start) * 1.0 / interval).doubleValue())).intValue();
		if (sectionNum > systemPropertyService.getPropertyIntegerValue(SystemPropertyKey.MAX_SECTION_NUM)) {
			log.error("exceed max section num, now is " + sectionNum);
			return new ArrayList<>();
		}

		QueryConditions conditions = new QueryConditions();
		conditions.addStage(stageId, start, end);
		conditions.setInterval(interval);
		if (server != null)
			conditions.addServer(server);
		if (Optional.ofNullable(userIDs).map(list -> !list.isEmpty()).orElse(false)) {
			userIDs.forEach(userID -> conditions.addUserID(userID));
		}
		if (Optional.ofNullable(itemIds).map(list -> !list.isEmpty()).orElse(false)) {
			itemIds.forEach(itemId -> conditions.addItemId(itemId));
		}

		List<Document> docs = itemDropDao.aggregateItemDrops(conditions);

		Map<String, Map<String, List<DropMatrixElement>>> map = new HashMap<>();
		Map<String, Map<Integer, Integer>> timesMap = new HashMap<>();
		docs.forEach(doc -> {
			if (doc.containsKey("itemId")) {
				String stageIdInDoc = doc.getString("stageId");
				String itemId = doc.getString("itemId");
				Integer quantity = doc.getInteger("quantity");
				Integer times = doc.getInteger("times");
				Integer section = doc.getDouble("section").intValue();

				DropMatrixElement element =
						new DropMatrixElement(stageIdInDoc, itemId, quantity, times, new Long(section), null);

				Map<String, List<DropMatrixElement>> subMap = map.getOrDefault(stageIdInDoc, new HashMap<>());
				List<DropMatrixElement> elements = subMap.getOrDefault(itemId, new ArrayList<>());
				elements.add(element);
				subMap.put(itemId, elements);
				map.put(stageIdInDoc, subMap);

				Map<Integer, Integer> timesMapSubMap = timesMap.getOrDefault(stageIdInDoc, new HashMap<>());
				timesMapSubMap.put(section, times);
				timesMap.put(stageIdInDoc, timesMapSubMap);
			}
		});

		map.forEach((stageIdInDoc, subMap) -> {
			Map<Integer, Integer> timesSubMap = timesMap.get(stageIdInDoc);
			subMap.forEach((itemId, elements) -> {
				Set<Integer> sectionSet = new HashSet<>();
				for (int i = 0; i < sectionNum; i++)
					sectionSet.add(i);
				elements.forEach(el -> sectionSet.remove(el.getStart().intValue()));
				sectionSet.forEach(section -> {
					Integer times =
							timesSubMap == null || !timesSubMap.containsKey(section) ? 0 : timesSubMap.get(section);
					DropMatrixElement newElement =
							new DropMatrixElement(stageIdInDoc, itemId, 0, times, new Long(section), null);
					elements.add(newElement);
				});
				elements.sort((e1, e2) -> e1.getStart().compareTo(e2.getStart()));
				elements.forEach(el -> {
					el.setStart(el.getStart() * interval + start);
					el.setEnd(el.getStart() + interval);
				});
			});
		});
		return map.values().stream().flatMap(m -> m.values().stream().flatMap(List::stream))
				.collect(Collectors.toList());
	}

	@Override
	public List<DropMatrixElement> refreshSegmentedGlobalDropMatrixElements(Server server, Long interval, Long range) {
		return generateSegmentedGlobalDropMatrixElements(server, interval, range);
	}

	@Override
	public List<DropMatrixElement> generateCustomDropMatrixElements(Server server, String stageId, List<String> itemIds,
			Long start, Long end, List<String> userIDs, Long interval) {
		List<TimeRange> splittedRanges = timeRangeService.getSplittedTimeRanges(server, stageId, start, end);
		Map<String, List<TimeRange>> timeRangeMap = new HashMap<>();
		timeRangeMap.put(stageId, splittedRanges);
		if (interval == null)
			return generateDropMatrixElementsFromTimeRangeMapByStageId(server, timeRangeMap, itemIds, userIDs);
		else
			return generateSegmentedDropMatrixElements(server, stageId, itemIds, start, end, userIDs, interval);
	}

	@Override
	public Map<String, Integer> getTotalStageTimesMap(Server server, Long range) {
		QueryConditions conditions = new QueryConditions().addServer(server).setRange(range);
		List<Document> docs = itemDropDao.aggregateStageTimes(conditions);
		Map<String, Integer> result =
				docs.stream().collect(Collectors.toMap(doc -> doc.getString("_id"), doc -> doc.getInteger("times")));
		LastUpdateTimeUtil.setCurrentTimestamp(
				LastUpdateMapKeyName.TOTAL_STAGE_TIMES_MAP + "_" + server + (range == null ? "" : "_" + range));
		return result;
	}

	@Override
	public Map<String, Integer> refreshTotalStageTimesMap(Server server, Long range) {
		return getTotalStageTimesMap(server, range);
	}

	@Override
	public Map<String, Integer> getTotalItemQuantitiesMap(Server server) {
		QueryConditions conditions = new QueryConditions().addServer(server);
		List<Document> docs = itemDropDao.aggregateItemQuantities(conditions);
		Map<String, Integer> result =
				docs.stream().collect(Collectors.toMap(doc -> doc.getString("_id"), doc -> doc.getInteger("quantity")));
		LastUpdateTimeUtil.setCurrentTimestamp(LastUpdateMapKeyName.TOTAL_ITEM_QUANTITIES_MAP + "_" + server);
		return result;
	}

	@Override
	public Map<String, Integer> refreshTotalItemQuantitiesMap(Server server) {
		return getTotalItemQuantitiesMap(server);
	}

}
