package org.sagebionetworks.repo.manager.replication;

import java.util.Iterator;

import org.sagebionetworks.repo.model.IdAndChecksum;
import org.sagebionetworks.table.cluster.view.filter.ViewFilter;

/**
 * Abstraction for synchronizing view truth data with replication data.
 *
 */
public interface TruthAndReplicationSynchronization {

	
	/**
	 * Stream over the view ID and checksums for all items defined by the given view filter.
	 * Note: It is expected that the items are ordered by ID ascending.
	 * @param salt Each run will receive a new random salt that is passed to both sides of the synch.
	 * @param filter
	 * @return
	 */
	Iterator<IdAndChecksum> streamOverIdsAndChecksums(Long salt, ViewFilter filter);
	
}
