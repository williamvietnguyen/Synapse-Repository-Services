package org.sagebionetworks.repo.model.dbo.dao.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.ColumnChange;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.IdRange;
import org.sagebionetworks.repo.model.table.RawRowSet;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.SparseChangeSetDto;
import org.sagebionetworks.repo.model.table.TableChangeType;
import org.sagebionetworks.repo.model.table.TableRowChange;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.table.model.SparseChangeSet;
import org.sagebionetworks.table.model.SparseRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.common.collect.Lists;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class TableRowTruthDAOImplTest {

	@Autowired
	private TableRowTruthDAO tableRowTruthDao;
	
	@Autowired
	FileHandleDao fileHandleDao;
	
	@Autowired
	private TableTransactionDao tableTransactionDao;
	
	@Autowired
	TransactionTemplate readCommitedTransactionTemplate;

	protected String creatorUserGroupId;

	
	List<String> fileHandleIds;
	String tableId;

	@BeforeEach
	public void before() throws Exception {
		tableRowTruthDao.truncateAllRowData();
		creatorUserGroupId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId().toString();
		assertNotNull(creatorUserGroupId);
		fileHandleIds = new LinkedList<String>();	
		tableId = "syn123";
	}
	
	@AfterEach
	public void after() throws Exception {
		if(tableRowTruthDao != null) tableRowTruthDao.truncateAllRowData();

		if(fileHandleIds != null){
			for(String id: fileHandleIds){
				try {
					fileHandleDao.delete(id);
				} catch (Exception e) {}
			}
		}
		tableTransactionDao.deleteTable(tableId);
	}

	@Test
	public void testReserveIdsInRange(){
		IdRange range = tableRowTruthDao.reserveIdsInRange(tableId, 3);
		assertNotNull(range);
		assertEquals(new Long(0), range.getMinimumId());
		assertEquals(new Long(2), range.getMaximumId());
		assertEquals(new Long(0), range.getVersionNumber());
		assertTrue(range.getMaximumUpdateId() < 0);
		assertNotNull(range.getEtag());
		// Now reserver 1 more
		range = tableRowTruthDao.reserveIdsInRange(tableId, 1);
		assertNotNull(range);
		assertEquals(new Long(3), range.getMinimumId());
		assertEquals(new Long(3), range.getMaximumId());
		assertEquals(new Long(1), range.getVersionNumber());
		assertEquals(new Long(2), range.getMaximumUpdateId());
		assertNotNull(range.getEtag());
		// two more
		range = tableRowTruthDao.reserveIdsInRange(tableId, 2);
		assertNotNull(range);
		assertEquals(new Long(4), range.getMinimumId());
		assertEquals(new Long(5), range.getMaximumId());
		assertEquals(new Long(2), range.getVersionNumber());
		assertEquals(new Long(3), range.getMaximumUpdateId());
		assertNotNull(range.getEtag());
		// zero
		range = tableRowTruthDao.reserveIdsInRange(tableId, 0);
		assertNotNull(range);
		assertEquals(null, range.getMinimumId());
		assertEquals(null, range.getMaximumId());
		assertEquals(new Long(5), range.getMaximumUpdateId());
		assertEquals(new Long(3), range.getVersionNumber());
		assertNotNull(range.getEtag());
	}
	
	@Test
	public void testAppendRows() throws Exception {
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		List<SelectColumn> select = TableModelUtils.getSelectColumns(columns);
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5, false);
		
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		long versionNumber =  appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		assertEquals(0L, versionNumber);
	}
	
	/**
	 * Helper to append rows to a table.
	 * @param userId
	 * @param tableId
	 * @param columns
	 * @param delta
	 * @return
	 * @throws IOException
	 */
	private long appendRowSetToTable(String userId,
			String tableId, List<ColumnModel> columns, RawRowSet delta) throws IOException {
		
		SparseChangeSet spars = TableModelUtils.createSparseChangeSet(delta, columns);
		return appendRowSetToTable(userId, tableId, columns, spars);
	}
	
	private long appendRowSetToTable(String userId,
			String tableId, List<ColumnModel> columns, SparseChangeSet delta) throws IOException {
		Long linkToVersion = null;
		return appendRowSetToTable(userId, tableId, columns, delta, linkToVersion);
	}
	
	private long appendRowSetToTable(String userId, String tableId, List<ColumnModel> columns, SparseChangeSet delta, Long linkToVersion) throws IOException{
		return appendRowSetToTable(userId, tableId, columns, delta, linkToVersion, !delta.getFileHandleIdsInSparseChangeSet().isEmpty());
	}
	
	/**
	 * Helper to append SparseChangeSetDto to a table.
	 * @param userId
	 * @param tableId
	 * @param columns
	 * @param delta
	 * @return
	 * @throws IOException
	 */
	private long appendRowSetToTable(String userId, String tableId, List<ColumnModel> columns, SparseChangeSet delta,
			Long linkToVersion, Boolean hasFileRefs) throws IOException {
		return readCommitedTransactionTemplate.execute((TransactionStatus status) -> {
			// Now set the row version numbers and ID.
			int coutToReserver = TableModelUtils.countEmptyOrInvalidRowIds(delta);
			// Reserver IDs for the missing
			IdRange range = tableRowTruthDao.reserveIdsInRange(delta.getTableId(), coutToReserver);
			// Now assign the rowIds and set the version number
			TableModelUtils.assignRowIdsAndVersionNumbers(delta, range);
			Long transactionId = tableTransactionDao.startTransaction(tableId, Long.parseLong(userId));
			tableRowTruthDao.appendRowSetToTable(userId, delta.getTableId(), range.getEtag(), range.getVersionNumber(),
					columns, delta.writeToDto(), transactionId, hasFileRefs);
			TableRowChange change = tableRowTruthDao.getLastTableRowChange(tableId, TableChangeType.ROW);
			if (linkToVersion != null) {
				tableTransactionDao.linkTransactionToVersion(transactionId, linkToVersion);
			}
			assertEquals(transactionId, change.getTransactionId());
			return range.getVersionNumber();
		});
	}
	
	/**
	 * Helper to append a schema change with a transaction.
	 * 
	 * @param userId
	 * @param tableId
	 * @param current
	 * @param changes
	 * @return
	 * @throws IOException
	 */
	private long appendSchemaChangeToTable(String userId, String tableId, List<String> current, List<ColumnChange> changes) throws IOException{
		Long transactionId = tableTransactionDao.startTransaction(tableId, Long.parseLong(userId));
		long version = tableRowTruthDao.appendSchemaChangeToTable(userId, tableId, current, changes, transactionId);
		TableRowChange change = tableRowTruthDao.getLastTableRowChange(tableId, TableChangeType.COLUMN);
		assertEquals(transactionId, change.getTransactionId());
		return version;
	}

	@Test
	public void testGetTableChangePage() throws IOException{
		long limit = 2L;
		long offset = 0L;
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		// Before we start there should be no changes
		List<TableRowChange> results = tableRowTruthDao.getTableChangePage(tableId, limit, offset);
		assertNotNull(results);
		assertEquals(0, results.size());
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		// Add some more rows
		set = new RawRowSet(set.getIds(), set.getEtag(), set.getTableId(), TableModelTestUtils.createRows(columns, 2));
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		// There should now be two version of the data
		results = tableRowTruthDao.getTableChangePage(tableId, limit, offset);
		assertNotNull(results);
		assertEquals(2, results.size());
		// Validate the results
		// The first change should be version zero
		TableRowChange zero = results.get(0);
		assertEquals(new Long(0), zero.getRowVersion());
		assertEquals(tableId, zero.getTableId());
		assertEquals(creatorUserGroupId, zero.getCreatedBy());
		assertNotNull(zero.getCreatedOn());
		assertTrue(zero.getCreatedOn().getTime() > 0);
		assertNotNull(zero.getBucket());
		assertNotNull(zero.getKeyNew());
		assertNotNull(zero.getEtag());
		assertEquals(new Long(5), zero.getRowCount());
		// Next is should be version one.
		// The first change should be version zero
		TableRowChange one = results.get(1);
		assertEquals(new Long(1), one.getRowVersion());
		assertNotNull(one.getEtag());
		assertFalse(zero.getEtag().equals(one.getEtag()), "Two changes cannot have the same Etag");
		
		// Listing all versions greater than zero should be the same as all
		List<TableRowChange> greater = tableRowTruthDao.listRowSetsKeysForTableGreaterThanVersion(tableId, -1l);
		assertNotNull(greater);
		assertEquals(results, greater);
		// Now limit to greater than version zero
		greater = tableRowTruthDao.listRowSetsKeysForTableGreaterThanVersion(tableId, 0l);
		assertNotNull(greater);
		assertEquals(1, greater.size());
		assertEquals(new Long(1), greater.get(0).getRowVersion());
	}
	
	@Test
	public void testGetTableChangePageMixedType() throws IOException{
		long limit = 2L;
		long offset = 0L;
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		// Before we start there should be no changes
		List<TableRowChange> results = tableRowTruthDao.getTableChangePage(tableId, limit, offset);
		assertNotNull(results);
		assertEquals(0, results.size());
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		// append a column change to the table
		ColumnChange add = new ColumnChange();
		add.setOldColumnId(null);
		add.setNewColumnId("123");		
		List<ColumnChange> changes = new LinkedList<ColumnChange>();
		changes.add(add);
		
		 List<String> current = new LinkedList<String>();
		 current.add("123");
		 current.add("888");
		// Append a column change this change set
		appendSchemaChangeToTable(creatorUserGroupId, tableId, current, changes);
		// Call under test
		// Listing all versions greater than zero should be the same as all
		List<TableRowChange> rowChanges = tableRowTruthDao.listRowSetsKeysForTableGreaterThanVersion(tableId, -1l);
		assertNotNull(rowChanges);
		assertEquals(1, rowChanges.size());
		TableRowChange change = rowChanges.get(0);
		assertEquals(TableChangeType.ROW, change.getChangeType());
	}

	
	@Test
	public void testGetMaxRowIdEmpty(){
		// for a tableId that does not exist the value should be negative (zero is a value rowId).
		assertEquals(-1L, tableRowTruthDao.getMaxRowId(tableId));
	}
	
	@Test
	public void testGetLastTableRowChange() throws IOException{
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
	
		TableChangeType changeType = TableChangeType.ROW;
		// call under test
		TableRowChange change = tableRowTruthDao.getLastTableRowChange(tableId, changeType);
		assertNotNull(change);
		assertEquals(changeType, change.getChangeType());
		// There should be no changes of type column.
		changeType = TableChangeType.COLUMN;
		change = tableRowTruthDao.getLastTableRowChange(tableId, changeType);
		assertNull(change);
	}
	
	@Test
	public void testGetLastTableChangeNumber() throws IOException{
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		// call under test
		Optional<Long> lastChangeNumber = tableRowTruthDao.getLastTableChangeNumber(KeyFactory.stringToKey(tableId));
		assertNotNull(lastChangeNumber);
		assertTrue(lastChangeNumber.isPresent());
		assertEquals(new Long(0), lastChangeNumber.get());
	}
	
	@Test
	public void testGetLastTableChangeNumberEmpty() throws IOException{
		// call under test
		Optional<Long> lastChangeNumber = tableRowTruthDao.getLastTableChangeNumber(KeyFactory.stringToKey(tableId));
		assertNotNull(lastChangeNumber);
		assertFalse(lastChangeNumber.isPresent());
	}
	
	@Test
	public void testGetLastTableChangeNumberWithVersion() throws IOException{
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		SparseChangeSet spars = TableModelUtils.createSparseChangeSet(set, columns);
		Long linkToVersion = 12L;
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, spars, linkToVersion);
		// call under test
		Optional<Long> lastChangeNumber = tableRowTruthDao.getLastTableChangeNumber(KeyFactory.stringToKey(tableId), linkToVersion);
		assertNotNull(lastChangeNumber);
		assertTrue(lastChangeNumber.isPresent());
		assertEquals(new Long(0), lastChangeNumber.get());
	}
	
	
	@Test
	public void testGetLastTableRowChangeNullType() throws IOException{
		TableChangeType changeType = null;
		
		assertThrows(IllegalArgumentException.class, () -> {			
			// call under test
			tableRowTruthDao.getLastTableRowChange(tableId, changeType);
		});
	}
	
	@Test
	public void testGetLastTableRowChangeNullId() throws IOException{
		TableChangeType changeType = TableChangeType.COLUMN;
		String tableId = null;
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			tableRowTruthDao.getLastTableRowChange(tableId, changeType);
		});
	}

	@Test
	public void testAppendSchemaChange() throws IOException{
		ColumnChange add = new ColumnChange();
		add.setOldColumnId(null);
		add.setNewColumnId("123");
		
		ColumnChange delete = new ColumnChange();
		delete.setOldColumnId("456");
		delete.setNewColumnId(null);
		
		ColumnChange update = new ColumnChange();
		update.setOldColumnId("777");
		update.setNewColumnId("888");
		
		List<ColumnChange> changes = new LinkedList<ColumnChange>();
		changes.add(add);
		changes.add(delete);
		changes.add(update);
		
		 List<String> current = new LinkedList<String>();
		 current.add("123");
		 current.add("888");
		 
		// append the schema change to the changes table.
		long versionNumber = appendSchemaChangeToTable(creatorUserGroupId, tableId, current, changes);
		// lookup the schema change for the given change number.
		List<ColumnChange> back = tableRowTruthDao.getSchemaChangeForVersion(tableId, versionNumber);
		assertEquals(changes, back);
	}
	
	@Test
	public void testAppendRowSetToTable() throws IOException{
		ColumnModel aBoolean = TableModelTestUtils.createColumn(201L, "aBoolean", ColumnType.BOOLEAN);
		ColumnModel aString = TableModelTestUtils.createColumn(202L, "aString", ColumnType.STRING);
		List<ColumnModel> schema = Lists.newArrayList(aBoolean, aString);
		
		SparseChangeSet changeSet = new SparseChangeSet(tableId, schema);
		
		SparseRow rowOne = changeSet.addEmptyRow();
		rowOne.setCellValue(aBoolean.getId(), "true");
		rowOne.setCellValue(aString.getId(), "foo");
		
		SparseRow rowTwo = changeSet.addEmptyRow();
		rowTwo.setCellValue(aString.getId(), "bar");
		
		// Save it
		long versionNumber = appendRowSetToTable(creatorUserGroupId, tableId, schema, changeSet);
		// fetch it back
		SparseChangeSetDto copy = tableRowTruthDao.getRowSet(tableId, versionNumber);
		assertEquals(changeSet.writeToDto(), copy);
	}

	@Test
	public void testHasAtLeastOneChangeOfType() throws IOException {
		// call under test
		assertFalse(tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.COLUMN));
		assertFalse(tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.ROW));
		
		// Add a column to the table
		ColumnModel aBoolean = TableModelTestUtils.createColumn(201L, "aBoolean", ColumnType.BOOLEAN);
		List<ColumnModel> schema = Lists.newArrayList(aBoolean, aBoolean);
		ColumnChange add = new ColumnChange();
		add.setOldColumnId(null);
		add.setNewColumnId(aBoolean.getId());
		List<String> current = Lists.newArrayList(aBoolean.getId());
		List<ColumnChange> changes = Lists.newArrayList(add);
		// Add a schema change
		appendSchemaChangeToTable(creatorUserGroupId, tableId, current, changes);
		
		// call under test
		assertTrue(tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.COLUMN));
		assertFalse(tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.ROW));
		
		// append rows to the table
		SparseChangeSet changeSet = new SparseChangeSet(tableId, schema);
		
		SparseRow rowOne = changeSet.addEmptyRow();
		rowOne.setCellValue(aBoolean.getId(), "true");
		appendRowSetToTable(creatorUserGroupId, tableId, schema, changeSet);
		
		// call under test
		assertTrue(tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.COLUMN));
		assertTrue(tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.ROW));
	}
	
	@Test
	public void testHasAtLeastOneChangeOfTypeNullTableId() {
		String tableId = null;
		
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.COLUMN);
		});
	}
	
	@Test
	public void testHasAtLeastOneChangeOfTypeNullType() {
		TableChangeType type = null;
		
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			tableRowTruthDao.hasAtLeastOneChangeOfType(tableId, type);
		});
	}
	
	@Test
	public void testgetLastTransactionIdTableDoesNotExist() {
		// empty if the table does not exist
		
		Optional<Long> lastTrans = tableRowTruthDao.getLastTransactionId(tableId);
		assertNotNull(lastTrans);
		assertFalse(lastTrans.isPresent());
	}
	
	@Test
	public void testgetLastTransactionIdTable() throws IOException {

		// append rows to the table
		// Add a column to the table
		ColumnModel aBoolean = TableModelTestUtils.createColumn(201L, "aBoolean", ColumnType.BOOLEAN);
		List<ColumnModel> schema = Lists.newArrayList(aBoolean);
		SparseChangeSet changeSet = new SparseChangeSet(tableId, schema);
		
		SparseRow rowOne = changeSet.addEmptyRow();
		rowOne.setCellValue(aBoolean.getId(), "true");
		appendRowSetToTable(creatorUserGroupId, tableId, schema, changeSet);
		
		// call under test
		Optional<Long> firstTransaction = tableRowTruthDao.getLastTransactionId(tableId);
		assertNotNull(firstTransaction);
		assertTrue(firstTransaction.isPresent());
		// add more rows
		appendRowSetToTable(creatorUserGroupId, tableId, schema, changeSet);
		
		Optional<Long> secondTransaction = tableRowTruthDao.getLastTransactionId(tableId);
		assertNotNull(secondTransaction);
		assertTrue(secondTransaction.isPresent());
		assertTrue(secondTransaction.get() > firstTransaction.get());
	}
	
	@Test
	public void testIsEtagInTablesChangeHistoryWithNoTable() {
		String etag = "does not exist";
		// call under test
		assertFalse(tableRowTruthDao.isEtagInTablesChangeHistory(tableId, etag));
	}
	
	@Test
	public void testIsEtagInTablesChangeHistoryWithTableWithMatch() throws IOException {
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
	
		TableChangeType changeType = TableChangeType.ROW;
		TableRowChange change = tableRowTruthDao.getLastTableRowChange(tableId, changeType);
		assertNotNull(change);
		assertEquals(changeType, change.getChangeType());
		
		// call under test
		assertTrue(tableRowTruthDao.isEtagInTablesChangeHistory(tableId, change.getEtag()));
	}
	
	@Test
	public void testIsEtagInTablesChangeHistoryWithTableWithMatchNotLatest() throws IOException {
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
	
		TableChangeType changeType = TableChangeType.ROW;
		TableRowChange firstChange = tableRowTruthDao.getLastTableRowChange(tableId, changeType);
		
		// Append another change
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		
		// call under test
		assertTrue(tableRowTruthDao.isEtagInTablesChangeHistory(tableId, firstChange.getEtag()));
	}
	
	@Test
	public void testIsEtagInTablesChangeHistoryWithTableWithNoMatch() throws IOException {
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
	
		TableChangeType changeType = TableChangeType.ROW;
		TableRowChange change = tableRowTruthDao.getLastTableRowChange(tableId, changeType);
		assertNotNull(change);
		assertEquals(changeType, change.getChangeType());
		
		// call under test
		assertFalse(tableRowTruthDao.isEtagInTablesChangeHistory(tableId, change.getEtag()+"no-match"));
	}
	
	@Test
	public void testIsEtagInTablesChangeHistoryWithNullTableId() throws IOException {
		String tableId = null;
		String etag = "some-etag";
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			tableRowTruthDao.isEtagInTablesChangeHistory(tableId, etag);
		});
	}
	
	@Test
	public void testIsEtagInTablesChangeHistoryWithNullEtag() throws IOException {
		String etag = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			tableRowTruthDao.isEtagInTablesChangeHistory(tableId, etag);
		});
	}
	
	@Test
	public void testGetTableChangeIdRange() throws IOException {
		
		// Create some test column models
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		// Append two change sets
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		
		// Call under test
		org.sagebionetworks.repo.model.IdRange result = tableRowTruthDao.getTableRowChangeIdRange();
		
		assertEquals(new org.sagebionetworks.repo.model.IdRange(result.getMinId(), result.getMinId() + 1), result);
	}
	
	@Test
	public void testGetTableChangeIdRangeWithEmpty() throws IOException{
		
		// Call under test
		org.sagebionetworks.repo.model.IdRange result = tableRowTruthDao.getTableRowChangeIdRange();
		
		assertEquals(new org.sagebionetworks.repo.model.IdRange(-1, -1), result);
	}
	
	@Test
	public void testGetTableRowChangeWithFileRefsPage() throws IOException {
		long limit = 10L;
		long offset = 0L;
		// Before we start there should be no changes
		List<TableRowChange> results = tableRowTruthDao.getTableRowChangeWithFileRefsPage(tableRowTruthDao.getTableRowChangeIdRange(), limit, offset);
		assertTrue(results.isEmpty());
		
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		// Add some more rows
		set = new RawRowSet(set.getIds(), set.getEtag(), set.getTableId(), TableModelTestUtils.createRows(columns, 2));
		
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		
		results = tableRowTruthDao.getTableRowChangeWithFileRefsPage(tableRowTruthDao.getTableRowChangeIdRange(), limit, offset);
		
		assertEquals(2, results.size());
		
		// Add a row without file handles
		List<Row> rowWithoutFiles = TableModelTestUtils.createRows(columns, 1);
		
		rowWithoutFiles.get(0).getValues().set(ColumnType.FILEHANDLEID.ordinal(), null);
		
		set = new RawRowSet(set.getIds(), set.getEtag(), set.getTableId(), rowWithoutFiles);
		
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);

		results = tableRowTruthDao.getTableRowChangeWithFileRefsPage(tableRowTruthDao.getTableRowChangeIdRange(), limit, offset);
		
		assertEquals(2, results.size());
		
	}
	
	@Test
	public void testGetTableRowChangeWithNullFileRefsPage() throws IOException {
		long limit = 10L;
		long offset = 0L;
		// Before we start there should be no changes
		List<TableRowChange> results = tableRowTruthDao.getTableRowChangeWithNullFileRefsPage(limit, offset);
		assertTrue(results.isEmpty());
		
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		
		// Result still empty as all of them has the file ref boolean
		results = tableRowTruthDao.getTableRowChangeWithNullFileRefsPage(limit, offset);
		
		assertTrue(results.isEmpty());
		
		// Now add some rows with the null hasFileRefs
		set = new RawRowSet(set.getIds(), set.getEtag(), set.getTableId(), TableModelTestUtils.createRows(columns, 2));
		
		SparseChangeSet spars = TableModelUtils.createSparseChangeSet(set, columns);
		
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, spars, null, null);
		
		results = tableRowTruthDao.getTableRowChangeWithNullFileRefsPage(limit, offset);
		
		assertEquals(1, results.size());
	}
	
	@Test
	public void testUpdateRowChangeHasFileRefsBatch() throws IOException {
		long limit = 10L;
		long offset = 0L;
		
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		
		// create some test rows.
		List<Row> rows = TableModelTestUtils.createRows(columns, 5);
		
		RawRowSet set = new RawRowSet(TableModelUtils.getIds(columns), null, tableId, rows);
		
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, set);
		
		// Now add some rows with the null hasFileRefs
		set = new RawRowSet(set.getIds(), set.getEtag(), set.getTableId(), TableModelTestUtils.createRows(columns, 2));
		
		SparseChangeSet spars = TableModelUtils.createSparseChangeSet(set, columns);
		
		// Append this change set
		appendRowSetToTable(creatorUserGroupId, tableId, columns, spars, null, null);
		
		List<TableRowChange> results = tableRowTruthDao.getTableRowChangeWithNullFileRefsPage(limit, offset);
		
		assertEquals(1, results.size());
		
		List<Long> ids = results.stream().map(TableRowChange::getId).collect(Collectors.toList());

		String etag = results.get(0).getEtag();
		
		// Call under test
		tableRowTruthDao.updateRowChangeHasFileRefsBatch(ids, false);
		
		// Now no row should have the null flag
		assertTrue(tableRowTruthDao.getTableRowChangeWithNullFileRefsPage(limit, offset).isEmpty());
		
		// And the first changeset should still be true
		assertEquals(1, tableRowTruthDao.getTableRowChangeWithFileRefsPage(tableRowTruthDao.getTableRowChangeIdRange(), limit, offset).size());
		
		assertNotEquals(etag, tableRowTruthDao.getTableRowChange(results.get(0).getTableId(), results.get(0).getRowVersion()).getEtag());
	}
}
