/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.preloader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.NearCacheConfiguration;
import org.apache.ignite.events.CacheEvent;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.EventType;
import org.apache.ignite.internal.IgniteClientDisconnectedCheckedException;
import org.apache.ignite.internal.IgniteDiagnosticAware;
import org.apache.ignite.internal.IgniteDiagnosticPrepareContext;
import org.apache.ignite.internal.IgniteFutureTimeoutCheckedException;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.IgniteNeedReconnectException;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.events.DiscoveryCustomEvent;
import org.apache.ignite.internal.managers.discovery.DiscoCache;
import org.apache.ignite.internal.managers.discovery.DiscoveryCustomMessage;
import org.apache.ignite.internal.processors.affinity.AffinityAssignment;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.affinity.GridAffinityAssignmentCache;
import org.apache.ignite.internal.processors.cache.CacheAffinityChangeMessage;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.CachePartitionExchangeWorkerTask;
import org.apache.ignite.internal.processors.cache.DynamicCacheChangeBatch;
import org.apache.ignite.internal.processors.cache.DynamicCacheDescriptor;
import org.apache.ignite.internal.processors.cache.ExchangeActions;
import org.apache.ignite.internal.processors.cache.ExchangeContext;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheMvccCandidate;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.StateChangeRequest;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridClientPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtLocalPartition;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionState;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTopologyFutureAdapter;
import org.apache.ignite.internal.processors.cache.persistence.snapshot.SnapshotDiscoveryMessage;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxKey;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.cluster.ChangeGlobalStateFinishMessage;
import org.apache.ignite.internal.processors.cluster.ChangeGlobalStateMessage;
import org.apache.ignite.internal.util.GridPartitionStateMap;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.tostring.GridToStringInclude;
import org.apache.ignite.internal.util.typedef.CI1;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.lang.IgniteRunnable;
import org.jetbrains.annotations.Nullable;
import org.jsr166.ConcurrentHashMap8;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_LONG_OPERATIONS_DUMP_TIMEOUT_LIMIT;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_PARTITION_RELEASE_FUTURE_DUMP_THRESHOLD;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_THREAD_DUMP_ON_EXCHANGE_TIMEOUT;
import static org.apache.ignite.IgniteSystemProperties.getBoolean;
import static org.apache.ignite.IgniteSystemProperties.getLong;
import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_JOINED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.internal.events.DiscoveryCustomEvent.EVT_DISCOVERY_CUSTOM_EVT;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.SYSTEM_POOL;

/**
 * Future for exchanging partition maps.
 */
