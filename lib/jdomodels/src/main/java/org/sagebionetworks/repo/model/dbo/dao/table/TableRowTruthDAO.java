package org.sagebionetworks.repo.model.dbo.dao.table;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.sagebionetworks.repo.model.table.ColumnChange;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.IdRange;
import org.sagebionetworks.repo.model.table.SparseChangeSetDto;
import org.sagebionetworks.repo.model.table.TableChangeType;
import org.sagebionetworks.repo.model.table.TableRowChange;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.TemporaryCode;

/**
 * This is the "truth" store for all rows of TableEntites.
 * 
 * @author John
 *
 */
public interface TableRowTruthDAO {
	
	/**
	 * Reserver a range of IDs for a given table and count.
	 * 
	 * @param tableId
	 * @param coutToReserver
	 * @return
	 */
	IdRange reserveIdsInRange(String tableId, long coutToReserver);
	
	/**
	 * Get the version number for a given table ID and etag.
	 * 
	 * @param tableIdString
	 * @param etag
	 * @return
	 */
	long getVersionForEtag(String tableIdString, String etag);
	
	/**
	 * Get the highest row ID in this table
	 * 
	 * @return
	 */
	long getMaxRowId(String tableId);

	/**
	 * Get the highest current version for this table
	 * 
	 * @return
	 */
	TableRowChange getLastTableRowChange(String tableId);
	
	/**
	 * Get the last transaction ID associated with the given table.
	 * @param tableId
	 * @return
	 */
	Optional<Long> getLastTransactionId(String tableId);
	
	/**
	 * Get the latest TableRowChange of a given type.
	 * 
	 * @param tableId
	 * @param changeType
	 * @return
	 */
	TableRowChange getLastTableRowChange(String tableId, TableChangeType changeType);
	
	/**
	 * Append a SpareChangeSet to the given table.
	 * 
	 * @param userId
	 * @param tableId
	 * @param columns
	 * @param delta
	 * @param hasFileRefs If the change set includes references to file handles, note that it can currently be null but used for testing only before the backfill is performed
	 * @return
	 * @throws IOException
	 */
	String appendRowSetToTable(String userId, String tableId, String etag, long versionNumber, List<ColumnModel> columns, SparseChangeSetDto delta, long transactionId, Boolean hasFileRefs);
	
	/**
	 * Append a schema change to the table's changes.
	 * 
	 * @param userId
	 * @param tableId
	 * @param current
	 * @param changes
	 * @throws IOException 
	 */
	long appendSchemaChangeToTable(String userId, String tableId, List<String> current, List<ColumnChange> changes, long transactionId);
	
	/**
	 * Get the schema change for a given version.
	 * @param tableId
	 * @param versionNumber
	 * @return
	 * @throws IOException 
	 */
	List<ColumnChange> getSchemaChangeForVersion(String tableId, long versionNumber) throws IOException;
	
	/**
	 * Fetch a sparse change set for a given table.
	 * 
	 * @param tableId
	 * @param rowVersion
	 * @return
	 * @throws IOException 
	 */
	SparseChangeSetDto getRowSet(String tableId, long rowVersion) throws IOException;
	
	/**
	 * Get the ChangeSet for the given dto.
	 * @param rowChange
	 * @return
	 * @throws IOException 
	 */
	SparseChangeSetDto getRowSet(TableRowChange dto) throws IOException;

	/**
	 * List the keys of all change sets applied to a table.
	 * 
	 * This can be used to synch the "truth" store with secondary stores. This is the full history of the table.
	 * 
	 * @param tableId
	 * @return
	 */
	@Deprecated
	List<TableRowChange> listRowSetsKeysForTable(String tableId);
	
	/**
	 * List all changes for a table with a version number greater than the given value (exclusive).
	 * 
	 * @param tableId
	 * @param version
	 * @return
	 */
	List<TableRowChange> listRowSetsKeysForTableGreaterThanVersion(String tableId, long version);

	/**
	 * Get the TableRowChange for a given tableId and row version number.
	 * 
	 * @param tableId
	 * @param rowVersion
	 * @return
	 * @throws NotFoundException
	 */
	TableRowChange getTableRowChange(String tableId, long rowVersion) throws NotFoundException;

	/**
	 * This should only be called after the table entity has been deleted
	 * 
	 * @param tableId
	 */
	void deleteAllRowDataForTable(String tableId);
	
	/**
	 * This should never be called in a production setting.
	 * 
	 */
	void truncateAllRowData();

	/**
	 * Get a single page of changes for the given table, limit, and offset.
	 * 
	 * @param tableId
	 * @param limit
	 * @param offset
	 * @return
	 */
	List<TableRowChange> getTableChangePage(String tableId, long limit, long offset);

	/**
	 * Get the last change number applied to the given table.
	 * @param tableId
	 * @return Will return an empty Optional if no changes have been applied to the table.
	 */
	Optional<Long> getLastTableChangeNumber(long tableId);
	
	/**
	 * Get the last change number applied to the given table and version number combination.
	 * @param tableId
	 * @param tableVersion
	 * @return
	 */
	Optional<Long> getLastTableChangeNumber(long tableId, long tableVersion);

	/**
	 * Does the given table have at least one change of the provided type?
	 * 
	 * @param tableId
	 * @return
	 */
	boolean hasAtLeastOneChangeOfType(String tableId, TableChangeType type);
	
	/**
	 * Delete a specific change number from a table.
	 * @param tableId
	 * @param changeNumber
	 */
	void deleteChangeNumber(String tableId, long changeNumber);

	/**
	 * Does the given table change etag match any change in the table's history?
	 * 
	 * @param tableId
	 * @param etag
	 * @return
	 */
	boolean isEtagInTablesChangeHistory(String tableId, String etag);
	
	/**
	 * @return The range of ids for the table row change table
	 */
	org.sagebionetworks.repo.model.IdRange getTableRowChangeIdRange();
	
	/**
	 * @param idRange
	 * @param limit
	 * @param offset
	 * @return A page of row changes that have file references (includes the changes for which the file references are unknown)
	 */
	List<TableRowChange> getTableRowChangeWithFileRefsPage(org.sagebionetworks.repo.model.IdRange idRange, long limit, long offset);

	@TemporaryCode(author = "marco.marasca@sagebase.org", comment = "Use for backfilling the table row change")
	List<TableRowChange> getTableRowChangeWithNullFileRefsPage(long limit, long offset);
	
	@TemporaryCode(author = "marco.marasca@sagebase.org", comment = "Use for backfilling the table row change")
	void updateRowChangeHasFileRefsBatch(List<Long> ids, boolean hasFileRefs);
	
}
