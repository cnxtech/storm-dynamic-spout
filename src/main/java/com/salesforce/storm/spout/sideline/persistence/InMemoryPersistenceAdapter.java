/**
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *   disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 *   derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.storm.spout.sideline.persistence;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequest;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequestIdentifier;
import com.salesforce.storm.spout.sideline.trigger.SidelineType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In memory persistence layer implementation. useful for tests.
 * NOT for production use as all state will be lost between JVM restarts.
 */
public class InMemoryPersistenceAdapter implements PersistenceAdapter {

    // "Persists" side line request states in memory.
    private Map<SidelineRequestStateKey, SidelinePayload> storedSidelineRequests;

    @Override
    public void open(Map spoutConfig) {
        if (storedSidelineRequests == null) {
            storedSidelineRequests = Maps.newHashMap();
        }
    }

    @Override
    public void close() {
        // Cleanup
        storedSidelineRequests.clear();
    }

    /**
     * @param type SidelineType (Start or Stop)
     * @param id unique identifier for the sideline request.
     * @param partitionId which partition we want to persist.
     * @param startingOffset The starting offset to persist.
     * @param endingOffset The ending offset to persist.
     */
    @Override
    public void persistSidelineRequestState(
        final SidelineType type,
        final SidelineRequestIdentifier id,
        final SidelineRequest request,
        final int partitionId,
        final Long startingOffset,
        final Long endingOffset
    ) {
        storedSidelineRequests.put(
            getSidelineRequestStateKey(id, partitionId),
            new SidelinePayload(type, id, request, startingOffset, endingOffset)
        );
    }

    /**
     * Retrieves a sideline request state for the given SidelineRequestIdentifier.
     * @param id SidelineRequestIdentifier you want to retrieve the state for.
     * @param partitionId which partition
     * @return The ConsumerState that was persisted via persistSidelineRequestState().
     */
    @Override
    public SidelinePayload retrieveSidelineRequest(SidelineRequestIdentifier id, int partitionId) {
        return storedSidelineRequests.get(getSidelineRequestStateKey(id, partitionId));
    }

    @Override
    public void clearSidelineRequest(SidelineRequestIdentifier id, int partitionId) {
        storedSidelineRequests.remove(getSidelineRequestStateKey(id, partitionId));
    }

    @Override
    public List<SidelineRequestIdentifier> listSidelineRequests() {
        List<SidelineRequestIdentifier> ids = Lists.newArrayList();

        for (SidelinePayload sidelinePayload : storedSidelineRequests.values()) {
            ids.add(sidelinePayload.id);
        }

        return ids;
    }

    @Override
    public Set<Integer> listSidelineRequestPartitions(final SidelineRequestIdentifier id) {
        final Set<Integer> partitions = Sets.newHashSet();

        for (SidelineRequestStateKey key : storedSidelineRequests.keySet()) {
            if (key.id.equals(id)) {
                partitions.add(key.partitionId);
            }
        }

        return Collections.unmodifiableSet(partitions);
    }

    private SidelineRequestStateKey getSidelineRequestStateKey(final SidelineRequestIdentifier id, final int partitionId) {
        return new SidelineRequestStateKey(id, partitionId);
    }

    private static class SidelineRequestStateKey {

        public final SidelineRequestIdentifier id;
        public final int partitionId;

        SidelineRequestStateKey(final SidelineRequestIdentifier id, final int partitionId) {
            this.id = id;
            this.partitionId = partitionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }

            SidelineRequestStateKey that = (SidelineRequestStateKey) other;

            if (partitionId != that.partitionId) {
                return false;
            }
            return id != null ? id.equals(that.id) : that.id == null;
        }

        @Override
        public int hashCode() {
            int result = id != null ? id.hashCode() : 0;
            result = 31 * result + partitionId;
            return result;
        }
    }
}