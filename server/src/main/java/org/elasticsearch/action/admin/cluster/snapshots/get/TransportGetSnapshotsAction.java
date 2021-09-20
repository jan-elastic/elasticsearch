/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.snapshots.get;

import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.StepListener;
import org.elasticsearch.action.admin.cluster.repositories.get.TransportGetRepositoriesAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.GroupedActionListener;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.SnapshotsInProgress;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.RepositoryMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.repositories.GetSnapshotInfoContext;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryData;
import org.elasticsearch.repositories.RepositoryMissingException;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.snapshots.SnapshotMissingException;
import org.elasticsearch.snapshots.SnapshotsService;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Transport Action for get snapshots operation
 */
public class TransportGetSnapshotsAction extends TransportMasterNodeAction<GetSnapshotsRequest, GetSnapshotsResponse> {

    private final RepositoriesService repositoriesService;

    @Inject
    public TransportGetSnapshotsAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        RepositoriesService repositoriesService,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            GetSnapshotsAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            GetSnapshotsRequest::new,
            indexNameExpressionResolver,
            GetSnapshotsResponse::new,
            ThreadPool.Names.SAME
        );
        this.repositoriesService = repositoriesService;
    }

    @Override
    protected ClusterBlockException checkBlock(GetSnapshotsRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }

    @Override
    protected void masterOperation(
        Task task,
        final GetSnapshotsRequest request,
        final ClusterState state,
        final ActionListener<GetSnapshotsResponse> listener
    ) {
        assert task instanceof CancellableTask : task + " not cancellable";

        getMultipleReposSnapshotInfo(
            request.isSingleRepositoryRequest() == false,
            state.custom(SnapshotsInProgress.TYPE, SnapshotsInProgress.EMPTY),
            TransportGetRepositoriesAction.getRepositories(state, request.repositories()),
            request.snapshots(),
            request.ignoreUnavailable(),
            request.verbose(),
            (CancellableTask) task,
            request.sort(),
            request.after(),
            request.offset(),
            request.size(),
            request.order(),
            buildSnapshotPredicate(request.sort(), request.order(), request.policies(), request.fromSortValue()),
            listener
        );
    }

    private void getMultipleReposSnapshotInfo(
        boolean isMultiRepoRequest,
        SnapshotsInProgress snapshotsInProgress,
        List<RepositoryMetadata> repos,
        String[] snapshots,
        boolean ignoreUnavailable,
        boolean verbose,
        CancellableTask cancellableTask,
        GetSnapshotsRequest.SortBy sortBy,
        @Nullable GetSnapshotsRequest.After after,
        int offset,
        int size,
        SortOrder order,
        @Nullable Predicate<SnapshotInfo> predicate,
        ActionListener<GetSnapshotsResponse> listener
    ) {
        // short-circuit if there are no repos, because we can not create GroupedActionListener of size 0
        if (repos.isEmpty()) {
            listener.onResponse(new GetSnapshotsResponse(Collections.emptyList(), Collections.emptyMap(), null, 0, 0));
            return;
        }
        final GroupedActionListener<Tuple<Tuple<String, ElasticsearchException>, SnapshotsInRepo>> groupedActionListener =
            new GroupedActionListener<>(listener.map(responses -> {
                assert repos.size() == responses.size();
                final List<SnapshotInfo> allSnapshots = responses.stream()
                    .map(Tuple::v2)
                    .filter(Objects::nonNull)
                    .flatMap(snapshotsInRepo -> snapshotsInRepo.snapshotInfos.stream())
                    .collect(Collectors.toUnmodifiableList());
                final Map<String, ElasticsearchException> failures = responses.stream()
                    .map(Tuple::v1)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Tuple::v1, Tuple::v2));
                final SnapshotsInRepo snInfos = sortAndFilterSnapshots(allSnapshots, sortBy, after, offset, size, order, predicate);
                final List<SnapshotInfo> snapshotInfos = snInfos.snapshotInfos;
                final int remaining = snInfos.remaining + responses.stream()
                    .map(Tuple::v2)
                    .filter(Objects::nonNull)
                    .mapToInt(s -> s.remaining)
                    .sum();
                return new GetSnapshotsResponse(
                    snapshotInfos,
                    failures,
                    remaining > 0
                        ? GetSnapshotsRequest.After.from(snapshotInfos.get(snapshotInfos.size() - 1), sortBy).asQueryParam()
                        : null,
                    responses.stream().map(Tuple::v2).filter(Objects::nonNull).mapToInt(s -> s.totalCount).sum(),
                    remaining
                );
            }), repos.size());

        for (final RepositoryMetadata repo : repos) {
            final String repoName = repo.name();
            getSingleRepoSnapshotInfo(
                snapshotsInProgress,
                repoName,
                snapshots,
                predicate,
                ignoreUnavailable,
                verbose,
                cancellableTask,
                sortBy,
                after,
                order,
                groupedActionListener.delegateResponse((groupedListener, e) -> {
                    if (isMultiRepoRequest && e instanceof ElasticsearchException) {
                        groupedListener.onResponse(Tuple.tuple(Tuple.tuple(repoName, (ElasticsearchException) e), null));
                    } else {
                        groupedListener.onFailure(e);
                    }
                }).map(snInfos -> Tuple.tuple(null, snInfos))
            );
        }
    }

    private void getSingleRepoSnapshotInfo(
        SnapshotsInProgress snapshotsInProgress,
        String repo,
        String[] snapshots,
        Predicate<SnapshotInfo> predicate,
        boolean ignoreUnavailable,
        boolean verbose,
        CancellableTask task,
        GetSnapshotsRequest.SortBy sortBy,
        @Nullable final GetSnapshotsRequest.After after,
        SortOrder order,
        ActionListener<SnapshotsInRepo> listener
    ) {
        final Map<String, Snapshot> allSnapshotIds = new HashMap<>();
        final List<SnapshotInfo> currentSnapshots = new ArrayList<>();
        for (SnapshotInfo snapshotInfo : currentSnapshots(snapshotsInProgress, repo)) {
            Snapshot snapshot = snapshotInfo.snapshot();
            allSnapshotIds.put(snapshot.getSnapshotId().getName(), snapshot);
            currentSnapshots.add(snapshotInfo);
        }

        final StepListener<RepositoryData> repositoryDataListener = new StepListener<>();
        if (isCurrentSnapshotsOnly(snapshots)) {
            repositoryDataListener.onResponse(null);
        } else {
            repositoriesService.getRepositoryData(repo, repositoryDataListener);
        }

        repositoryDataListener.whenComplete(
            repositoryData -> loadSnapshotInfos(
                snapshotsInProgress,
                repo,
                snapshots,
                ignoreUnavailable,
                verbose,
                allSnapshotIds,
                currentSnapshots,
                repositoryData,
                task,
                sortBy,
                after,
                order,
                predicate,
                listener
            ),
            listener::onFailure
        );
    }

    /**
     * Returns a list of currently running snapshots from repository sorted by snapshot creation date
     *
     * @param snapshotsInProgress snapshots in progress in the cluster state
     * @param repositoryName repository name
     * @return list of snapshots
     */
    private static List<SnapshotInfo> currentSnapshots(SnapshotsInProgress snapshotsInProgress, String repositoryName) {
        List<SnapshotInfo> snapshotList = new ArrayList<>();
        List<SnapshotsInProgress.Entry> entries = SnapshotsService.currentSnapshots(
            snapshotsInProgress,
            repositoryName,
            Collections.emptyList()
        );
        for (SnapshotsInProgress.Entry entry : entries) {
            snapshotList.add(new SnapshotInfo(entry));
        }
        return snapshotList;
    }

    private void loadSnapshotInfos(
        SnapshotsInProgress snapshotsInProgress,
        String repo,
        String[] snapshots,
        boolean ignoreUnavailable,
        boolean verbose,
        Map<String, Snapshot> allSnapshotIds,
        List<SnapshotInfo> currentSnapshots,
        @Nullable RepositoryData repositoryData,
        CancellableTask task,
        GetSnapshotsRequest.SortBy sortBy,
        @Nullable final GetSnapshotsRequest.After after,
        SortOrder order,
        @Nullable Predicate<SnapshotInfo> predicate,
        ActionListener<SnapshotsInRepo> listener
    ) {
        if (task.notifyIfCancelled(listener)) {
            return;
        }

        if (repositoryData != null) {
            for (SnapshotId snapshotId : repositoryData.getSnapshotIds()) {
                allSnapshotIds.put(snapshotId.getName(), new Snapshot(repo, snapshotId));
            }
        }

        final Set<Snapshot> toResolve = new HashSet<>();
        if (TransportGetRepositoriesAction.isMatchAll(snapshots)) {
            toResolve.addAll(allSnapshotIds.values());
        } else {
            final List<String> includePatterns = new ArrayList<>();
            final List<String> excludePatterns = new ArrayList<>();
            boolean hasCurrent = false;
            boolean seenWildcard = false;
            for (String snapshotOrPattern : snapshots) {
                if (seenWildcard && snapshotOrPattern.length() > 1 && snapshotOrPattern.startsWith("-")) {
                    excludePatterns.add(snapshotOrPattern.substring(1));
                } else {
                    if (Regex.isSimpleMatchPattern(snapshotOrPattern)) {
                        seenWildcard = true;
                        includePatterns.add(snapshotOrPattern);
                    } else if (GetSnapshotsRequest.CURRENT_SNAPSHOT.equalsIgnoreCase(snapshotOrPattern)) {
                        hasCurrent = true;
                        seenWildcard = true;
                    } else {
                        if (ignoreUnavailable == false && allSnapshotIds.containsKey(snapshotOrPattern) == false) {
                            throw new SnapshotMissingException(repo, snapshotOrPattern);
                        }
                        includePatterns.add(snapshotOrPattern);
                    }
                }
            }
            final String[] includes = includePatterns.toArray(Strings.EMPTY_ARRAY);
            final String[] excludes = excludePatterns.toArray(Strings.EMPTY_ARRAY);
            for (Map.Entry<String, Snapshot> entry : allSnapshotIds.entrySet()) {
                final Snapshot snapshot = entry.getValue();
                if (toResolve.contains(snapshot) == false
                    && Regex.simpleMatch(includes, entry.getKey())
                    && Regex.simpleMatch(excludes, entry.getKey()) == false) {
                    toResolve.add(snapshot);
                }
            }
            if (hasCurrent) {
                for (SnapshotInfo snapshotInfo : currentSnapshots) {
                    final Snapshot snapshot = snapshotInfo.snapshot();
                    if (Regex.simpleMatch(excludes, snapshot.getSnapshotId().getName()) == false) {
                        toResolve.add(snapshot);
                    }
                }
            }
            if (toResolve.isEmpty() && ignoreUnavailable == false && isCurrentSnapshotsOnly(snapshots) == false) {
                throw new SnapshotMissingException(repo, snapshots[0]);
            }
        }

        if (verbose) {
            snapshots(
                snapshotsInProgress,
                repo,
                toResolve.stream().map(Snapshot::getSnapshotId).collect(Collectors.toUnmodifiableList()),
                ignoreUnavailable,
                task,
                sortBy,
                after,
                order,
                predicate,
                listener
            );
        } else {
            assert predicate == null : "filtering is not supported in non-verbose mode";
            final SnapshotsInRepo snapshotInfos;
            if (repositoryData != null) {
                // want non-current snapshots as well, which are found in the repository data
                snapshotInfos = buildSimpleSnapshotInfos(toResolve, repo, repositoryData, currentSnapshots, sortBy, after, order);
            } else {
                // only want current snapshots
                snapshotInfos = sortSnapshots(
                    currentSnapshots.stream().map(SnapshotInfo::basic).collect(Collectors.toList()),
                    sortBy,
                    after,
                    0,
                    GetSnapshotsRequest.NO_LIMIT,
                    order
                );
            }
            listener.onResponse(snapshotInfos);
        }
    }

    /**
     * Returns a list of snapshots from repository sorted by snapshot creation date
     *  @param snapshotsInProgress snapshots in progress in the cluster state
     * @param repositoryName      repository name
     * @param snapshotIds         snapshots for which to fetch snapshot information
     * @param ignoreUnavailable   if true, snapshots that could not be read will only be logged with a warning,
     */
    private void snapshots(
        SnapshotsInProgress snapshotsInProgress,
        String repositoryName,
        Collection<SnapshotId> snapshotIds,
        boolean ignoreUnavailable,
        CancellableTask task,
        GetSnapshotsRequest.SortBy sortBy,
        @Nullable GetSnapshotsRequest.After after,
        SortOrder order,
        @Nullable Predicate<SnapshotInfo> predicate,
        ActionListener<SnapshotsInRepo> listener
    ) {
        if (task.notifyIfCancelled(listener)) {
            return;
        }
        final Set<SnapshotInfo> snapshotSet = new HashSet<>();
        final Set<SnapshotId> snapshotIdsToIterate = new HashSet<>(snapshotIds);
        // first, look at the snapshots in progress
        final List<SnapshotsInProgress.Entry> entries = SnapshotsService.currentSnapshots(
            snapshotsInProgress,
            repositoryName,
            snapshotIdsToIterate.stream().map(SnapshotId::getName).collect(Collectors.toList())
        );
        for (SnapshotsInProgress.Entry entry : entries) {
            if (snapshotIdsToIterate.remove(entry.snapshot().getSnapshotId())) {
                snapshotSet.add(new SnapshotInfo(entry));
            }
        }
        // then, look in the repository if there's any matching snapshots left
        final List<SnapshotInfo> snapshotInfos;
        if (snapshotIdsToIterate.isEmpty()) {
            snapshotInfos = Collections.emptyList();
        } else {
            snapshotInfos = Collections.synchronizedList(new ArrayList<>());
        }
        final ActionListener<Void> allDoneListener = listener.delegateFailure((l, v) -> {
            final ArrayList<SnapshotInfo> snapshotList = new ArrayList<>(snapshotInfos);
            snapshotList.addAll(snapshotSet);
            listener.onResponse(sortAndFilterSnapshots(snapshotList, sortBy, after, 0, GetSnapshotsRequest.NO_LIMIT, order, predicate));
        });
        if (snapshotIdsToIterate.isEmpty()) {
            allDoneListener.onResponse(null);
            return;
        }
        final Repository repository;
        try {
            repository = repositoriesService.repository(repositoryName);
        } catch (RepositoryMissingException e) {
            listener.onFailure(e);
            return;
        }
        repository.getSnapshotInfo(
            new GetSnapshotInfoContext(
                snapshotIdsToIterate,
                ignoreUnavailable == false,
                task::isCancelled,
                (context, snapshotInfo) -> snapshotInfos.add(snapshotInfo),
                allDoneListener
            )
        );
    }

    private boolean isCurrentSnapshotsOnly(String[] snapshots) {
        return (snapshots.length == 1 && GetSnapshotsRequest.CURRENT_SNAPSHOT.equalsIgnoreCase(snapshots[0]));
    }

    private static SnapshotsInRepo buildSimpleSnapshotInfos(
        final Set<Snapshot> toResolve,
        final String repoName,
        final RepositoryData repositoryData,
        final List<SnapshotInfo> currentSnapshots,
        final GetSnapshotsRequest.SortBy sortBy,
        @Nullable final GetSnapshotsRequest.After after,
        final SortOrder order
    ) {
        List<SnapshotInfo> snapshotInfos = new ArrayList<>();
        for (SnapshotInfo snapshotInfo : currentSnapshots) {
            if (toResolve.remove(snapshotInfo.snapshot())) {
                snapshotInfos.add(snapshotInfo.basic());
            }
        }
        Map<SnapshotId, List<String>> snapshotsToIndices = new HashMap<>();
        for (IndexId indexId : repositoryData.getIndices().values()) {
            for (SnapshotId snapshotId : repositoryData.getSnapshots(indexId)) {
                if (toResolve.contains(new Snapshot(repoName, snapshotId))) {
                    snapshotsToIndices.computeIfAbsent(snapshotId, (k) -> new ArrayList<>()).add(indexId.getName());
                }
            }
        }
        for (Snapshot snapshot : toResolve) {
            final List<String> indices = snapshotsToIndices.getOrDefault(snapshot.getSnapshotId(), Collections.emptyList());
            CollectionUtil.timSort(indices);
            snapshotInfos.add(
                new SnapshotInfo(
                    snapshot,
                    indices,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    repositoryData.getSnapshotState(snapshot.getSnapshotId())
                )
            );
        }
        return sortSnapshots(snapshotInfos, sortBy, after, 0, GetSnapshotsRequest.NO_LIMIT, order);
    }

    private static final Comparator<SnapshotInfo> BY_START_TIME = Comparator.comparingLong(SnapshotInfo::startTime)
        .thenComparing(SnapshotInfo::snapshotId);

    private static final Comparator<SnapshotInfo> BY_DURATION = Comparator.<SnapshotInfo>comparingLong(
        sni -> sni.endTime() - sni.startTime()
    ).thenComparing(SnapshotInfo::snapshotId);

    private static final Comparator<SnapshotInfo> BY_INDICES_COUNT = Comparator.<SnapshotInfo>comparingInt(sni -> sni.indices().size())
        .thenComparing(SnapshotInfo::snapshotId);

    private static final Comparator<SnapshotInfo> BY_SHARDS_COUNT = Comparator.comparingInt(SnapshotInfo::totalShards)
        .thenComparing(SnapshotInfo::snapshotId);

    private static final Comparator<SnapshotInfo> BY_FAILED_SHARDS_COUNT = Comparator.comparingInt(SnapshotInfo::failedShards)
        .thenComparing(SnapshotInfo::snapshotId);

    private static final Comparator<SnapshotInfo> BY_NAME = Comparator.comparing(sni -> sni.snapshotId().getName());

    private static final Comparator<SnapshotInfo> BY_REPOSITORY = Comparator.comparing(SnapshotInfo::repository)
        .thenComparing(SnapshotInfo::snapshotId);

    private static SnapshotsInRepo sortAndFilterSnapshots(
        final List<SnapshotInfo> snapshotInfos,
        final GetSnapshotsRequest.SortBy sortBy,
        final @Nullable GetSnapshotsRequest.After after,
        final int offset,
        final int size,
        final SortOrder order,
        final @Nullable Predicate<SnapshotInfo> predicate
    ) {
        final List<SnapshotInfo> filteredSnapshotInfos;
        if (predicate == null) {
            filteredSnapshotInfos = snapshotInfos;
        } else {
            filteredSnapshotInfos = snapshotInfos.stream().filter(predicate).collect(Collectors.toUnmodifiableList());
        }
        return sortSnapshots(filteredSnapshotInfos, sortBy, after, offset, size, order);
    }

    private static Predicate<SnapshotInfo> buildSnapshotPredicate(
        GetSnapshotsRequest.SortBy sortBy,
        SortOrder order,
        String[] slmPolicies,
        String fromSortValue
    ) {
        Predicate<SnapshotInfo> predicate = null;
        if (slmPolicies.length > 0) {
            predicate = filterBySLMPolicies(slmPolicies);
        }
        if (fromSortValue != null) {
            final Predicate<SnapshotInfo> fromSortValuePredicate = buildFromSortValuePredicate(sortBy, fromSortValue, order, null, null);
            if (predicate == null) {
                predicate = fromSortValuePredicate;
            } else {
                predicate = fromSortValuePredicate.and(predicate);
            }
        }
        return predicate;
    }

    private static SnapshotsInRepo sortSnapshots(
        List<SnapshotInfo> snapshotInfos,
        GetSnapshotsRequest.SortBy sortBy,
        @Nullable GetSnapshotsRequest.After after,
        int offset,
        int size,
        SortOrder order
    ) {
        final Comparator<SnapshotInfo> comparator;
        switch (sortBy) {
            case START_TIME:
                comparator = BY_START_TIME;
                break;
            case NAME:
                comparator = BY_NAME;
                break;
            case DURATION:
                comparator = BY_DURATION;
                break;
            case INDICES:
                comparator = BY_INDICES_COUNT;
                break;
            case SHARDS:
                comparator = BY_SHARDS_COUNT;
                break;
            case FAILED_SHARDS:
                comparator = BY_FAILED_SHARDS_COUNT;
                break;
            case REPOSITORY:
                comparator = BY_REPOSITORY;
                break;
            default:
                throw new AssertionError("unexpected sort column [" + sortBy + "]");
        }

        Stream<SnapshotInfo> infos = snapshotInfos.stream();

        if (after != null) {
            assert offset == 0 : "can't combine after and offset but saw [" + after + "] and offset [" + offset + "]";
            infos = infos.filter(buildFromSortValuePredicate(sortBy, after.value(), order, after.snapshotName(), after.repoName()));
        }
        infos = infos.sorted(order == SortOrder.DESC ? comparator.reversed() : comparator).skip(offset);
        final List<SnapshotInfo> allSnapshots = infos.collect(Collectors.toUnmodifiableList());
        final List<SnapshotInfo> snapshots;
        if (size != GetSnapshotsRequest.NO_LIMIT) {
            snapshots = allSnapshots.stream().limit(size + 1).collect(Collectors.toUnmodifiableList());
        } else {
            snapshots = allSnapshots;
        }
        final List<SnapshotInfo> resultSet = size != GetSnapshotsRequest.NO_LIMIT && size < snapshots.size()
            ? snapshots.subList(0, size)
            : snapshots;
        return new SnapshotsInRepo(resultSet, snapshotInfos.size(), allSnapshots.size() - resultSet.size());
    }

    private static Predicate<SnapshotInfo> buildFromSortValuePredicate(
        GetSnapshotsRequest.SortBy sortBy,
        String after,
        SortOrder order,
        @Nullable String snapshotName,
        @Nullable String repoName
    ) {
        final Predicate<SnapshotInfo> isAfter;
        switch (sortBy) {
            case START_TIME:
                isAfter = filterByLongOffset(SnapshotInfo::startTime, Long.parseLong(after), snapshotName, repoName, order);
                break;
            case NAME:
                if (snapshotName == null) {
                    assert repoName == null : "no snapshot name given but saw repo name [" + repoName + "]";
                    isAfter = order == SortOrder.ASC
                        ? snapshotInfo -> after.compareTo(snapshotInfo.snapshotId().getName()) <= 0
                        : snapshotInfo -> after.compareTo(snapshotInfo.snapshotId().getName()) >= 0;
                } else {
                    isAfter = order == SortOrder.ASC
                        ? (info -> compareName(snapshotName, repoName, info) < 0)
                        : (info -> compareName(snapshotName, repoName, info) > 0);
                }
                break;
            case DURATION:
                isAfter = filterByLongOffset(
                    info -> info.endTime() - info.startTime(),
                    Long.parseLong(after),
                    snapshotName,
                    repoName,
                    order
                );
                break;
            case INDICES:
                isAfter = filterByLongOffset(info -> info.indices().size(), Integer.parseInt(after), snapshotName, repoName, order);
                break;
            case SHARDS:
                isAfter = filterByLongOffset(SnapshotInfo::totalShards, Integer.parseInt(after), snapshotName, repoName, order);
                break;
            case FAILED_SHARDS:
                isAfter = filterByLongOffset(SnapshotInfo::failedShards, Integer.parseInt(after), snapshotName, repoName, order);
                break;
            case REPOSITORY:
                if (snapshotName == null) {
                    assert repoName == null : "no snapshot name given but saw repo name [" + repoName + "]";
                    isAfter = order == SortOrder.ASC
                        ? snapshotInfo -> after.compareTo(snapshotInfo.repository()) <= 0
                        : snapshotInfo -> after.compareTo(snapshotInfo.repository()) >= 0;
                } else {
                    isAfter = order == SortOrder.ASC
                        ? (info -> compareRepositoryName(snapshotName, repoName, info) < 0)
                        : (info -> compareRepositoryName(snapshotName, repoName, info) > 0);
                }
                break;
            default:
                throw new AssertionError("unexpected sort column [" + sortBy + "]");
        }
        return isAfter;
    }

    private static Predicate<SnapshotInfo> filterBySLMPolicies(String[] slmPolicies) {
        final List<String> includePatterns = new ArrayList<>();
        final List<String> excludePatterns = new ArrayList<>();
        boolean seenWildcard = false;
        boolean matchNoPolicy = false;
        for (String slmPolicy : slmPolicies) {
            if (seenWildcard && slmPolicy.length() > 1 && slmPolicy.startsWith("-")) {
                excludePatterns.add(slmPolicy.substring(1));
            } else {
                if (Regex.isSimpleMatchPattern(slmPolicy)) {
                    seenWildcard = true;
                } else if (GetSnapshotsRequest.NO_POLICY_PATTERN.equals(slmPolicy)) {
                    matchNoPolicy = true;
                }
                includePatterns.add(slmPolicy);
            }
        }
        final String[] includes = includePatterns.toArray(Strings.EMPTY_ARRAY);
        final String[] excludes = excludePatterns.toArray(Strings.EMPTY_ARRAY);
        final boolean matchWithoutPolicy = matchNoPolicy;
        return snapshotInfo -> {
            final Map<String, Object> metadata = snapshotInfo.userMetadata();
            final String policy;
            if (metadata == null) {
                policy = null;
            } else {
                final Object policyFound = metadata.get(SnapshotsService.POLICY_ID_METADATA_FIELD);
                policy = policyFound instanceof String ? (String) policyFound : null;
            }
            if (policy == null) {
                return matchWithoutPolicy;
            }
            if (Regex.simpleMatch(includes, policy) == false) {
                return false;
            }
            return excludes.length == 0 || Regex.simpleMatch(excludes, policy) == false;
        };
    }

    private static Predicate<SnapshotInfo> filterByLongOffset(
        ToLongFunction<SnapshotInfo> extractor,
        long after,
        @Nullable String snapshotName,
        @Nullable String repoName,
        SortOrder order
    ) {
        if (snapshotName == null) {
            assert repoName == null : "no snapshot name given but saw repo name [" + repoName + "]";
            return order == SortOrder.ASC ? info -> after <= extractor.applyAsLong(info) : info -> after >= extractor.applyAsLong(info);
        }
        return order == SortOrder.ASC ? info -> {
            final long val = extractor.applyAsLong(info);

            return after < val || (after == val && compareName(snapshotName, repoName, info) < 0);
        } : info -> {
            final long val = extractor.applyAsLong(info);
            return after > val || (after == val && compareName(snapshotName, repoName, info) > 0);
        };
    }

    private static int compareRepositoryName(String name, String repoName, SnapshotInfo info) {
        final int res = repoName.compareTo(info.repository());
        if (res != 0) {
            return res;
        }
        return name.compareTo(info.snapshotId().getName());
    }

    private static int compareName(String name, String repoName, SnapshotInfo info) {
        final int res = name.compareTo(info.snapshotId().getName());
        if (res != 0) {
            return res;
        }
        return repoName.compareTo(info.repository());
    }

    private static final class SnapshotsInRepo {

        private final List<SnapshotInfo> snapshotInfos;

        private final int totalCount;

        private final int remaining;

        SnapshotsInRepo(List<SnapshotInfo> snapshotInfos, int totalCount, int remaining) {
            this.snapshotInfos = snapshotInfos;
            this.totalCount = totalCount;
            this.remaining = remaining;
        }
    }
}