@SuppressWarnings({"TypeMayBeWeakened", "unchecked"})
public class GridDhtPartitionsExchangeFuture extends GridDhtTopologyFutureAdapter
    implements Comparable<GridDhtPartitionsExchangeFuture>, CachePartitionExchangeWorkerTask, IgniteDiagnosticAware {
    /** */
    public static final String EXCHANGE_LOG = "org.apache.ignite.internal.exchange.time";

    /** */
    private static final int RELEASE_FUTURE_DUMP_THRESHOLD =
        IgniteSystemProperties.getInteger(IGNITE_PARTITION_RELEASE_FUTURE_DUMP_THRESHOLD, 0);

    /** */
    @GridToStringExclude
    private volatile DiscoCache discoCache;

    /** Discovery event. */
    private volatile DiscoveryEvent discoEvt;

    /** */
    @GridToStringExclude
    private final Set<UUID> remaining = new HashSet<>();

    /** Guarded by this */
    @GridToStringExclude
    private int pendingSingleUpdates;

    /** */
    @GridToStringExclude
    private List<ClusterNode> srvNodes;

    /** */
    private ClusterNode crd;

    /** ExchangeFuture id. */
    private final GridDhtPartitionExchangeId exchId;

    /** Cache context. */
    private final GridCacheSharedContext<?, ?> cctx;

    /** Busy lock to prevent activities from accessing exchanger while it's stopping. */
    private ReadWriteLock busyLock;

    /** */
    private AtomicBoolean added = new AtomicBoolean(false);

    /** Event latch. */
    @GridToStringExclude
    private final CountDownLatch evtLatch = new CountDownLatch(1);

    /** Exchange future init method completes this future. */
    private GridFutureAdapter<Boolean> initFut;

    /** */
    @GridToStringExclude
    private final List<IgniteRunnable> discoEvts = new ArrayList<>();

    /** */
    private boolean init;

    /** Last committed cache version before next topology version use. */
    private AtomicReference<GridCacheVersion> lastVer = new AtomicReference<>();

    /**
     * Messages received on non-coordinator are stored in case if this node
     * becomes coordinator.
     */
    private final Map<UUID, GridDhtPartitionsSingleMessage> pendingSingleMsgs = new ConcurrentHashMap8<>();

    /** Messages received from new coordinator. */
    private final Map<ClusterNode, GridDhtPartitionsFullMessage> fullMsgs = new ConcurrentHashMap8<>();

    /** */
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    @GridToStringInclude
    private volatile IgniteInternalFuture<?> partReleaseFut;

    /** Logger. */
    private final IgniteLogger log;

    /** Cache change requests. */
    private ExchangeActions exchActions;

    /** */
    private final IgniteLogger exchLog;

    /** */
    private CacheAffinityChangeMessage affChangeMsg;

    /** Init timestamp. Used to track the amount of time spent to complete the future. */
    private long initTs;

    /**
     * Centralized affinity assignment required. Activated for node left of failed. For this mode crd will send full
     * partitions maps to nodes using discovery (ring) instead of communication.
     */
    private boolean centralizedAff;

    /** Change global state exception. */
    private Exception changeGlobalStateE;

    /** Change global state exceptions. */
    private final Map<UUID, Exception> changeGlobalStateExceptions = new ConcurrentHashMap8<>();

    /** */
    private ConcurrentMap<UUID, GridDhtPartitionsSingleMessage> msgs = new ConcurrentHashMap8<>();

    /** */
    private Map<UUID, GridDhtPartitionsSingleMessage> mergedJoinExchMsgs;

    /** */
    private int awaitMergedMsgs;

    /** */
    @GridToStringExclude
    private volatile IgniteDhtPartitionHistorySuppliersMap partHistSuppliers = new IgniteDhtPartitionHistorySuppliersMap();

    /** */
    private volatile Map<Integer, Map<Integer, Long>> partHistReserved;

    /** */
    @GridToStringExclude
    private volatile IgniteDhtPartitionsToReloadMap partsToReload = new IgniteDhtPartitionsToReloadMap();

    /** */
    private final AtomicBoolean done = new AtomicBoolean();

    /** */
    private ExchangeLocalState state;

    /** */
    @GridToStringExclude
    private ExchangeContext exchCtx;

    /** */
    @GridToStringExclude
    private FinishState finishState;

    /** */
    @GridToStringExclude
    private InitNewCoordinatorFuture newCrdFut;

    /** */
    @GridToStringExclude
    private GridDhtPartitionsExchangeFuture mergedWith;

    /** */
    @GridToStringExclude
    private GridDhtPartitionsSingleMessage pendingJoinMsg;


    /**
     * @param cctx Cache context.
     * @param busyLock Busy lock.
     * @param exchId Exchange ID.
     * @param exchActions Cache change requests.
     * @param affChangeMsg Affinity change message.
     */
    public GridDhtPartitionsExchangeFuture(
        GridCacheSharedContext cctx,
        ReadWriteLock busyLock,
        GridDhtPartitionExchangeId exchId,
        ExchangeActions exchActions,
        CacheAffinityChangeMessage affChangeMsg
    ) {
        assert busyLock != null;
        assert exchId != null;
        assert exchId.topologyVersion() != null;
        assert exchActions == null || !exchActions.empty();

        this.cctx = cctx;
        this.busyLock = busyLock;
        this.exchId = exchId;
        this.exchActions = exchActions;
        this.affChangeMsg = affChangeMsg;

        log = cctx.logger(getClass());
        exchLog = cctx.logger(EXCHANGE_LOG);

        initFut = new GridFutureAdapter<>();

        if (log.isDebugEnabled())
            log.debug("Creating exchange future [localNode=" + cctx.localNodeId() + ", fut=" + this + ']');
    }

    /**
     * @return Shared cache context.
     */
    GridCacheSharedContext sharedContext() {
        return cctx;
    }

    /** {@inheritDoc} */
    @Override public boolean skipForExchangeMerge() {
        return false;
    }

    /**
     * @return Exchange context.
     */
    public ExchangeContext context() {
        assert exchCtx != null : this;

        return exchCtx;
    }

    /**
     * @param exchActions Exchange actions.
     */
    public void exchangeActions(ExchangeActions exchActions) {
        assert exchActions == null || !exchActions.empty() : exchActions;
        assert evtLatch != null && evtLatch.getCount() == 1L : this;

        this.exchActions = exchActions;
    }

    /**
     * @param affChangeMsg Affinity change message.
     */
    public void affinityChangeMessage(CacheAffinityChangeMessage affChangeMsg) {
        this.affChangeMsg = affChangeMsg;
    }

    /**
     * @return Initial exchange version.
     */
    public AffinityTopologyVersion initialVersion() {
        return exchId.topologyVersion();
    }

    /** {@inheritDoc} */
    @Override public AffinityTopologyVersion topologyVersion() {
        assert isDone();

        return exchCtx.events().topologyVersion();
    }

    /**
     * @param grpId Cache group ID.
     * @param partId Partition ID.
     * @return ID of history supplier node or null if it doesn't exist.
     */
    @Nullable public UUID partitionHistorySupplier(int grpId, int partId) {
        return partHistSuppliers.getSupplier(grpId, partId);
    }

    /**
     * @return Discovery cache.
     *
     * TODO 5578 review usages, rename initialDiscoveryEvent
     */
    public DiscoCache discoCache() {
        return discoCache;
    }

    /**
     * @param cacheId Cache ID.
     * @param rcvdFrom Node ID cache was received from.
     * @return {@code True} if cache was added during this exchange.
     */
    public boolean cacheAddedOnExchange(int cacheId, UUID rcvdFrom) {
        return dynamicCacheStarted(cacheId) || (exchId.isJoined() && exchId.nodeId().equals(rcvdFrom));
    }

    /**
     * @param grpId Cache group ID.
     * @param rcvdFrom Node ID cache group was received from.
     * @return {@code True} if cache group was added during this exchange.
     */
    public boolean cacheGroupAddedOnExchange(int grpId, UUID rcvdFrom) {
        return dynamicCacheGroupStarted(grpId) ||
            (exchId.isJoined() && exchId.nodeId().equals(rcvdFrom));
    }

    /**
     * @param cacheId Cache ID.
     * @return {@code True} if non-client cache was added during this exchange.
     */
    private boolean dynamicCacheStarted(int cacheId) {
        return exchActions != null && exchActions.cacheStarted(cacheId);
    }

    /**
     * @param grpId Cache group ID.
     * @return {@code True} if non-client cache group was added during this exchange.
     */
    public boolean dynamicCacheGroupStarted(int grpId) {
        return exchActions != null && exchActions.cacheGroupStarting(grpId);
    }

    /**
     * @return {@code True}
     */
    public boolean onAdded() {
        return added.compareAndSet(false, true);
    }

    /**
     * Event callback.
     *
     * @param exchId Exchange ID.
     * @param discoEvt Discovery event.
     * @param discoCache Discovery data cache.
     */
    public void onEvent(GridDhtPartitionExchangeId exchId, DiscoveryEvent discoEvt, DiscoCache discoCache) {
        assert exchId.equals(this.exchId);

        this.exchId.discoveryEvent(discoEvt);
        this.discoEvt = discoEvt;
        this.discoCache = discoCache;

        evtLatch.countDown();
    }

    /**
     * @return {@code True} if cluster state change exchange.
     */
    private boolean stateChangeExchange() {
        return exchActions != null && exchActions.stateChangeRequest() != null;
    }

    /**
     * @return {@code True} if activate cluster exchange.
     */
    public boolean activateCluster() {
        return exchActions != null && exchActions.activate();
    }

    /**
     * @return {@code True} if deactivate cluster exchange.
     */
    private boolean deactivateCluster() {
        return exchActions != null && exchActions.deactivate();
    }

    /**
     * @return Discovery event.
     *
     * TODO 5578 review usages, rename initialDiscoveryEvent
     */
    public DiscoveryEvent discoveryEvent() {
        return discoEvt;
    }

    /**
     * @return Exchange ID.
     */
    public GridDhtPartitionExchangeId exchangeId() {
        return exchId;
    }

    /**
     * @return {@code true} if entered to busy state.
     */
    private boolean enterBusy() {
        if (busyLock.readLock().tryLock())
            return true;

        if (log.isDebugEnabled())
            log.debug("Failed to enter busy state (exchanger is stopping): " + this);

        return false;
    }

    /**
     *
     */
    private void leaveBusy() {
        busyLock.readLock().unlock();
    }

    /**
     * Starts activity.
     *
     * @param newCrd {@code True} if node become coordinator on this exchange.
     * @throws IgniteInterruptedCheckedException If interrupted.
     */
    public void init(boolean newCrd) throws IgniteInterruptedCheckedException {
        if (isDone())
            return;

        assert !cctx.kernalContext().isDaemon();

        initTs = U.currentTimeMillis();

        U.await(evtLatch);

        assert discoEvt != null : this;
        assert exchId.nodeId().equals(discoEvt.eventNode().id()) : this;

        exchCtx = new ExchangeContext(this);

        try {
            AffinityTopologyVersion topVer = initialVersion();

            srvNodes = new ArrayList<>(discoCache.serverNodes());

            remaining.addAll(F.nodeIds(F.view(srvNodes, F.remoteNodes(cctx.localNodeId()))));

            crd = srvNodes.isEmpty() ? null : srvNodes.get(0);

            boolean crdNode = crd != null && crd.isLocal();

            assert state == null : state;

            if (crdNode)
                state = ExchangeLocalState.CRD;
            else
                state = cctx.kernalContext().clientNode() ? ExchangeLocalState.CLIENT : ExchangeLocalState.SRV;

            exchLog.info("Started exchange init [topVer=" + topVer +
                ", crd=" + crdNode +
                ", evt=" + IgniteUtils.gridEventName(discoEvt.type()) +
                ", evtNode=" + discoEvt.eventNode().id() +
                ", customEvt=" + (discoEvt.type() == EVT_DISCOVERY_CUSTOM_EVT ? ((DiscoveryCustomEvent)discoEvt).customMessage() : null) +
                ']');

            ExchangeType exchange;

            if (discoEvt.type() == EVT_DISCOVERY_CUSTOM_EVT) {
                DiscoveryCustomMessage msg = ((DiscoveryCustomEvent)discoEvt).customMessage();

                if (msg instanceof ChangeGlobalStateMessage) {
                    assert exchActions != null && !exchActions.empty();

                    exchange = onClusterStateChangeRequest(crdNode);
                }
                else if (msg instanceof DynamicCacheChangeBatch) {
                    assert exchActions != null && !exchActions.empty();

                    exchange = onCacheChangeRequest(crdNode);
                }
                else if (msg instanceof SnapshotDiscoveryMessage) {
                    exchange = CU.clientNode(discoEvt.eventNode()) ?
                        onClientNodeEvent(crdNode) :
                        onServerNodeEvent(crdNode);
                }
                else {
                    assert affChangeMsg != null : this;

                    exchange = onAffinityChangeRequest(crdNode);
                }
            }
            else {
                if (discoEvt.type() == EVT_NODE_JOINED) {
                    if (!discoEvt.eventNode().isLocal()) {
                        Collection<DynamicCacheDescriptor> receivedCaches = cctx.cache().startReceivedCaches(
                            discoEvt.eventNode().id(),
                            topVer);

                        cctx.affinity().initStartedCaches(crdNode, this, receivedCaches);
                    }
                    else
                        initCachesOnLocalJoin();
                }

                if (newCrd) {
                    IgniteInternalFuture<?> fut = cctx.affinity().initCoordinatorCaches(this, false);

                    if (fut != null)
                        fut.get();

                    cctx.exchange().coordinatorInitialized();
                }

                if (exchCtx.mergeExchanges()) {
                    if (localJoinExchange()) {
                        if (cctx.kernalContext().clientNode()) {
                            onClientNodeEvent(crdNode);

                            exchange = ExchangeType.CLIENT;
                        }
                        else {
                            onServerNodeEvent(crdNode);

                            exchange = ExchangeType.ALL;
                        }
                    }
                    else {
                        if (CU.clientNode(discoEvt.eventNode()))
                            exchange = onClientNodeEvent(crdNode);
                        else
                            exchange = cctx.kernalContext().clientNode() ? ExchangeType.CLIENT : ExchangeType.ALL;
                    }
                }
                else {
                    exchange = CU.clientNode(discoEvt.eventNode()) ? onClientNodeEvent(crdNode) :
                        onServerNodeEvent(crdNode);
                }
            }

            updateTopologies(crdNode);

            switch (exchange) {
                case ALL: {
                    distributedExchange();

                    break;
                }

                case CLIENT: {
                    if (!exchCtx.mergeExchanges())
                        initTopologies();

                    clientOnlyExchange();

                    break;
                }

                case NONE: {
                    initTopologies();

                    onDone(topVer);

                    break;
                }

                default:
                    assert false;
            }

            if (cctx.localNode().isClient())
                tryToPerformLocalSnapshotOperation();

            exchLog.info("Finished exchange init [topVer=" + topVer + ", crd=" + crdNode + ']');
        }
        catch (IgniteInterruptedCheckedException e) {
            onDone(e);

            throw e;
        }
        catch (IgniteNeedReconnectException e) {
            onDone(e);
        }
        catch (Throwable e) {
            if (reconnectOnError(e))
                onDone(new IgniteNeedReconnectException(cctx.localNode(), e));
            else {
                U.error(log, "Failed to reinitialize local partitions (preloading will be stopped): " + exchId, e);

                onDone(e);
            }

            if (e instanceof Error)
                throw (Error)e;
        }
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void initCachesOnLocalJoin() throws IgniteCheckedException {
        cctx.activate();

        List<T2<DynamicCacheDescriptor, NearCacheConfiguration>> caches =
            cctx.cache().cachesToStartOnLocalJoin();

        if (cctx.database().persistenceEnabled() && !cctx.kernalContext().clientNode()) {
            List<DynamicCacheDescriptor> startDescs = new ArrayList<>();

            if (caches != null) {
                for (T2<DynamicCacheDescriptor, NearCacheConfiguration> c : caches)
                    startDescs.add(c.get1());
            }

            cctx.database().readCheckpointAndRestoreMemory(startDescs);
        }

        cctx.cache().startCachesOnLocalJoin(caches, initialVersion());
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void initTopologies() throws IgniteCheckedException {
        cctx.database().checkpointReadLock();

        try {
            if (crd != null) {
                for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                    if (grp.isLocal())
                        continue;

                    grp.topology().beforeExchange(this, !centralizedAff);
                }
            }
        }
        finally {
            cctx.database().checkpointReadUnlock();
        }
    }

    /**
     * @param crd Coordinator flag.
     * @throws IgniteCheckedException If failed.
     */
    private void updateTopologies(boolean crd) throws IgniteCheckedException {
        for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
            if (grp.isLocal())
                continue;

            GridClientPartitionTopology clientTop = cctx.exchange().clearClientTopology(grp.groupId());

            long updSeq = clientTop == null ? -1 : clientTop.lastUpdateSequence();

            GridDhtPartitionTopology top = grp.topology();

            if (crd) {
                boolean updateTop = exchId.topologyVersion().equals(grp.localStartVersion());

                if (updateTop && clientTop != null) {
                    top.update(initialVersion(),
                        clientTop.partitionMap(true),
                        clientTop.updateCounters(false),
                        Collections.<Integer>emptySet(),
                        null);
                }
            }

            top.updateTopologyVersion(
                this,
                discoCache(),
                updSeq,
                cacheGroupStopping(grp.groupId()));
        }

        for (GridClientPartitionTopology top : cctx.exchange().clientTopologies())
            top.updateTopologyVersion(this, discoCache(), -1, cacheGroupStopping(top.groupId()));
    }

    /**
     * @param crd Coordinator flag.
     * @return Exchange type.
     */
    private ExchangeType onClusterStateChangeRequest(boolean crd) {
        assert exchActions != null && !exchActions.empty() : this;

        StateChangeRequest req = exchActions.stateChangeRequest();

        assert req != null : exchActions;

        if (req.activate()) {
            if (log.isInfoEnabled()) {
                log.info("Start activation process [nodeId=" + cctx.localNodeId() +
                    ", client=" + cctx.kernalContext().clientNode() +
                    ", topVer=" + initialVersion() + "]");
            }

            try {
                cctx.activate();

                if (cctx.database().persistenceEnabled() && !cctx.kernalContext().clientNode()) {
                    List<DynamicCacheDescriptor> startDescs = new ArrayList<>();

                    for (ExchangeActions.CacheActionData startReq : exchActions.cacheStartRequests())
                        startDescs.add(startReq.descriptor());

                    cctx.database().readCheckpointAndRestoreMemory(startDescs);
                }

                cctx.affinity().onCacheChangeRequest(this, crd, exchActions);

                if (log.isInfoEnabled()) {
                    log.info("Successfully activated caches [nodeId=" + cctx.localNodeId() +
                        ", client=" + cctx.kernalContext().clientNode() +
                        ", topVer=" + initialVersion() + "]");
                }
            }
            catch (Exception e) {
                U.error(log, "Failed to activate node components [nodeId=" + cctx.localNodeId() +
                    ", client=" + cctx.kernalContext().clientNode() +
                    ", topVer=" + initialVersion() + "]", e);

                changeGlobalStateE = e;

                if (crd) {
                    synchronized (this) {
                        changeGlobalStateExceptions.put(cctx.localNodeId(), e);
                    }
                }
            }
        }
        else {
            if (log.isInfoEnabled()) {
                log.info("Start deactivation process [nodeId=" + cctx.localNodeId() +
                    ", client=" + cctx.kernalContext().clientNode() +
                    ", topVer=" + initialVersion() + "]");
            }

            try {
                cctx.kernalContext().dataStructures().onDeActivate(cctx.kernalContext());

                cctx.kernalContext().service().onDeActivate(cctx.kernalContext());

                cctx.affinity().onCacheChangeRequest(this, crd, exchActions);

                if (log.isInfoEnabled()) {
                    log.info("Successfully deactivated data structures, services and caches [" +
                        "nodeId=" + cctx.localNodeId() +
                        ", client=" + cctx.kernalContext().clientNode() +
                        ", topVer=" + initialVersion() + "]");
                }
            }
            catch (Exception e) {
                U.error(log, "Failed to deactivate node components [nodeId=" + cctx.localNodeId() +
                    ", client=" + cctx.kernalContext().clientNode() +
                    ", topVer=" + initialVersion() + "]", e);

                changeGlobalStateE = e;
            }
        }

        return cctx.kernalContext().clientNode() ? ExchangeType.CLIENT : ExchangeType.ALL;
    }

    /**
     * @param crd Coordinator flag.
     * @return Exchange type.
     * @throws IgniteCheckedException If failed.
     */
    private ExchangeType onCacheChangeRequest(boolean crd) throws IgniteCheckedException {
        assert exchActions != null && !exchActions.empty() : this;

        assert !exchActions.clientOnlyExchange() : exchActions;

        cctx.affinity().onCacheChangeRequest(this, crd, exchActions);

        return cctx.kernalContext().clientNode() ? ExchangeType.CLIENT : ExchangeType.ALL;
    }

    /**
     * @param crd Coordinator flag.
     * @throws IgniteCheckedException If failed.
     * @return Exchange type.
     */
    private ExchangeType onAffinityChangeRequest(boolean crd) throws IgniteCheckedException {
        assert affChangeMsg != null : this;

        cctx.affinity().onChangeAffinityMessage(this, crd, affChangeMsg);

        if (cctx.kernalContext().clientNode())
            return ExchangeType.CLIENT;

        return ExchangeType.ALL;
    }

    /**
     * @param crd Coordinator flag.
     * @throws IgniteCheckedException If failed.
     * @return Exchange type.
     */
    private ExchangeType onClientNodeEvent(boolean crd) throws IgniteCheckedException {
        assert CU.clientNode(discoEvt.eventNode()) : this;

        if (discoEvt.type() == EVT_NODE_LEFT || discoEvt.type() == EVT_NODE_FAILED) {
            onLeft();

            assert !discoEvt.eventNode().isLocal() : discoEvt;
        }
        else
            assert discoEvt.type() == EVT_NODE_JOINED || discoEvt.type() == EVT_DISCOVERY_CUSTOM_EVT : discoEvt;

        cctx.affinity().onClientEvent(this, crd);

        return discoEvt.eventNode().isLocal() ? ExchangeType.CLIENT : ExchangeType.NONE;
    }

    /**
     * @param crd Coordinator flag.
     * @throws IgniteCheckedException If failed.
     * @return Exchange type.
     */
    private ExchangeType onServerNodeEvent(boolean crd) throws IgniteCheckedException {
        assert !CU.clientNode(discoEvt.eventNode()) : this;

        if (discoEvt.type() == EVT_NODE_LEFT || discoEvt.type() == EVT_NODE_FAILED) {
            onLeft();

            warnNoAffinityNodes();

            centralizedAff = cctx.affinity().onServerLeft(this, crd);
        }
        else
            cctx.affinity().onServerJoin(this, crd);

        return cctx.kernalContext().clientNode() ? ExchangeType.CLIENT : ExchangeType.ALL;
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void clientOnlyExchange() throws IgniteCheckedException {
        if (crd != null) {
            assert !crd.isLocal() : crd;

            if (!centralizedAff)
                sendLocalPartitions(crd);

            initDone();

            return;
        }
        else {
            if (centralizedAff) { // Last server node failed.
                for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                    GridAffinityAssignmentCache aff = grp.affinity();

                    aff.initialize(initialVersion(), aff.idealAssignment());
                }
            }
        }

        onDone(initialVersion());
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void distributedExchange() throws IgniteCheckedException {
        assert crd != null;

        assert !cctx.kernalContext().clientNode();

        for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
            if (grp.isLocal())
                continue;

            grp.preloader().onTopologyChanged(this);
        }

        cctx.database().releaseHistoryForPreloading();

        // To correctly rebalance when persistence is enabled, it is necessary to reserve history within exchange.
        partHistReserved = cctx.database().reserveHistoryForExchange();

        waitPartitionRelease();

        boolean topChanged = discoEvt.type() != EVT_DISCOVERY_CUSTOM_EVT || affChangeMsg != null;

        for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
            if (cacheCtx.isLocal() || cacheStopping(cacheCtx.cacheId()))
                continue;

            if (topChanged) {
                // Partition release future is done so we can flush the write-behind store.
                cacheCtx.store().forceFlush();
            }
        }

        if (!exchCtx.mergeExchanges()) {
            for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                if (grp.isLocal() || cacheGroupStopping(grp.groupId()))
                    continue;

                // It is possible affinity is not initialized yet if node joins to cluster.
                if (grp.affinity().lastVersion().topologyVersion() > 0)
                    grp.topology().beforeExchange(this, !centralizedAff);
            }
        }

        cctx.database().beforeExchange(this);

        if (crd.isLocal()) {
            if (remaining.isEmpty())
                onAllReceived();
        }
        else
            sendPartitions(crd);

        initDone();
    }

    /**
     * Try to start local snapshot operation if it is needed by discovery event
     */
    private void tryToPerformLocalSnapshotOperation() {
        try {
            long start = U.currentTimeMillis();

            IgniteInternalFuture fut = cctx.snapshot().tryStartLocalSnapshotOperation(discoEvt);

            if (fut != null) {
                fut.get();

                long end = U.currentTimeMillis();

                if (log.isInfoEnabled())
                    log.info("Snapshot initialization completed [topVer=" + exchangeId().topologyVersion() +
                        ", time=" + (end - start) + "ms]");
            }
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Error while starting snapshot operation", e);
        }
    }

    /**
     * The main purpose of this method is to wait for all ongoing updates (transactional and atomic), initiated on
     * the previous topology version, to finish to prevent inconsistencies during rebalancing and to prevent two
     * different simultaneous owners of the same lock.
     * For the exact list of the objects being awaited for see
     * {@link GridCacheSharedContext#partitionReleaseFuture(AffinityTopologyVersion)} javadoc.
     *
     * @throws IgniteCheckedException If failed.
     */
    private void waitPartitionRelease() throws IgniteCheckedException {
        IgniteInternalFuture<?> partReleaseFut = cctx.partitionReleaseFuture(initialVersion());

        // Assign to class variable so it will be included into toString() method.
        this.partReleaseFut = partReleaseFut;

        if (exchId.isLeft())
            cctx.mvcc().removeExplicitNodeLocks(exchId.nodeId(), exchId.topologyVersion());

        if (log.isDebugEnabled())
            log.debug("Before waiting for partition release future: " + this);

        int dumpCnt = 0;

        long waitStart = U.currentTimeMillis();

        long nextDumpTime = 0;

        long futTimeout = 2 * cctx.gridConfig().getNetworkTimeout();

        while (true) {
            try {
                partReleaseFut.get(futTimeout, TimeUnit.MILLISECONDS);

                break;
            }
            catch (IgniteFutureTimeoutCheckedException ignored) {
                // Print pending transactions and locks that might have led to hang.
                if (nextDumpTime <= U.currentTimeMillis()) {
                    dumpPendingObjects();

                    nextDumpTime = U.currentTimeMillis() + nextDumpTimeout(dumpCnt++, futTimeout);
                }
            }
        }

        long waitEnd = U.currentTimeMillis();

        if (log.isInfoEnabled()) {
            long waitTime = (waitEnd - waitStart);

            String futInfo = RELEASE_FUTURE_DUMP_THRESHOLD > 0 && waitTime > RELEASE_FUTURE_DUMP_THRESHOLD ?
                partReleaseFut.toString() : "NA";

            log.info("Finished waiting for partition release future [topVer=" + exchangeId().topologyVersion() +
                ", waitTime=" + (waitEnd - waitStart) + "ms, futInfo=" + futInfo + "]");
        }

        IgniteInternalFuture<?> locksFut = cctx.mvcc().finishLocks(exchId.topologyVersion());

        nextDumpTime = 0;
        dumpCnt = 0;

        while (true) {
            try {
                locksFut.get(futTimeout, TimeUnit.MILLISECONDS);

                break;
            }
            catch (IgniteFutureTimeoutCheckedException ignored) {
                if (nextDumpTime <= U.currentTimeMillis()) {
                    U.warn(log, "Failed to wait for locks release future. " +
                        "Dumping pending objects that might be the cause: " + cctx.localNodeId());

                    U.warn(log, "Locked keys:");

                    for (IgniteTxKey key : cctx.mvcc().lockedKeys())
                        U.warn(log, "Locked key: " + key);

                    for (IgniteTxKey key : cctx.mvcc().nearLockedKeys())
                        U.warn(log, "Locked near key: " + key);

                    Map<IgniteTxKey, Collection<GridCacheMvccCandidate>> locks =
                        cctx.mvcc().unfinishedLocks(exchId.topologyVersion());

                    for (Map.Entry<IgniteTxKey, Collection<GridCacheMvccCandidate>> e : locks.entrySet())
                        U.warn(log, "Awaited locked entry [key=" + e.getKey() + ", mvcc=" + e.getValue() + ']');

                    nextDumpTime = U.currentTimeMillis() + nextDumpTimeout(dumpCnt++, futTimeout);

                    if (getBoolean(IGNITE_THREAD_DUMP_ON_EXCHANGE_TIMEOUT, false))
                        U.dumpThreads(log);
                }
            }
        }
    }

    /**
     *
     */
    private void onLeft() {
        for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
            if (grp.isLocal())
                continue;

            grp.preloader().unwindUndeploys();
        }

        cctx.mvcc().removeExplicitNodeLocks(exchId.nodeId(), exchId.topologyVersion());
    }

    /**
     *
     */
    private void warnNoAffinityNodes() {
        List<String> cachesWithoutNodes = null;

        for (DynamicCacheDescriptor cacheDesc : cctx.cache().cacheDescriptors().values()) {
            if (discoCache.cacheGroupAffinityNodes(cacheDesc.groupId()).isEmpty()) {
                if (cachesWithoutNodes == null)
                    cachesWithoutNodes = new ArrayList<>();

                cachesWithoutNodes.add(cacheDesc.cacheName());

                // Fire event even if there is no client cache started.
                if (cctx.gridEvents().isRecordable(EventType.EVT_CACHE_NODES_LEFT)) {
                    Event evt = new CacheEvent(
                        cacheDesc.cacheName(),
                        cctx.localNode(),
                        cctx.localNode(),
                        "All server nodes have left the cluster.",
                        EventType.EVT_CACHE_NODES_LEFT,
                        0,
                        false,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        false,
                        null,
                        null,
                        null
                    );

                    cctx.gridEvents().record(evt);
                }
            }
        }

        if (cachesWithoutNodes != null) {
            StringBuilder sb =
                new StringBuilder("All server nodes for the following caches have left the cluster: ");

            for (int i = 0; i < cachesWithoutNodes.size(); i++) {
                String cache = cachesWithoutNodes.get(i);

                sb.append('\'').append(cache).append('\'');

                if (i != cachesWithoutNodes.size() - 1)
                    sb.append(", ");
            }

            U.quietAndWarn(log, sb.toString());

            U.quietAndWarn(log, "Must have server nodes for caches to operate.");
        }
    }

    /**
     *
     */
    private void dumpPendingObjects() {
        U.warn(cctx.kernalContext().cluster().diagnosticLog(),
            "Failed to wait for partition release future [topVer=" + initialVersion() +
            ", node=" + cctx.localNodeId() + "]. Dumping pending objects that might be the cause: ");

        try {
            cctx.exchange().dumpDebugInfo(this);
        }
        catch (Exception e) {
            U.error(cctx.kernalContext().cluster().diagnosticLog(), "Failed to dump debug information: " + e, e);
        }
    }

    /**
     * @param grpId Cache group ID to check.
     * @return {@code True} if cache group us stopping by this exchange.
     */
    private boolean cacheGroupStopping(int grpId) {
        return exchActions != null && exchActions.cacheGroupStopping(grpId);
    }

    /**
     * @param cacheId Cache ID to check.
     * @return {@code True} if cache is stopping by this exchange.
     */
    private boolean cacheStopping(int cacheId) {
        return exchActions != null && exchActions.cacheStopped(cacheId);
    }

    /**
     * @return {@code True} if exchange for local node join.
     */
    boolean localJoinExchange() {
        return discoEvt.type() == EVT_NODE_JOINED && discoEvt.eventNode().isLocal();
    }

    /**
     * @param node Target Node.
     * @throws IgniteCheckedException If failed.
     */
    private void sendLocalPartitions(ClusterNode node) throws IgniteCheckedException {
        assert node != null;

        GridDhtPartitionsSingleMessage msg;

        // Reset lost partition before send local partition to coordinator.
        if (exchActions != null) {
            Set<String> caches = exchActions.cachesToResetLostPartitions();

            if (!F.isEmpty(caches))
                resetLostPartitions(caches);
        }

        if (cctx.kernalContext().clientNode()) {
            msg = new GridDhtPartitionsSingleMessage(exchangeId(),
                true,
                null,
                true);
        }
        else {
            msg = cctx.exchange().createPartitionsSingleMessage(exchangeId(),
                false,
                true);

            Map<Integer, Map<Integer, Long>> partHistReserved0 = partHistReserved;

            if (partHistReserved0 != null)
                msg.partitionHistoryCounters(partHistReserved0);
        }

        if (stateChangeExchange() && changeGlobalStateE != null)
            msg.setError(changeGlobalStateE);
        else if (localJoinExchange())
            msg.cacheGroupsAffinityRequest(exchCtx.groupsAffinityRequestOnJoin());

        if (log.isDebugEnabled())
            log.debug("Sending local partitions [nodeId=" + node.id() + ", exchId=" + exchId + ", msg=" + msg + ']');

        try {
            cctx.io().send(node, msg, SYSTEM_POOL);
        }
        catch (ClusterTopologyCheckedException ignored) {
            if (log.isDebugEnabled())
                log.debug("Node left during partition exchange [nodeId=" + node.id() + ", exchId=" + exchId + ']');
        }
    }

    /**
     * @param compress Message compress flag.
     * @return Message.
     */
    private GridDhtPartitionsFullMessage createPartitionsMessage(boolean compress) {
        GridCacheVersion last = lastVer.get();

        GridDhtPartitionsFullMessage m = cctx.exchange().createPartitionsFullMessage(
            compress,
            exchangeId(),
            last != null ? last : cctx.versions().last(),
            partHistSuppliers,
            partsToReload);

        if (stateChangeExchange() && !F.isEmpty(changeGlobalStateExceptions))
            m.setErrorsMap(changeGlobalStateExceptions);

        return m;
    }

    /**
     * @param nodes Nodes.
     * @param joinedNodeAff Affinity if was requested by some nodes.
     */
    private void sendAllPartitions(
        GridDhtPartitionsFullMessage msg,
        Collection<ClusterNode> nodes,
        Map<UUID, GridDhtPartitionsSingleMessage> mergedJoinExchMsgs,
        Map<Integer, CacheGroupAffinityMessage> joinedNodeAff) {
        boolean singleNode = nodes.size() == 1;

        GridDhtPartitionsFullMessage joinedNodeMsg = null;

        assert !nodes.contains(cctx.localNode());

        if (log.isDebugEnabled()) {
            log.debug("Sending full partition map [nodeIds=" + F.viewReadOnly(nodes, F.node2id()) +
                ", exchId=" + exchId + ", msg=" + msg + ']');
        }

        for (ClusterNode node : nodes) {
            GridDhtPartitionsFullMessage sndMsg = msg;

            if (joinedNodeAff != null) {
                if (singleNode)
                    msg.joinedNodeAffinity(joinedNodeAff);
                else {
                    GridDhtPartitionsSingleMessage singleMsg = msgs.get(node.id());

                    if (singleMsg != null && singleMsg.cacheGroupsAffinityRequest() != null) {
                        if (joinedNodeMsg == null) {
                            joinedNodeMsg = msg.copy();

                            joinedNodeMsg.joinedNodeAffinity(joinedNodeAff);
                        }

                        sndMsg = joinedNodeMsg;
                    }
                }
            }

            try {
                GridDhtPartitionExchangeId sndExchId = exchangeId();

                if (mergedJoinExchMsgs != null) {
                    GridDhtPartitionsSingleMessage mergedMsg = mergedJoinExchMsgs.get(node.id());

                    if (mergedMsg != null)
                        sndExchId = mergedMsg.exchangeId();
                }

                if (sndExchId != null && !sndExchId.equals(exchangeId())) {
                    sndMsg = sndMsg.copy();

                    sndMsg.exchangeId(sndExchId);
                }

                cctx.io().send(node, sndMsg, SYSTEM_POOL);
            }
            catch (ClusterTopologyCheckedException e) {
                if (log.isDebugEnabled())
                    log.debug("Failed to send partitions, node failed: " + node);
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to send partitions [node=" + node + ']', e);
            }
        }
    }

    /**
     * @param oldestNode Oldest node. Target node to send message to.
     */
    private void sendPartitions(ClusterNode oldestNode) {
        try {
            sendLocalPartitions(oldestNode);
        }
        catch (ClusterTopologyCheckedException ignore) {
            if (log.isDebugEnabled())
                log.debug("Oldest node left during partition exchange [nodeId=" + oldestNode.id() +
                    ", exchId=" + exchId + ']');
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send local partitions to oldest node (will retry after timeout) [oldestNodeId=" +
                oldestNode.id() + ", exchId=" + exchId + ']', e);
        }
    }

    /**
     * @return {@code True} if exchange triggered by server node join or fail.
     */
    public boolean serverNodeDiscoveryEvent() {
        assert exchCtx != null;

        return exchCtx.events().serverJoin() || exchCtx.events().serverLeft();
    }

    /** {@inheritDoc} */
    @Override public boolean onDone(@Nullable AffinityTopologyVersion res, @Nullable Throwable err) {
        if (!done.compareAndSet(false, true))
            return false;

        log.info("Finish exchange future [startVer=" + initialVersion() +
            ", resVer=" + res +
            ", err=" + err + ']');

        assert res != null || err != null;

        if (err == null &&
            !cctx.kernalContext().clientNode() &&
            (serverNodeDiscoveryEvent() || affChangeMsg != null)) {
            for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
                if (!cacheCtx.affinityNode() || cacheCtx.isLocal())
                    continue;

                cacheCtx.continuousQueries().flushBackupQueue(res);
            }
       }

        if (err == null) {
            if (centralizedAff) {
                assert !exchCtx.mergeExchanges();

                for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                    if (grp.isLocal())
                        continue;

                    try {
                        grp.topology().initPartitionsWhenAffinityReady(res, this);
                    }
                    catch (IgniteInterruptedCheckedException e) {
                        U.error(log, "Failed to initialize partitions.", e);
                    }
                }
            }

            for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
                GridCacheContext drCacheCtx = cacheCtx.isNear() ? cacheCtx.near().dht().context() : cacheCtx;

                if (drCacheCtx.isDrEnabled()) {
                    try {
                        drCacheCtx.dr().onExchange(res, exchId.isLeft());
                    }
                    catch (IgniteCheckedException e) {
                        U.error(log, "Failed to notify DR: " + e, e);
                    }
                }
            }

            if (serverNodeDiscoveryEvent())
                detectLostPartitions(res);

            Map<Integer, CacheValidation> m = U.newHashMap(cctx.cache().cacheGroups().size());

            for (CacheGroupContext grp : cctx.cache().cacheGroups())
                m.put(grp.groupId(), validateCacheGroup(grp, discoEvt.topologyNodes()));

            grpValidRes = m;
        }

        tryToPerformLocalSnapshotOperation();

        cctx.cache().onExchangeDone(initialVersion(), exchActions, err);

        cctx.exchange().onExchangeDone(res, initialVersion(), err);

        if (exchActions != null && err == null)
            exchActions.completeRequestFutures(cctx);

        if (stateChangeExchange() && err == null)
            cctx.kernalContext().state().onStateChangeExchangeDone(exchActions.stateChangeRequest());

        Map<T2<Integer, Integer>, Long> localReserved = partHistSuppliers.getReservations(cctx.localNodeId());

        if (localReserved != null) {
            for (Map.Entry<T2<Integer, Integer>, Long> e : localReserved.entrySet()) {
                boolean success = cctx.database().reserveHistoryForPreloading(
                    e.getKey().get1(), e.getKey().get2(), e.getValue());

                if (!success) {
                    // TODO: how to handle?
                    err = new IgniteCheckedException("Could not reserve history");
                }
            }
        }

        cctx.database().releaseHistoryForExchange();

        if (err == null) {
            for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                if (!grp.isLocal())
                    grp.topology().onExchangeDone(grp.affinity().readyAffinity(res), false);
            }
        }

        if (super.onDone(res, err)) {
            if (log.isDebugEnabled())
                log.debug("Completed partition exchange [localNode=" + cctx.localNodeId() + ", exchange= " + this +
                    ", durationFromInit=" + (U.currentTimeMillis() - initTs) + ']');

            initFut.onDone(err == null);

            if (exchId.isLeft()) {
                for (CacheGroupContext grp : cctx.cache().cacheGroups())
                    grp.affinityFunction().removeNode(exchId.nodeId());
            }

            exchActions = null;

            if (discoEvt instanceof DiscoveryCustomEvent)
                ((DiscoveryCustomEvent)discoEvt).customMessage(null);

            if (err == null)
                cctx.exchange().lastFinishedFuture(this);

            return true;
        }

        return false;
    }

    /**
     * Cleans up resources to avoid excessive memory usage.
     */
    public void cleanUp() {
        pendingSingleMsgs.clear();
        fullMsgs.clear();
        msgs.clear();
        changeGlobalStateExceptions.clear();
        crd = null;
        partReleaseFut = null;
        changeGlobalStateE = null;
        exchActions = null;
    }

    /**
     * @param ver Version.
     */
    private void updateLastVersion(GridCacheVersion ver) {
        assert ver != null;

        while (true) {
            GridCacheVersion old = lastVer.get();

            if (old == null || Long.compare(old.order(), ver.order()) < 0) {
                if (lastVer.compareAndSet(old, ver))
                    break;
            }
            else
                break;
        }
    }

    private boolean addMergedJoinExchange(ClusterNode node, @Nullable GridDhtPartitionsSingleMessage msg) {
        assert Thread.holdsLock(this);
        assert node != null;
        assert state == ExchangeLocalState.CRD : state;

        UUID nodeId = node.id();

        boolean wait = false;

        if (CU.clientNode(node)) {
            if (msg != null)
                waitAndReplyToClient(nodeId, msg);
        }
        else {
            if (mergedJoinExchMsgs == null)
                mergedJoinExchMsgs = new LinkedHashMap<>();

            if (msg != null) {
                log.info("Merge server join exchange, message received [curFut=" + initialVersion() +
                    ", node=" + nodeId + ']');

                mergedJoinExchMsgs.put(nodeId, msg);
            }
            else {
                if (cctx.discovery().alive(nodeId)) {
                    log.info("Merge server join exchange, wait for message [curFut=" + initialVersion() +
                        ", node=" + nodeId + ']');

                    wait = true;

                    mergedJoinExchMsgs.put(nodeId, null);

                    awaitMergedMsgs++;
                }
                else {
                    log.info("Merge server join exchange, awaited node left [curFut=" + initialVersion() +
                        ", node=" + nodeId + ']');
                }
            }
        }

        return wait;
    }

    /**
     * @param fut Current exchange to merge with.
     * @return {@code True} if need wait for message from joined server node.
     */
    public boolean mergeJoinExchange(GridDhtPartitionsExchangeFuture fut) {
        boolean wait;

        synchronized (this) {
            assert !isDone() && !initFut.isDone() : this;
            assert mergedWith == null && state == null : this;

            state = ExchangeLocalState.MERGED;

            mergedWith = fut;

            ClusterNode joinedNode = discoEvt.eventNode();

            wait = fut.addMergedJoinExchange(joinedNode, pendingJoinMsg);
        }

        return wait;
    }

    @Nullable public GridDhtPartitionsSingleMessage mergeJoinExchangeOnDone(GridDhtPartitionsExchangeFuture fut) {
        synchronized (this) {
            assert !isDone();
            assert !initFut.isDone();
            assert mergedWith == null;
            assert state == null;

            state = ExchangeLocalState.MERGED;

            mergedWith = fut;

            return pendingJoinMsg;
        }
    }

    /**
     * @param node
     * @param msg
     */
    void processMergedMessage(final ClusterNode node, final GridDhtPartitionsSingleMessage msg) {
        if (msg.client()) {
            waitAndReplyToClient(node.id(), msg);

            return;
        }

        boolean done = false;

        FinishState finishState0 = null;

        synchronized (this) {
            if (state == ExchangeLocalState.DONE) {
                assert finishState != null;

                finishState0 = finishState;
            }
            else {
                boolean process = mergedJoinExchMsgs != null &&
                    mergedJoinExchMsgs.containsKey(node.id()) &&
                    mergedJoinExchMsgs.get(node.id()) == null;

                log.info("Merge server join exchange, received message [curFut=" + initialVersion() +
                    ", node=" + node.id() +
                    ", msgVer=" + msg.exchangeId().topologyVersion() +
                    ", process=" + process +
                    ", awaited=" + awaitMergedMsgs + ']');

                if (process) {
                    mergedJoinExchMsgs.put(node.id(), msg);

                    assert awaitMergedMsgs > 0 : awaitMergedMsgs;

                    awaitMergedMsgs--;

                    done = awaitMergedMsgs == 0;
                }
            }
        }

        if (finishState0 != null) {
            sendAllPartitionsToNode(finishState0, msg, node.id());

            return;
        }

        if (done)
            finishExchangeOnCoordinator();
    }

    /**
     * Processing of received single message. Actual processing in future may be delayed if init method was not
     * completed, see {@link #initDone()}
     *
     * @param node Sender node.
     * @param msg Single partition info.
     */
    public void onReceiveSingleMessage(final ClusterNode node, final GridDhtPartitionsSingleMessage msg) {
        assert !node.isDaemon() : node;
        assert msg != null;
        assert exchId.equals(msg.exchangeId()) : msg;
        assert !cctx.kernalContext().clientNode();

        if (msg.restoreState()) {
            InitNewCoordinatorFuture newCrdFut0;

            synchronized (this) {
                assert newCrdFut != null;

                newCrdFut0 = newCrdFut;
            }

            newCrdFut0.onMessage(node, msg);

            return;
        }

        if (!msg.client()) {
            assert msg.lastVersion() != null : msg;

            updateLastVersion(msg.lastVersion());
        }

        GridDhtPartitionsExchangeFuture mergedWith0 = null;

        synchronized (this) {
            if (state == ExchangeLocalState.MERGED) {
                assert mergedWith != null;

                mergedWith0 = mergedWith;
            }
            else {
                assert state != ExchangeLocalState.CLIENT;

                if (exchangeId().isJoined() && node.id().equals(exchId.nodeId()))
                    pendingJoinMsg = msg;
            }
        }

        if (mergedWith0 != null) {
            mergedWith0.processMergedMessage(node, msg);

            return;
        }

        initFut.listen(new CI1<IgniteInternalFuture<Boolean>>() {
            @Override public void apply(IgniteInternalFuture<Boolean> f) {
                try {
                    if (!f.get())
                        return;
                }
                catch (IgniteCheckedException e) {
                    U.error(log, "Failed to initialize exchange future: " + this, e);

                    return;
                }

                processSingleMessage(node.id(), msg);
            }
        });
    }

    public void waitAndReplyToNode(final ClusterNode node, final GridDhtPartitionsSingleMessage msg) {
        listen(new CI1<IgniteInternalFuture<AffinityTopologyVersion>>() {
            @Override public void apply(IgniteInternalFuture<AffinityTopologyVersion> fut) {
                FinishState finishState0;

                synchronized (GridDhtPartitionsExchangeFuture.this) {
                    finishState0 = finishState;
                }

                assert finishState0 != null;

                sendAllPartitionsToNode(finishState0, msg, node.id());
            }
        });
    }

    /**
     * Note this method performs heavy updatePartitionSingleMap operation, this operation is moved out from the
     * synchronized block. Only count of such updates {@link #pendingSingleUpdates} is managed under critical section.
     *
     * @param nodeId Node ID.
     * @param msg Client's message.
     */
    private void waitAndReplyToClient(final UUID nodeId, final GridDhtPartitionsSingleMessage msg) {
        assert msg.client();

        listen(new CI1<IgniteInternalFuture<AffinityTopologyVersion>>() {
            @Override public void apply(IgniteInternalFuture<AffinityTopologyVersion> fut) {
                if (cctx.kernalContext().isStopping())
                    return;

                FinishState finishState0;

                synchronized (GridDhtPartitionsExchangeFuture.this) {
                    finishState0 = finishState;
                }

                if (finishState0 == null) {
                    assert discoEvt.type() == EVT_NODE_JOINED && CU.clientNode(discoEvt.eventNode()) : this;

                    finishState0 = new FinishState(cctx.localNodeId(),
                        initialVersion(),
                        createPartitionsMessage(false));
                }

                sendAllPartitionsToNode(finishState0, msg, nodeId);
            }
        });
    }

    /**
     * @param nodeId Sender node.
     * @param msg Partition single message.
     */
    void processSingleMessage(UUID nodeId, GridDhtPartitionsSingleMessage msg) {
        if (msg.client()) {
            waitAndReplyToClient(nodeId, msg);

            return;
        }

        boolean allReceived = false; // Received all expected messages.
        boolean updateSingleMap = false;

        FinishState finishState0 = null;

        synchronized (this) {
            assert crd != null;

            switch (state) {
                case DONE: {
                    log.info("Received single message, already done [ver=" + initialVersion() +
                        ", node=" + nodeId + ']');

                    assert finishState != null;

                    finishState0 = finishState;

                    break;
                }

                case CRD: {
                    assert crd.isLocal() : crd;

                    if (remaining.remove(nodeId)) {
                        updateSingleMap = true;

                        pendingSingleUpdates++;

                        if (stateChangeExchange() && msg.getError() != null)
                            changeGlobalStateExceptions.put(nodeId, msg.getError());

                        allReceived = remaining.isEmpty();

                        log.info("Coordinator received single message [ver=" + initialVersion() +
                            ", node=" + nodeId +
                            ", allReceived=" + allReceived + ']');
                    }

                    break;
                }

                case SRV:
                case BECOME_CRD: {
                    log.info("Non-coordinator received single message [ver=" + initialVersion() +
                        ", node=" + nodeId + ", state=" + state + ']');

                    pendingSingleMsgs.put(nodeId, msg);

                    break;
                }

                default:
                    assert false : state;
            }
        }

        if (finishState0 != null) {
            sendAllPartitionsToNode(finishState0, msg, nodeId);

            return;
        }

        if (updateSingleMap) {
            try {
                // Do not update partition map, in case cluster transitioning to inactive state.
                if (!deactivateCluster())
                    updatePartitionSingleMap(nodeId, msg);
            }
            finally {
                synchronized (this) {
                    assert pendingSingleUpdates > 0;

                    pendingSingleUpdates--;

                    if (pendingSingleUpdates == 0)
                        notifyAll();
                }
            }
        }

        if (allReceived) {
            if (!awaitSingleMapUpdates())
                return;

            onAllReceived();
        }
    }

    /**
     * @return {@code False} if interrupted.
     */
    private synchronized boolean awaitSingleMapUpdates() {
        try {
            while (pendingSingleUpdates > 0)
                U.wait(this);

            return true;
        }
        catch (IgniteInterruptedCheckedException e) {
            U.warn(log, "Failed to wait for partition map updates, thread was interrupted: " + e);

            return false;
        }
    }

    /**
     * @param fut Affinity future.
     */
    private void onAffinityInitialized(IgniteInternalFuture<Map<Integer, Map<Integer, List<UUID>>>> fut) {
        try {
            assert fut.isDone();

            Map<Integer, Map<Integer, List<UUID>>> assignmentChange = fut.get();

            GridDhtPartitionsFullMessage m = createPartitionsMessage(false);

            CacheAffinityChangeMessage msg = new CacheAffinityChangeMessage(exchId, m, assignmentChange);

            if (log.isDebugEnabled())
                log.debug("Centralized affinity exchange, send affinity change message: " + msg);

            cctx.discovery().sendCustomEvent(msg);
        }
        catch (IgniteCheckedException e) {
            onDone(e);
        }
    }

    /**
     * @param top Topology to assign.
     */
    private void assignPartitionStates(GridDhtPartitionTopology top) {
        Map<Integer, CounterWithNodes> maxCntrs = new HashMap<>();
        Map<Integer, Long> minCntrs = new HashMap<>();

        for (Map.Entry<UUID, GridDhtPartitionsSingleMessage> e : msgs.entrySet()) {
            assert e.getValue().partitionUpdateCounters(top.groupId()) != null;

            for (Map.Entry<Integer, T2<Long, Long>> e0 : e.getValue().partitionUpdateCounters(top.groupId()).entrySet()) {
                int p = e0.getKey();

                UUID uuid = e.getKey();

                GridDhtPartitionState state = top.partitionState(uuid, p);

                if (state != GridDhtPartitionState.OWNING && state != GridDhtPartitionState.MOVING)
                    continue;

                Long cntr = state == GridDhtPartitionState.MOVING ? e0.getValue().get1() : e0.getValue().get2();

                if (cntr == null)
                    cntr = 0L;

                Long minCntr = minCntrs.get(p);

                if (minCntr == null || minCntr > cntr)
                    minCntrs.put(p, cntr);

                if (state != GridDhtPartitionState.OWNING)
                    continue;

                CounterWithNodes maxCntr = maxCntrs.get(p);

                if (maxCntr == null || cntr > maxCntr.cnt)
                    maxCntrs.put(p, new CounterWithNodes(cntr, uuid));
                else if (cntr == maxCntr.cnt)
                    maxCntr.nodes.add(uuid);
            }
        }

        // Also must process counters from the local node.
        for (GridDhtLocalPartition part : top.currentLocalPartitions()) {
            GridDhtPartitionState state = top.partitionState(cctx.localNodeId(), part.id());

            if (state != GridDhtPartitionState.OWNING && state != GridDhtPartitionState.MOVING)
                continue;

            long cntr = state == GridDhtPartitionState.MOVING ? part.initialUpdateCounter() : part.updateCounter();

            Long minCntr = minCntrs.get(part.id());

            if (minCntr == null || minCntr > cntr)
                minCntrs.put(part.id(), cntr);

            if (state != GridDhtPartitionState.OWNING)
                continue;

            CounterWithNodes maxCntr = maxCntrs.get(part.id());

            if (maxCntr == null && cntr == 0) {
                CounterWithNodes cntrObj = new CounterWithNodes(cntr, cctx.localNodeId());

                for (UUID nodeId : msgs.keySet()) {
                    if (top.partitionState(nodeId, part.id()) == GridDhtPartitionState.OWNING)
                        cntrObj.nodes.add(nodeId);
                }

                maxCntrs.put(part.id(), cntrObj);
            }
            else if (maxCntr == null || cntr > maxCntr.cnt)
                maxCntrs.put(part.id(), new CounterWithNodes(cntr, cctx.localNodeId()));
            else if (cntr == maxCntr.cnt)
                maxCntr.nodes.add(cctx.localNodeId());
        }

        int entryLeft = maxCntrs.size();

        Map<Integer, Map<Integer, Long>> partHistReserved0 = partHistReserved;

        Map<Integer, Long> localReserved = partHistReserved0 != null ? partHistReserved0.get(top.groupId()) : null;

        Set<Integer> haveHistory = new HashSet<>();

        for (Map.Entry<Integer, Long> e : minCntrs.entrySet()) {
            int p = e.getKey();
            long minCntr = e.getValue();

            CounterWithNodes maxCntrObj = maxCntrs.get(p);

            long maxCntr = maxCntrObj != null ? maxCntrObj.cnt : 0;

            // If minimal counter is zero, do clean preloading.
            if (minCntr == 0 || minCntr == maxCntr)
                continue;

            if (localReserved != null) {
                Long localCntr = localReserved.get(p);

                if (localCntr != null && localCntr <= minCntr &&
                    maxCntrObj.nodes.contains(cctx.localNodeId())) {
                    partHistSuppliers.put(cctx.localNodeId(), top.groupId(), p, minCntr);

                    haveHistory.add(p);

                    continue;
                }
            }

            for (Map.Entry<UUID, GridDhtPartitionsSingleMessage> e0 : msgs.entrySet()) {
                Long histCntr = e0.getValue().partitionHistoryCounters(top.groupId()).get(p);

                if (histCntr != null && histCntr <= minCntr && maxCntrObj.nodes.contains(e0.getKey())) {
                    partHistSuppliers.put(e0.getKey(), top.groupId(), p, minCntr);

                    haveHistory.add(p);

                    break;
                }
            }
        }

        for (Map.Entry<Integer, CounterWithNodes> e : maxCntrs.entrySet()) {
            int p = e.getKey();
            long maxCntr = e.getValue().cnt;

            entryLeft--;

            if (entryLeft != 0 && maxCntr == 0)
                continue;

            Set<UUID> nodesToReload = top.setOwners(p, e.getValue().nodes, haveHistory.contains(p), entryLeft == 0);

            for (UUID nodeId : nodesToReload)
                partsToReload.put(nodeId, top.groupId(), p);
        }
    }

    /**
     * Detect lost partitions.
     *
     * @param resTopVer Result topology version.
     */
    private void detectLostPartitions(AffinityTopologyVersion resTopVer) {
        boolean detected = false;

        synchronized (cctx.exchange().interruptLock()) {
            if (Thread.currentThread().isInterrupted())
                return;

            for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                if (!grp.isLocal()) {
                    boolean detectedOnGrp = grp.topology().detectLostPartitions(resTopVer, discoEvt);

                    detected |= detectedOnGrp;
                }
            }
        }

        if (detected)
            cctx.exchange().scheduleResendPartitions();
    }

    /**
     * @param cacheNames Cache names.
     */
    private void resetLostPartitions(Collection<String> cacheNames) {
        assert !exchCtx.mergeExchanges();

        synchronized (cctx.exchange().interruptLock()) {
            if (Thread.currentThread().isInterrupted())
                return;

            for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                if (grp.isLocal())
                    continue;

                for (String cacheName : cacheNames) {
                    if (grp.hasCache(cacheName)) {
                        grp.topology().resetLostPartitions(initialVersion());

                        break;
                    }
                }
            }
        }
    }

    /**
     *
     */
    private void onAllReceived() {
        try {
            assert crd.isLocal();

            assert partHistSuppliers.isEmpty() : partHistSuppliers;

            if (!crd.equals(discoCache.serverNodes().get(0)) && !exchCtx.mergeExchanges()) {
                for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                    if (!grp.isLocal())
                        grp.topology().beforeExchange(this, !centralizedAff);
                }
            }

            if (exchCtx.mergeExchanges()) {
                log.info("Coordinator received all messages, try merge [ver=" + initialVersion() + ']');

                boolean finish = cctx.exchange().mergeExchangesOnCoordinator(this);

                if (!finish)
                    return;
            }

            finishExchangeOnCoordinator();
        }
        catch (IgniteCheckedException e) {
            if (reconnectOnError(e))
                onDone(new IgniteNeedReconnectException(cctx.localNode(), e));
            else
                onDone(e);
        }
    }

    /**
     *
     */
    private void finishExchangeOnCoordinator() {
        try {
            AffinityTopologyVersion resTopVer = exchCtx.events().topologyVersion();

            log.info("finishExchangeOnCoordinator [topVer=" + initialVersion() +
                ", resVer=" + resTopVer + ']');

            Map<Integer, CacheGroupAffinityMessage> idealAffDiff = null;

            if (exchCtx.mergeExchanges()) {
                assert exchCtx.events().serverJoin() || exchCtx.events().serverLeft();

                if (exchCtx.events().serverLeft())
                    idealAffDiff = cctx.affinity().mergeExchangesInitAffinityOnServerLeft(this);
                else
                    cctx.affinity().mergeExchangesOnServerJoin(this, true);

                for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                    if (grp.isLocal() || cacheGroupStopping(grp.groupId()))
                        continue;

                    grp.topology().beforeExchange(this, true);
                }

                synchronized (this) {
                    if (mergedJoinExchMsgs != null) {
                        for (Map.Entry<UUID, GridDhtPartitionsSingleMessage> e : mergedJoinExchMsgs.entrySet()) {
                            msgs.put(e.getKey(), e.getValue());

                            updatePartitionSingleMap(e.getKey(), e.getValue());
                        }
                    }
                }
            }

            Map<Integer, CacheGroupAffinityMessage> joinedNodeAff = null;

            for (Map.Entry<UUID, GridDhtPartitionsSingleMessage> e : msgs.entrySet()) {
                GridDhtPartitionsSingleMessage msg = e.getValue();

                // Apply update counters after all single messages are received.
                for (Map.Entry<Integer, GridDhtPartitionMap> entry : msg.partitions().entrySet()) {
                    Integer grpId = entry.getKey();

                    CacheGroupContext grp = cctx.cache().cacheGroup(grpId);

                    GridDhtPartitionTopology top = grp != null ? grp.topology() :
                        cctx.exchange().clientTopology(grpId, this);

                    Map<Integer, T2<Long, Long>> cntrs = msg.partitionUpdateCounters(grpId);

                    if (cntrs != null)
                        top.applyUpdateCounters(cntrs);
                }

                Collection<Integer> affReq = msg.cacheGroupsAffinityRequest();

                if (affReq != null) {
                    joinedNodeAff = CacheGroupAffinityMessage.createAffinityMessages(cctx,
                        resTopVer,
                        affReq,
                        joinedNodeAff);

                    UUID nodeId = e.getKey();

                    // If node requested affinity on join and partitions are not created, then
                    // all affinity partitions should be in MOVING state.
                    for (Integer grpId : affReq) {
                        GridDhtPartitionMap partMap = msg.partitions().get(grpId);

                        if (partMap == null || F.isEmpty(partMap.map())) {
                            CacheGroupContext grp = cctx.cache().cacheGroup(grpId);

                            GridDhtPartitionTopology top = grp != null ? grp.topology() :
                                cctx.exchange().clientTopology(grpId, this);

                            if (partMap == null) {
                                partMap = new GridDhtPartitionMap(nodeId,
                                    1L,
                                    resTopVer,
                                    new GridPartitionStateMap(),
                                    false);
                            }

                            AffinityAssignment aff = cctx.affinity().affinity(grpId).cachedAffinity(resTopVer);

                            for (int p = 0; p < aff.assignment().size(); p++) {
                                if (aff.getIds(p).contains(nodeId))
                                    partMap.put(p, GridDhtPartitionState.MOVING);
                            }

                            top.update(exchId, partMap, true);
                        }
                    }
                }
            }

            if (discoEvt.type() == EVT_DISCOVERY_CUSTOM_EVT) {
                assert discoEvt instanceof DiscoveryCustomEvent;

                if (activateCluster())
                    assignPartitionsStates();

                if (((DiscoveryCustomEvent)discoEvt).customMessage() instanceof DynamicCacheChangeBatch) {
                    if (exchActions != null) {
                        Set<String> caches = exchActions.cachesToResetLostPartitions();

                        if (!F.isEmpty(caches))
                            resetLostPartitions(caches);
                    }
                }
            }
            else {
                if (exchCtx.events().serverJoin())
                    assignPartitionsStates();

                if (exchCtx.events().serverLeft())
                    detectLostPartitions(resTopVer);
            }

            updateLastVersion(cctx.versions().last());

            cctx.versions().onExchange(lastVer.get().order());

            GridDhtPartitionsFullMessage msg = createPartitionsMessage(true);

            if (exchCtx.mergeExchanges()) {
                assert !centralizedAff;

                msg.resultTopologyVersion(exchCtx.events().topologyVersion());

                if (exchCtx.events().serverLeft())
                    msg.idealAffinityDiff(idealAffDiff);
            }

            msg.prepareMarshal(cctx);

            synchronized (this) {
                finishState = new FinishState(crd.id(), exchCtx.events().topologyVersion(), msg);

                state = ExchangeLocalState.DONE;
            }

            if (centralizedAff) {
                assert !exchCtx.mergeExchanges();

                IgniteInternalFuture<Map<Integer, Map<Integer, List<UUID>>>> fut = cctx.affinity().initAffinityOnNodeLeft(this);

                if (!fut.isDone()) {
                    fut.listen(new IgniteInClosure<IgniteInternalFuture<Map<Integer, Map<Integer, List<UUID>>>>>() {
                        @Override public void apply(IgniteInternalFuture<Map<Integer, Map<Integer, List<UUID>>>> fut) {
                            onAffinityInitialized(fut);
                        }
                    });
                }
                else
                    onAffinityInitialized(fut);
            }
            else {
                List<ClusterNode> nodes;

                Map<UUID, GridDhtPartitionsSingleMessage> mergedJoinExchMsgs0;

                synchronized (this) {
                    srvNodes.remove(cctx.localNode());

                    nodes = new ArrayList<>(srvNodes);

                    mergedJoinExchMsgs0 = mergedJoinExchMsgs;

                    if (mergedJoinExchMsgs != null) {
                        for (Map.Entry<UUID, GridDhtPartitionsSingleMessage> e : mergedJoinExchMsgs.entrySet()) {
                            if (e.getValue() != null) {
                                ClusterNode node = cctx.discovery().node(e.getKey());

                                if (node != null)
                                    nodes.add(node);
                            }
                        }
                    }
                }

                IgniteCheckedException err = null;

                if (stateChangeExchange()) {
                    StateChangeRequest req = exchActions.stateChangeRequest();

                    assert req != null : exchActions;

                    boolean stateChangeErr = false;

                    if (!F.isEmpty(changeGlobalStateExceptions)) {
                        stateChangeErr = true;

                        err = new IgniteCheckedException("Cluster state change failed.");

                        cctx.kernalContext().state().onStateChangeError(changeGlobalStateExceptions, req);
                    }

                    boolean active = !stateChangeErr && req.activate();

                    ChangeGlobalStateFinishMessage stateFinishMsg = new ChangeGlobalStateFinishMessage(
                        req.requestId(),
                        active);

                    cctx.discovery().sendCustomEvent(stateFinishMsg);
                }

                if (!nodes.isEmpty())
                    sendAllPartitions(msg, nodes, mergedJoinExchMsgs0, joinedNodeAff);

                onDone(exchCtx.events().topologyVersion(), err);

                for (Map.Entry<UUID, GridDhtPartitionsSingleMessage> e : pendingSingleMsgs.entrySet())
                    processSingleMessage(e.getKey(), e.getValue());
            }
        }
        catch (IgniteCheckedException e) {
            if (reconnectOnError(e))
                onDone(new IgniteNeedReconnectException(cctx.localNode(), e));
            else
                onDone(e);
        }
    }

    /**
     *
     */
    private void assignPartitionsStates() {
        if (cctx.database().persistenceEnabled()) {
            for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                if (grp.isLocal())
                    continue;

                assignPartitionStates(grp.topology());
            }
        }
    }

    /**
     * @param finishState State.
     * @param msg Request.
     * @param nodeId Node ID.
     */
    private void sendAllPartitionsToNode(FinishState finishState, GridDhtPartitionsSingleMessage msg, UUID nodeId) {
        ClusterNode node = cctx.node(nodeId);

        if (node != null) {
            GridDhtPartitionsFullMessage fullMsg = finishState.msg.copy();
            fullMsg.exchangeId(msg.exchangeId());

            Collection<Integer> affReq = msg.cacheGroupsAffinityRequest();

            if (affReq != null) {
                Map<Integer, CacheGroupAffinityMessage> aff = CacheGroupAffinityMessage.createAffinityMessages(
                    cctx,
                    finishState.resTopVer,
                    affReq,
                    null);

                fullMsg.joinedNodeAffinity(aff);
            }

            if (!fullMsg.exchangeId().equals(msg.exchangeId()))
                fullMsg.exchangeId(msg.exchangeId());

            try {
                cctx.io().send(node, fullMsg, SYSTEM_POOL);
            }
            catch (ClusterTopologyCheckedException e) {
                if (log.isDebugEnabled())
                    log.debug("Failed to send partitions, node failed: " + node);
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to send partitions [node=" + node + ']', e);
            }
        }
        else if (log.isDebugEnabled())
            log.debug("Failed to send partitions, node failed: " + nodeId);

    }

    /**
     * @param node Sender node.
     * @param msg Full partition info.
     */
    public void onReceiveFullMessage(final ClusterNode node, final GridDhtPartitionsFullMessage msg) {
        assert msg != null;
        assert msg.exchangeId() != null : msg;
        assert !node.isDaemon() : node;

        initFut.listen(new CI1<IgniteInternalFuture<Boolean>>() {
            @Override public void apply(IgniteInternalFuture<Boolean> f) {
                try {
                    if (!f.get())
                        return;
                }
                catch (IgniteCheckedException e) {
                    U.error(log, "Failed to initialize exchange future: " + this, e);

                    return;
                }

                processFullMessage(true, node, msg);
            }
        });
    }

    /**
     * @param node Sender node.
     * @param msg Message with full partition info.
     */
    public void onReceivePartitionRequest(final ClusterNode node, final GridDhtPartitionsSingleRequest msg) {
        assert !cctx.kernalContext().clientNode() || msg.restoreState();
        assert !node.isDaemon() && !CU.clientNode(node) : node;

        initFut.listen(new CI1<IgniteInternalFuture<Boolean>>() {
            @Override public void apply(IgniteInternalFuture<Boolean> fut) {
                processSinglePartitionRequest(node, msg);
            }
        });
    }

    /**
     * @param node Sender node.
     * @param msg Message.
     */
    private void processSinglePartitionRequest(ClusterNode node, GridDhtPartitionsSingleRequest msg) {
        FinishState finishState0 = null;

        synchronized (this) {
            if (crd == null) {
                log.info("Ignore partitions request, no coordinator [node=" + node.id() + ']');

                return;
            }

            switch (state) {
                case DONE: {
                    assert finishState != null;

                    if (node.id().equals(finishState.crdId)) {
                        log.info("Ignore partitions request, finished exchange with this coordinator: " + msg);

                        return;
                    }

                    finishState0 = finishState;

                    break;
                }

                case CRD:
                case BECOME_CRD: {
                    log.info("Ignore partitions request, node is coordinator: " + msg);

                    return;
                }

                case CLIENT:
                case SRV: {
                    if (!cctx.discovery().alive(node)) {
                        log.info("Ignore restore state request, node is not alive [node=" + node.id() + ']');

                        return;
                    }

                    if (msg.restoreState()) {
                        if (!node.equals(crd)) {
                            if (node.order() > crd.order()) {
                                log.info("Received restore state request, change coordinator [oldCrd=" + crd.id() +
                                    "newCrd=" + node.id() + ']');

                                crd = node; // Do not allow to process FullMessage from old coordinator.
                            }
                            else {
                                log.info("Ignore restore state request, coordinator changed [oldCrd=" + crd.id() +
                                    "newCrd=" + node.id() + ']');

                                return;
                            }
                        }
                    }

                    break;
                }

                default:
                    assert false : state;
            }
        }

        if (msg.restoreState()) {
            try {
                assert msg.restoreExchangeId() != null : msg;

                GridDhtPartitionsSingleMessage res = cctx.exchange().createPartitionsSingleMessage(
                    msg.restoreExchangeId(),
                    cctx.kernalContext().clientNode(),
                    true);

                if (localJoinExchange() && finishState0 == null)
                    res.cacheGroupsAffinityRequest(exchCtx.groupsAffinityRequestOnJoin());

                res.restoreState(true);

                res.finishMessage(finishState0 != null ? finishState0.msg : null);

                cctx.io().send(node, res, SYSTEM_POOL);
            }
            catch (ClusterTopologyCheckedException ignored) {
                if (log.isDebugEnabled())
                    log.debug("Node left during partition exchange [nodeId=" + node.id() + ", exchId=" + exchId + ']');
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to send partitions message [node=" + node + ", msg=" + msg + ']', e);
            }

            return;
        }

        try {
            sendLocalPartitions(node);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send message to coordinator: " + e);
        }
    }

    /**
     * @param node Sender node.
     * @param msg Message.
     */
    private void processFullMessage(boolean checkCrd, ClusterNode node, GridDhtPartitionsFullMessage msg) {
        try {
            assert exchId.equals(msg.exchangeId()) : msg;
            assert msg.lastVersion() != null : msg;

            if (checkCrd) {
                assert node != null;

                synchronized (this) {
                    if (crd == null) {
                        log.info("Ignore full message, all server nodes left: " + msg);

                        return;
                    }

                    switch (state) {
                        case CRD:
                        case BECOME_CRD: {
                            log.info("Ignore full message, node is coordinator: " + msg);

                            return;
                        }

                        case DONE: {
                            log.info("Ignore full message, future is done: " + msg);

                            return;
                        }

                        case SRV:
                        case CLIENT: {
                            if (!crd.equals(node)) {
                                log.info("Received full message from non-coordinator [node=" + node.id() +
                                    ", nodeOrder=" + node.order() +
                                    ", crd=" + crd.id() +
                                    ", crdOrder=" + crd.order() + ']');

                                if (node.order() > crd.order())
                                    fullMsgs.put(node, msg);

                                return;
                            }
                            else {
                                AffinityTopologyVersion resVer = msg.resultTopologyVersion() != null ? msg.resultTopologyVersion() : initialVersion();

                                log.info("Received full message, will finish exchange [node=" + node.id() +
                                    ", resVer=" + resVer + ']');

                                finishState = new FinishState(crd.id(),
                                    resVer,
                                    msg);

                                state = ExchangeLocalState.DONE;

                                break;
                            }
                        }
                    }
                }
            }
            else
                assert node == null : node;

            AffinityTopologyVersion resTopVer = initialVersion();

            if (exchCtx.mergeExchanges()) {
                if (msg.resultTopologyVersion() != null && !initialVersion().equals(msg.resultTopologyVersion())) {
                    log.info("Received full message, need merge [curFut=" + initialVersion() +
                        ", resVer=" + msg.resultTopologyVersion() + ']');

                    resTopVer = msg.resultTopologyVersion();

                    cctx.exchange().mergeExchanges(this, msg);

                    assert resTopVer.equals(exchCtx.events().topologyVersion()) :  "Unexpected result version [" +
                        "msgVer=" + resTopVer +
                        ", locVer=" + exchCtx.events().topologyVersion() + ']';
                }

                if (localJoinExchange())
                    cctx.affinity().onLocalJoin(this, msg, resTopVer);
                else {
                    if (exchCtx.events().serverLeft())
                        cctx.affinity().mergeExchangesOnServerLeft(this, msg);
                    else
                        cctx.affinity().mergeExchangesOnServerJoin(this, false);

                    for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                        if (grp.isLocal() || cacheGroupStopping(grp.groupId()))
                            continue;

                        grp.topology().beforeExchange(this, true);
                    }
                }
            }

            updatePartitionFullMap(resTopVer, msg);

            IgniteCheckedException err = null;

            if (stateChangeExchange() && !F.isEmpty(msg.getErrorsMap())) {
                err = new IgniteCheckedException("Cluster state change failed");

                cctx.kernalContext().state().onStateChangeError(msg.getErrorsMap(), exchActions.stateChangeRequest());
            }

            onDone(resTopVer, err);
        }
        catch (IgniteCheckedException e) {
            onDone(e);
        }
    }

    /**
     * Updates partition map in all caches.
     *
     * @param resTopVer Result topology version.
     * @param msg Partitions full messages.
     */
    private void updatePartitionFullMap(AffinityTopologyVersion resTopVer, GridDhtPartitionsFullMessage msg) {
        cctx.versions().onExchange(msg.lastVersion().order());

        assert partHistSuppliers.isEmpty();

        partHistSuppliers.putAll(msg.partitionHistorySuppliers());

        for (Map.Entry<Integer, GridDhtPartitionFullMap> entry : msg.partitions().entrySet()) {
            Integer grpId = entry.getKey();

            Map<Integer, T2<Long, Long>> cntrMap = msg.partitionUpdateCounters(grpId);

            CacheGroupContext grp = cctx.cache().cacheGroup(grpId);

            if (grp != null) {
                grp.topology().update(resTopVer,
                    entry.getValue(),
                    cntrMap,
                    msg.partsToReload(cctx.localNodeId(), grpId),
                    null);
            }
            else {
                ClusterNode oldest = cctx.discovery().oldestAliveCacheServerNode(AffinityTopologyVersion.NONE);

                if (oldest != null && oldest.isLocal()) {
                    cctx.exchange().clientTopology(grpId, this).update(exchCtx.events().topologyVersion(),
                        entry.getValue(),
                        cntrMap,
                        Collections.<Integer>emptySet(),
                        null);
                }
            }
        }
    }

    /**
     * Updates partition map in all caches.
     *
     * @param nodeId Node message received from.
     * @param msg Partitions single message.
     */
    private void updatePartitionSingleMap(UUID nodeId, GridDhtPartitionsSingleMessage msg) {
        msgs.put(nodeId, msg);

        for (Map.Entry<Integer, GridDhtPartitionMap> entry : msg.partitions().entrySet()) {
            Integer grpId = entry.getKey();
            CacheGroupContext grp = cctx.cache().cacheGroup(grpId);

            GridDhtPartitionTopology top = grp != null ? grp.topology() :
                cctx.exchange().clientTopology(grpId, this);

            top.update(exchId, entry.getValue(), false);
        }
    }

    /**
     * Affinity change message callback, processed from the same thread as {@link #onNodeLeft}.
     *
     * @param node Message sender node.
     * @param msg Message.
     */
    public void onAffinityChangeMessage(final ClusterNode node, final CacheAffinityChangeMessage msg) {
        assert exchId.equals(msg.exchangeId()) : msg;

        onDiscoveryEvent(new IgniteRunnable() {
            @Override public void run() {
                if (isDone() || !enterBusy())
                    return;

                try {
                    assert centralizedAff;

                    if (crd.equals(node)) {
                        AffinityTopologyVersion resTopVer = initialVersion();

                        cctx.affinity().onExchangeChangeAffinityMessage(GridDhtPartitionsExchangeFuture.this,
                            crd.isLocal(),
                            msg);

                        if (!crd.isLocal()) {
                            GridDhtPartitionsFullMessage partsMsg = msg.partitionsMessage();

                            assert partsMsg != null : msg;
                            assert partsMsg.lastVersion() != null : partsMsg;

                            updatePartitionFullMap(resTopVer, partsMsg);
                        }

                        onDone(resTopVer);
                    }
                    else {
                        if (log.isDebugEnabled()) {
                            log.debug("Ignore affinity change message, coordinator changed [node=" + node.id() +
                                ", crd=" + crd.id() +
                                ", msg=" + msg +
                                ']');
                        }
                    }
                }
                finally {
                    leaveBusy();
                }
            }
        });
    }

    /**
     * @param c Closure.
     */
    private void onDiscoveryEvent(IgniteRunnable c) {
        synchronized (discoEvts) {
            if (!init) {
                discoEvts.add(c);

                return;
            }

            assert discoEvts.isEmpty() : discoEvts;
        }

        c.run();
    }

    /**
     * Moves exchange future to state 'init done' using {@link #initFut}.
     */
    private void initDone() {
        while (!isDone()) {
            List<IgniteRunnable> evts;

            synchronized (discoEvts) {
                if (discoEvts.isEmpty()) {
                    init = true;

                    break;
                }

                evts = new ArrayList<>(discoEvts);

                discoEvts.clear();
            }

            for (IgniteRunnable c : evts)
                c.run();
        }

        initFut.onDone(true);
    }

    /**
     * Node left callback, processed from the same thread as {@link #onAffinityChangeMessage}.
     *
     * @param node Left node.
     */
    public void onNodeLeft(final ClusterNode node) {
        if (isDone() || !enterBusy())
            return;

        cctx.mvcc().removeExplicitNodeLocks(node.id(), initialVersion());

        try {
            onDiscoveryEvent(new IgniteRunnable() {
                @Override public void run() {
                    if (isDone() || !enterBusy())
                        return;

                    try {
                        boolean crdChanged = false;
                        boolean allReceived = false;

                        ClusterNode crd0;

                        discoCache.updateAlives(node);

                        InitNewCoordinatorFuture newCrdFut0;

                        synchronized (this) {
                            newCrdFut0 = newCrdFut;
                        }

                        if (newCrdFut0 != null)
                            newCrdFut0.onNodeLeft(node.id());

                        synchronized (this) {
                            if (!srvNodes.remove(node))
                                return;

                            boolean rmvd = remaining.remove(node.id());

                            if (!rmvd) {
                                if (mergedJoinExchMsgs != null && mergedJoinExchMsgs.containsKey(node.id())) {
                                    if (mergedJoinExchMsgs.get(node.id()) == null) {
                                        mergedJoinExchMsgs.remove(node.id());

                                        rmvd = true;
                                    }
                                }
                            }

                            if (node.equals(crd)) {
                                crdChanged = true;

                                crd = !srvNodes.isEmpty() ? srvNodes.get(0) : null;
                            }

                            switch (state) {
                                case DONE:
                                    return;

                                case CRD:
                                    allReceived = rmvd && (remaining.isEmpty() && F.isEmpty(mergedJoinExchMsgs));

                                    break;

                                case SRV:
                                    assert crd != null;

                                    if (crdChanged && crd.isLocal()) {
                                        state = ExchangeLocalState.BECOME_CRD;

                                        newCrdFut = new InitNewCoordinatorFuture();
                                    }

                                    break;
                            }

                            crd0 = crd;

                            if (crd0 == null) {
                                finishState = new FinishState(null, initialVersion(), null);
                            }
                        }

                        if (crd0 == null) {
                            assert cctx.kernalContext().clientNode() : cctx.localNode();

                            List<ClusterNode> empty = Collections.emptyList();

                            for (CacheGroupContext grp : cctx.cache().cacheGroups()) {
                                List<List<ClusterNode>> affAssignment = new ArrayList<>(grp.affinity().partitions());

                                for (int i = 0; i < grp.affinity().partitions(); i++)
                                    affAssignment.add(empty);

                                grp.affinity().initialize(initialVersion(), affAssignment);
                            }

                            onDone(initialVersion());

                            return;
                        }

                        if (crd0.isLocal()) {
                            if (stateChangeExchange() && changeGlobalStateE != null)
                                changeGlobalStateExceptions.put(crd0.id(), changeGlobalStateE);

                            if (crdChanged) {
                                log.info("Coordinator failed, node is new coordinator [ver=" + initialVersion() +
                                    ", prev=" + node.id() + ']');

                                assert newCrdFut != null;

                                newCrdFut.init(GridDhtPartitionsExchangeFuture.this);

                                newCrdFut.listen(new CI1<IgniteInternalFuture>() {
                                    @Override public void apply(IgniteInternalFuture fut) {
                                        onBecomeCoordinator((InitNewCoordinatorFuture)fut);
                                    }
                                });

                                return;
                            }

                            if (allReceived) {
                                awaitSingleMapUpdates();

                                onAllReceived();
                            }
                        }
                        else {
                            if (crdChanged) {
                                for (Map.Entry<ClusterNode, GridDhtPartitionsFullMessage> m : fullMsgs.entrySet()) {
                                    if (crd0.equals(m.getKey())) {
                                        log.info("Coordinator changed, process pending full message [" +
                                            "ver=" + initialVersion() +
                                            ", crd=" + node.id() +
                                            ", pendingMsgNode=" + m.getKey() + ']');

                                        processFullMessage(true, m.getKey(), m.getValue());

                                        if (isDone())
                                            return;
                                    }
                                }

                                log.info("Coordinator changed, send partitions to new coordinator [" +
                                    "ver=" + initialVersion() +
                                    ", crd=" + node.id() +
                                    ", newCrd=" + crd0.id() + ']');

                                sendPartitions(crd0);
                            }
                        }
                    }
                    catch (IgniteCheckedException e) {
                        if (reconnectOnError(e))
                            onDone(new IgniteNeedReconnectException(cctx.localNode(), e));
                        else
                            U.error(log, "Failed to process node left event: " + e, e);
                    }
                    finally {
                        leaveBusy();
                    }
                }
            });
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param newCrdFut Coordinator initialization future.
     */
    private void onBecomeCoordinator(InitNewCoordinatorFuture newCrdFut) {
        boolean allRcvd = false;

        cctx.exchange().coordinatorInitialized();

        if (newCrdFut.restoreState()) {
            GridDhtPartitionsFullMessage fullMsg = newCrdFut.fullMessage();

            boolean process = fullMsg == null;

            assert msgs.isEmpty() : msgs;

            for (Map.Entry<ClusterNode, GridDhtPartitionsSingleMessage> e : newCrdFut.messages().entrySet()) {
                GridDhtPartitionsSingleMessage msg = e.getValue();

                if (!msg.client()) {
                    msgs.put(e.getKey().id(), e.getValue());

                    if (process)
                        updatePartitionSingleMap(e.getKey().id(), msg);
                }
            }

            if (fullMsg != null) {
                log.info("New coordinator restored state [ver=" + initialVersion() +
                    ", resVer=" + fullMsg.resultTopologyVersion() + ']');

                synchronized (this) {
                    state = ExchangeLocalState.DONE;

                    finishState = new FinishState(crd.id(), fullMsg.resultTopologyVersion(), fullMsg);
                }

                fullMsg.exchangeId(exchId);

                processFullMessage(false, null, fullMsg);

                Map<ClusterNode, GridDhtPartitionsSingleMessage> msgs = newCrdFut.messages();

                if (!F.isEmpty(msgs)) {
                    Map<Integer, CacheGroupAffinityMessage> joinedNodeAff = null;

                    for (Map.Entry<ClusterNode, GridDhtPartitionsSingleMessage> e : msgs.entrySet()) {
                        GridDhtPartitionsSingleMessage msg = e.getValue();

                        Collection<Integer> affReq = msg.cacheGroupsAffinityRequest();

                        if (!F.isEmpty(affReq)) {
                            joinedNodeAff = CacheGroupAffinityMessage.createAffinityMessages(cctx,
                                fullMsg.resultTopologyVersion(),
                                affReq,
                                joinedNodeAff);
                        }
                    }

                    sendAllPartitions(fullMsg, msgs.keySet(), newCrdFut.mergedJoinExchangeMessages(), joinedNodeAff);
                }

                return;
            }
            else
                log.info("New coordinator restore state finished [ver=" + initialVersion() + ']');

            allRcvd = true;

            synchronized (this) {
                remaining.clear(); // Do not process messages.

                assert crd != null && crd.isLocal();

                state = ExchangeLocalState.CRD;

                assert mergedJoinExchMsgs == null;
            }
        }
        else {
            Set<UUID> remaining0 = null;

            synchronized (this) {
                assert crd != null && crd.isLocal();

                state = ExchangeLocalState.CRD;

                assert mergedJoinExchMsgs == null;

                log.info("New coordinator initialization finished [ver=" + initialVersion() +
                    ", remaining=" + remaining + ']');

                if (!remaining.isEmpty())
                    remaining0 = new HashSet<>(remaining);
            }

            if (remaining0 != null) {
                // It is possible that some nodes finished exchange with previous coordinator.
                GridDhtPartitionsSingleRequest req = new GridDhtPartitionsSingleRequest(exchId);

                for (UUID nodeId : remaining0) {
                    try {
                        if (!pendingSingleMsgs.containsKey(nodeId)) {
                            log.info("New coordinator sends request [ver=" + initialVersion() +
                                ", node=" + nodeId + ']');

                            cctx.io().send(nodeId, req, SYSTEM_POOL);
                        }
                    }
                    catch (ClusterTopologyCheckedException ignored) {
                        if (log.isDebugEnabled())
                            log.debug("Node left during partition exchange [nodeId=" + nodeId +
                                ", exchId=" + exchId + ']');
                    }
                    catch (IgniteCheckedException e) {
                        U.error(log, "Failed to request partitions from node: " + nodeId, e);
                    }
                }

                for (Map.Entry<UUID, GridDhtPartitionsSingleMessage> m : pendingSingleMsgs.entrySet()) {
                    log.info("New coordinator process pending message [ver=" + initialVersion() +
                        ", node=" + m.getKey() + ']');

                    processSingleMessage(m.getKey(), m.getValue());
                }
            }
        }

        if (allRcvd) {
            awaitSingleMapUpdates();

            onAllReceived();
        }
    }

    /**
     * @param e Exception.
     * @return {@code True} if local node should try reconnect in case of error.
     */
    public boolean reconnectOnError(Throwable e) {
        return X.hasCause(e, IOException.class, IgniteClientDisconnectedCheckedException.class) &&
            cctx.discovery().reconnectSupported();
    }

    /** {@inheritDoc} */
    @Override public int compareTo(GridDhtPartitionsExchangeFuture fut) {
        return exchId.compareTo(fut.exchId);
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || o.getClass() != getClass())
            return false;

        GridDhtPartitionsExchangeFuture fut = (GridDhtPartitionsExchangeFuture)o;

        return exchId.equals(fut.exchId);
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return exchId.hashCode();
    }

    /**
     *
     */
    enum ExchangeType {
        /** */
        CLIENT,

        /** */
        ALL,

        /** */
        NONE
    }

    /** {@inheritDoc} */
    @Override public void addDiagnosticRequest(IgniteDiagnosticPrepareContext diagCtx) {
        if (!isDone()) {
            ClusterNode crd;
            Set<UUID> remaining;

            synchronized (this) {
                crd = this.crd;
                remaining = new HashSet<>(this.remaining);
            }

            if (crd != null) {
                if (!crd.isLocal()) {
                    diagCtx.exchangeInfo(crd.id(), initialVersion(), "Exchange future waiting for coordinator " +
                        "response [crd=" + crd.id() + ", topVer=" + initialVersion() + ']');
                }
                else if (!remaining.isEmpty()){
                    UUID nodeId = remaining.iterator().next();

                    diagCtx.exchangeInfo(nodeId, initialVersion(), "Exchange future on coordinator waiting for " +
                        "server response [node=" + nodeId + ", topVer=" + initialVersion() + ']');
                }
            }
        }
    }

    /**
     * @return Short information string.
     */
    public String shortInfo() {
        return "GridDhtPartitionsExchangeFuture [topVer=" + initialVersion() +
            ", evt=" + (discoEvt != null ? IgniteUtils.gridEventName(discoEvt.type()) : -1) +
            ", evtNode=" + (discoEvt != null ? discoEvt.eventNode() : null) +
            ", done=" + isDone() + ']';
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        Set<UUID> remaining;

        synchronized (this) {
            remaining = new HashSet<>(this.remaining);
        }

        return S.toString(GridDhtPartitionsExchangeFuture.class, this,
            "evtLatch", evtLatch == null ? "null" : evtLatch.getCount(),
            "remaining", remaining,
            "super", super.toString());
    }

    /**
     *
     */
    private static class CounterWithNodes {
        /** */
        private final long cnt;

        /** */
        private final Set<UUID> nodes = new HashSet<>();

        /**
         * @param cnt Count.
         * @param firstNode Node ID.
         */
        private CounterWithNodes(long cnt, UUID firstNode) {
            this.cnt = cnt;

            nodes.add(firstNode);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(CounterWithNodes.class, this);
        }
    }

    /**
     * @param step Exponent coefficient.
     * @param timeout Base timeout.
     * @return Time to wait before next debug dump.
     */
    public static long nextDumpTimeout(int step, long timeout) {
        long limit = getLong(IGNITE_LONG_OPERATIONS_DUMP_TIMEOUT_LIMIT, 30 * 60_000);

        if (limit <= 0)
            limit = 30 * 60_000;

        assert step >= 0 : step;

        long dumpFactor = Math.round(Math.pow(2, step));

        long nextTimeout = timeout * dumpFactor;

        if (nextTimeout <= 0)
            return limit;

        return nextTimeout <= limit ? nextTimeout : limit;
    }

    /**
     *
     */
    private static class FinishState {
        /** */
        private final UUID crdId;

        /** */
        private final AffinityTopologyVersion resTopVer;

        /** */
        private final GridDhtPartitionsFullMessage msg;

        /**
         * @param crdId Coordinator node.
         */
        FinishState(UUID crdId, AffinityTopologyVersion resTopVer, GridDhtPartitionsFullMessage msg) {
            this.crdId = crdId;
            this.resTopVer = resTopVer;
            this.msg = msg;
        }
    }

    /**
     *
     */
    enum ExchangeLocalState {
        CRD,
        SRV,
        CLIENT,
        BECOME_CRD,
        DONE,
        MERGED
    }
}
