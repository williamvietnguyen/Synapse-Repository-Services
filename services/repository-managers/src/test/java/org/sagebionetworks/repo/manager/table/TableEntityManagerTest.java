package org.sagebionetworks.repo.manager.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyListOf;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.common.util.progress.ProgressingCallable;
import org.sagebionetworks.repo.manager.NodeManager;
import org.sagebionetworks.repo.manager.events.EventsCollector;
import org.sagebionetworks.repo.manager.file.FileHandleAuthorizationStatus;
import org.sagebionetworks.repo.manager.table.change.TableChangeMetaData;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.StackStatusDao;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.dbo.dao.table.CSVToRowIterator;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.dbo.dao.table.TableRowTruthDAO;
import org.sagebionetworks.repo.model.dbo.dao.table.TableTransactionDao;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.exception.ReadOnlyException;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.status.StatusEnum;
import org.sagebionetworks.repo.model.table.AppendableRowSetRequest;
import org.sagebionetworks.repo.model.table.ColumnChange;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.IdRange;
import org.sagebionetworks.repo.model.table.PartialRow;
import org.sagebionetworks.repo.model.table.PartialRowSet;
import org.sagebionetworks.repo.model.table.RawRowSet;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowReference;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowReferenceSetResults;
import org.sagebionetworks.repo.model.table.RowSelection;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.SnapshotRequest;
import org.sagebionetworks.repo.model.table.SnapshotResponse;
import org.sagebionetworks.repo.model.table.SparseChangeSetDto;
import org.sagebionetworks.repo.model.table.SparseRowDto;
import org.sagebionetworks.repo.model.table.TableChangeType;
import org.sagebionetworks.repo.model.table.TableRowChange;
import org.sagebionetworks.repo.model.table.TableSchemaChangeRequest;
import org.sagebionetworks.repo.model.table.TableSchemaChangeResponse;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatus;
import org.sagebionetworks.repo.model.table.TableUpdateRequest;
import org.sagebionetworks.repo.model.table.TableUpdateResponse;
import org.sagebionetworks.repo.model.table.UploadToTableRequest;
import org.sagebionetworks.repo.model.table.UploadToTableResult;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.table.cluster.ColumnChangeDetails;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.SqlQuery;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.table.model.ChangeData;
import org.sagebionetworks.table.model.SchemaChange;
import org.sagebionetworks.table.model.SparseChangeSet;
import org.sagebionetworks.table.model.SparseRow;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonServiceException.ErrorType;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import au.com.bytecode.opencsv.CSVReader;

@ExtendWith(MockitoExtension.class)
public class TableEntityManagerTest {
	
	@Mock
	ProgressCallback mockProgressCallback;
	@Mock
	StackStatusDao mockStackStatusDao;
	@Mock
	TableRowTruthDAO mockTruthDao;
	@Mock
	ConnectionFactory mockTableConnectionFactory;
	@Mock
	TableIndexDAO mockTableIndexDAO;
	@Mock
	ProgressCallback mockProgressCallback2;
	@Mock
	ProgressCallback mockProgressCallbackVoid;
	@Mock
	FileHandleDao mockFileDao;
	@Mock
	ColumnModelManager mockColumModelManager;
	@Mock
	TableManagerSupport mockTableManagerSupport;
	@Mock
	TableIndexManager mockIndexManager;
	@Mock
	TableUploadManager mockTableUploadManager;
	@Mock
	TableTransactionDao mockTableTransactionDao;
	@Mock
	NodeManager mockNodeManager;
	@Mock
	EventsCollector mockStatisticsCollector;
	@InjectMocks
	TableEntityManagerImpl manager;
	
	@Captor
	ArgumentCaptor<List<String>> stringListCaptor;

	List<ColumnModel> models;
	
	UserInfo user;
	String tableId;
	IdAndVersion idAndVersion;
	Long tableIdLong;
	List<Row> rows;
	RowSet set;
	RawRowSet rawSet;
	SparseChangeSet sparseChangeSet;
	SparseChangeSet sparseChangeSetWithRowIds;
	PartialRowSet partialSet;
	RawRowSet expectedRawRows;
	RowReferenceSet refSet;
	long rowIdSequence;
	long rowVersionSequence;
	int maxBytesPerRequest;
	List<FileHandleAuthorizationStatus> fileAuthResults;
	TableStatus status;
	String ETAG;
	IdRange range;
	
	SparseChangeSetDto rowDto;
	
	TableSchemaChangeRequest schemaChangeRequest;
	List<ColumnChangeDetails> columChangedetails;
	List<String> newColumnIds;
	
	Long transactionId;
	
	SnapshotRequest snapshotRequest;
	List<ColumnChange> changes;
	IdRange range2;
	IdRange range3;
	
	@BeforeEach
	public void before() throws Exception {
		maxBytesPerRequest = 10000000;
		manager.setMaxBytesPerRequest(maxBytesPerRequest);
		manager.setMaxBytesPerChangeSet(1000000000);
		user = new UserInfo(false, 7L);
		models = TableModelTestUtils.createOneOfEachType(true);
		tableId = "syn123";
		idAndVersion = IdAndVersion.parse(tableId);
		tableIdLong = KeyFactory.stringToKey(tableId);
		rows = TableModelTestUtils.createRows(models, 10);
		set = new RowSet();
		set.setTableId(tableId);
		set.setHeaders(TableModelUtils.getSelectColumns(models));
		set.setRows(rows);
		rawSet = new RawRowSet(TableModelUtils.getIds(models), null, tableId, Lists.newArrayList(rows));
		
		sparseChangeSet = TableModelUtils.createSparseChangeSet(rawSet, models);
		
		// create a sparse rowset with ID
		List<SparseRowDto> sparseRowsWithIds = TableModelTestUtils.createSparseRows(models, 2);
		// assign IDs to each
		Long versionNumber = 101L;
		for(int i=0; i<sparseRowsWithIds.size(); i++){
			SparseRowDto row = sparseRowsWithIds.get(i);
			row.setRowId(new Long(i));
			row.setVersionNumber(versionNumber);
		}
		sparseChangeSetWithRowIds = new SparseChangeSet(tableId, models, sparseRowsWithIds, ETAG);

		List<PartialRow> partialRows = TableModelTestUtils.createPartialRows(models, 10);
		partialSet = new PartialRowSet();
		partialSet.setTableId(tableId);
		partialSet.setRows(partialRows);
		
		rows = TableModelTestUtils.createExpectedFullRows(models, 10);
		expectedRawRows = new RawRowSet(TableModelUtils.getIds(models), null, tableId, rows);
		
		refSet = new RowReferenceSet();
		refSet.setTableId(tableId);
		refSet.setHeaders(TableModelUtils.getSelectColumns(models));
		refSet.setRows(new LinkedList<RowReference>());
		refSet.setEtag("etag123");
		
		rowIdSequence = 0;
		rowVersionSequence = 0;		
		
		status = new TableStatus();
		status.setTableId(tableId);
		status.setState(TableState.PROCESSING);
		status.setChangedOn(new Date(123));
		status.setLastTableChangeEtag("etag");
		ETAG = "";
		
		changes = TableModelTestUtils.createAddUpdateDeleteColumnChange();
		schemaChangeRequest = new TableSchemaChangeRequest();
		schemaChangeRequest.setChanges(changes);
		schemaChangeRequest.setEntityId(tableId);
		
		columChangedetails = createDetailsForChanges(changes);
		

		newColumnIds = Lists.newArrayList("111","333");
		
		range = new IdRange();
		range.setEtag("rangeEtag");
		range.setVersionNumber(3L);
		range.setMaximumId(100L);
		range.setMaximumUpdateId(50L);
		range.setMinimumId(51L);
		
		range2 = new IdRange();
		range2.setEtag("rangeEtag");
		range2.setVersionNumber(4L);
		range2.setMaximumId(100L);
		range2.setMaximumUpdateId(50L);
		range2.setMinimumId(51L);
		
		range3 = new IdRange();
		range3.setEtag("rangeEtag");
		range3.setVersionNumber(5L);
		range3.setMaximumId(100L);
		range3.setMaximumUpdateId(50L);
		range3.setMinimumId(51L);
		
		transactionId = 987L;
		
		rowDto = new SparseChangeSetDto();
		rowDto.setTableId(tableId);
		rowDto.setColumnIds(Lists.newArrayList("123","456"));
		rowDto.setRows(Lists.newArrayList(new SparseRowDto()));
				
		snapshotRequest = new SnapshotRequest();
		snapshotRequest.setSnapshotActivityId("987");
		snapshotRequest.setSnapshotComment("a new comment");
		snapshotRequest.setSnapshotLabel("a new label");
	}

	void setupQueryAsStream() {
		doAnswer(new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				RowHandler handler = (RowHandler) invocation.getArguments()[2];
				// pass each row
				long rowId = 0;
				for(Row row: rows){
					Row copy = new Row();
					copy.setRowId(rowId++);
					copy.setValues(new LinkedList<String>(row.getValues()));
					handler.nextRow(copy);
				}
				return true;
			}
		}).when(mockTableIndexDAO).queryAsStream(isNull(), any(SqlQuery.class), any(RowHandler.class));
	}

	void setUserAsFileHandleCreator() {
		// By default set the user as the creator of all files handles
		doAnswer(new Answer<Set<String>>() {

			@Override
			public Set<String> answer(InvocationOnMock invocation)
					throws Throwable {
				// returning all passed files
				List<String> input = (List<String>) invocation.getArguments()[1];
				if(input != null){
					return new HashSet<String>(input);
				}
				return null;
			}
		}).when(mockFileDao).getFileHandleIdsCreatedByUser(anyLong(), any(List.class));
	}

	void setupNonexclusiveLock() throws Exception {
		// Just call the caller.
		when(mockTableManagerSupport.tryRunWithTableNonexclusiveLock(any(ProgressCallback.class),any(IdAndVersion.class)
				,any(ProgressingCallable.class))).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				if(invocation == null) return null;
				ProgressingCallable<Object> callable = (ProgressingCallable<Object>) invocation.getArguments()[3];
						if (callable != null) {
							return callable.call(mockProgressCallback2);
						} else {
							return null;
						}
			}
		});
	}

	@Test
	public void testAppendRowsUnauthroized() throws DatastoreException, NotFoundException, IOException{
		doThrow(new UnauthorizedException()).when(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
		assertThrows(UnauthorizedException.class, ()->{
			manager.appendRows(user, tableId, set, mockProgressCallback, transactionId);
		});
	}
	
	@Test
	public void testAppendRowsAsStreamUnauthroized() throws DatastoreException, NotFoundException, IOException{
		doThrow(new UnauthorizedException()).when(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
		assertThrows(UnauthorizedException.class, ()->{
			manager.appendRowsAsStream(user, tableId, models, sparseChangeSet.writeToDto().getRows().iterator(),
					"etag",
					null, mockProgressCallback, transactionId);
		});
	}
	
	@Test
	public void testAppendRowsToTable() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		
		// assign a rowId and version to trigger a row level conflict test.
		rawSet.getRows().get(0).setRowId(1L);
		rawSet.getRows().get(0).setVersionNumber(0L);
		
		sparseChangeSet = TableModelUtils.createSparseChangeSet(rawSet, models);
		
		int rowCount = rawSet.getRows().size();
		// Call under test
		RowReferenceSet refSet = manager.appendRowsToTable(user, models, sparseChangeSet, transactionId);
		assertNotNull(refSet);
		assertEquals(tableId, refSet.getTableId());
		assertEquals(range.getEtag(), refSet.getEtag());
		assertEquals(TableModelUtils.getSelectColumns(models),refSet.getHeaders());
		assertNotNull(refSet.getRows());
		assertEquals(rowCount, refSet.getRows().size());
		RowReference firstRef = refSet.getRows().get(0);
		assertEquals(new Long(1), firstRef.getRowId());
		assertEquals(new Long(3), firstRef.getVersionNumber());
		// the rest should be assigned a version
		for(int i=1; i<rowCount; i++){
			RowReference ref = refSet.getRows().get(i);
			assertEquals(new Long(i+range.getMinimumId()-1), ref.getRowId());
			assertEquals(new Long(3), ref.getVersionNumber());
		}
		
		// check stack status
		verify(mockStackStatusDao).getCurrentStatus();
		// check file handles
		verify(mockFileDao).getFileHandleIdsCreatedByUser(eq(user.getId()), stringListCaptor.capture());
		List<String> fileHandes = stringListCaptor.getValue();
		assertNotNull(fileHandes);
		assertEquals(rowCount, fileHandes.size());
		verify(mockTruthDao).reserveIdsInRange(tableId, new Long(rowCount-1));
		// row level conflict test
		verify(mockTruthDao).listRowSetsKeysForTableGreaterThanVersion(tableId, 0L);
		// save the row set
		verify(mockTruthDao).appendRowSetToTable(""+user.getId(), tableId, range.getEtag(), range.getVersionNumber(), models, sparseChangeSet.writeToDto(), transactionId, /* hasFileRefs */ true);
		verify(mockStatisticsCollector, times(1)).collectEvents(any(List.class));
	}
	
	@Test
	public void testAppendRowsHappy() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		RowReferenceSet results = manager.appendRows(user, tableId, set, mockProgressCallback, transactionId);
		assertNotNull(results);
		// verify the table status was set
		verify(mockTableManagerSupport, times(1)).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
	}
	
	/**
	 * Validate that the maximum number of versions is enforced.
	 * @throws DatastoreException
	 * @throws NotFoundException
	 * @throws IOException
	 */
	@Test
	public void testAppendRowsOverLimitPLFM_4774() throws DatastoreException, NotFoundException, IOException{
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		IdRange range = new IdRange();
		range.setVersionNumber(TableEntityManagerImpl.MAXIMUM_VERSIONS_PER_TABLE+1);
		range.setEtag("etag");
		range.setMaximumId(111L);
		range.setMaximumUpdateId(222L);
		range.setMinimumId(1000L);
		when(mockTruthDao.reserveIdsInRange(any(String.class), anyLong())).thenReturn(range);
		try {
			manager.appendRows(user, tableId, set, mockProgressCallback, transactionId);
			fail();
		} catch (IllegalArgumentException e) {
			assertEquals(TableEntityManagerImpl.MAXIMUM_TABLE_SIZE_EXCEEDED, e.getMessage());
		}
	}
	
	@Test
	public void testAppendPartialRowsHappy() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		RowReferenceSet results = manager.appendPartialRows(user, tableId, partialSet, mockProgressCallback, transactionId);
		assertNotNull(results);
		// verify the table status was set
		verify(mockTableManagerSupport, times(1)).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
	}
	
	@Test
	public void testAppendPartialRowsSizeTooLarge() throws DatastoreException, NotFoundException, IOException {
		manager.setMaxBytesPerRequest(1);
		try {
			manager.appendPartialRows(user, tableId, partialSet, mockProgressCallback, transactionId);
			fail("Should have failed");
		} catch (IllegalArgumentException e) {
			assertEquals(String.format(TableModelUtils.EXCEEDS_MAX_SIZE_TEMPLATE, 1), e.getMessage());
		}
	}
	
	/**
	 * This is a test for PLFM-3386
	 * @throws DatastoreException
	 * @throws NotFoundException
	 * @throws IOException
	 */
	@Test
	public void testAppendPartialRowsColumnIdNotFound() throws DatastoreException, NotFoundException, IOException {
		PartialRow partialRow = new PartialRow();
		partialRow.setRowId(null);
		partialRow.setValues(ImmutableMap.of("foo", "updated value 2"));
		partialSet = new PartialRowSet();
		partialSet.setTableId(tableId);
		partialSet.setRows(Arrays.asList(partialRow));
		try {
			manager.appendPartialRows(user, tableId, partialSet, mockProgressCallback, transactionId);
			fail("Should have failed since a column name was used and not an ID.");
		} catch (IllegalArgumentException e) {
			assertEquals("PartialRow.value.key: 'foo' is not a valid column ID for row ID: null", e.getMessage());
		}
		verify(mockTableManagerSupport, never()).setTableToProcessingAndTriggerUpdate(idAndVersion);
	}

	@Test
	public void testAppendRowsAsStreamHappy() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		RowReferenceSet results = new RowReferenceSet();
		TableUpdateResponse response = manager.appendRowsAsStream(user, tableId, models, sparseChangeSet.writeToDto().getRows().iterator(), "etag", results, mockProgressCallback, transactionId);
		assertNotNull(response);
		assertTrue(response instanceof UploadToTableResult);
		UploadToTableResult uploadToTableResult = (UploadToTableResult)response;
		assertNotNull(results);
		assertEquals(tableId, results.getTableId());
		assertEquals(range.getEtag(), results.getEtag());
		assertEquals(TableModelUtils.getSelectColumns(models),results.getHeaders());
		assertNotNull(results.getRows());
		assertEquals(10, results.getRows().size());
		assertEquals(results.getEtag(), uploadToTableResult.getEtag());
		assertEquals(new Long(10), uploadToTableResult.getRowsProcessed());
		// verify the table status was set
		verify(mockTableManagerSupport, times(1)).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockTableManagerSupport).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
		verify(mockTruthDao).hasAtLeastOneChangeOfType(tableId, TableChangeType.ROW);
	}
	
	@Test
	public void testAppendRowsAsStreamPLFM_3155TableNoRows() throws DatastoreException, NotFoundException, IOException{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		// setup an empty table with no rows.
		when(mockTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.ROW)).thenReturn(false);
		String etag = "etag";
		RowReferenceSet results = new RowReferenceSet();
		// call under test
		TableUpdateResponse response = manager.appendRowsAsStream(user, tableId, models, sparseChangeSetWithRowIds.writeToDto().getRows().iterator(), etag, results, mockProgressCallback, transactionId);
		assertNotNull(response);
		
		// a rowIds should be assigned to each row.
		long idsToReserve = sparseChangeSetWithRowIds.getRowCount();
		verify(mockTruthDao).reserveIdsInRange(tableId, idsToReserve);
	}
	
	@Test
	public void testAppendRowsAsStreamPLFM_3155TableWithRows() throws DatastoreException, NotFoundException, IOException{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		// setup at table with rows.
		when(mockTruthDao.hasAtLeastOneChangeOfType(tableId, TableChangeType.ROW)).thenReturn(true);
		String etag = "etag";
		RowReferenceSet results = new RowReferenceSet();
		// call under test
		TableUpdateResponse response = manager.appendRowsAsStream(user, tableId, models, sparseChangeSetWithRowIds.writeToDto().getRows().iterator(), etag, results, mockProgressCallback, transactionId);
		assertNotNull(response);
		// no rowIds should be reserved. Each row should already have a rowId.
		long idsToReserve = 0;
		verify(mockTruthDao).reserveIdsInRange(tableId, idsToReserve);
	}
	
	@Test
	public void testAppendRowsTooLarge() throws Exception{
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		
		// What is the row size for the model?
		int rowSizeBytes = TableModelUtils
				.calculateMaxRowSize(models);
		// Create a rowSet that is too big
		maxBytesPerRequest = 1000;
		manager.setMaxBytesPerRequest(maxBytesPerRequest);
		int tooManyRows = maxBytesPerRequest/rowSizeBytes+1;
		List<Row> rows = TableModelTestUtils.createRows(models, tooManyRows);
		RowSet tooBigSet = new RowSet();
		tooBigSet.setTableId(tableId);
		tooBigSet.setHeaders(TableModelUtils.getSelectColumns(models));
		tooBigSet.setRows(rows);
		try {
			manager.appendRows(user, tableId, tooBigSet, mockProgressCallback, transactionId);
			fail("The passed RowSet should have been too large");
		} catch (IllegalArgumentException e) {
			assertEquals(String.format(TableModelUtils.EXCEEDS_MAX_SIZE_TEMPLATE, maxBytesPerRequest), e.getMessage());
		}
	}
	
	@Test
	public void testAppendRowsAsStreamMultipleBatches() throws DatastoreException, NotFoundException, IOException{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		// calculate the actual size of the first row
		int actualSizeFristRowBytes = TableModelUtils.calculateActualRowSize(sparseChangeSet.writeToDto().getRows().get(0));
		// With this max, there should be three batches (4,8,2)
		manager.setMaxBytesPerChangeSet(actualSizeFristRowBytes*3);
		RowReferenceSet results = new RowReferenceSet();
		TableUpdateResponse response = manager.appendRowsAsStream(user, tableId, models, sparseChangeSet.writeToDto().getRows().iterator(), "etag", results, mockProgressCallback, transactionId);
		assertNotNull(response);
		assertTrue(response instanceof UploadToTableResult);
		UploadToTableResult uploadToTableResult = (UploadToTableResult)response;
		assertEquals(range.getEtag(), uploadToTableResult.getEtag());
		assertEquals(tableId, results.getTableId());
		assertEquals(range.getEtag(), results.getEtag());
		// All ten rows should be referenced
		assertNotNull(results.getRows());
		assertEquals(10, results.getRows().size());
		// Each batch should be assigned its own version number
		assertEquals(new Long(3), results.getRows().get(0).getVersionNumber());
		assertEquals(new Long(4), results.getRows().get(5).getVersionNumber());
		assertEquals(new Long(5), results.getRows().get(9).getVersionNumber());
		// verify the table status was set
		verify(mockTableManagerSupport, times(1)).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
	}


	@Test
	public void testDeleteRowsHappy() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		
		Row row1 = TableModelTestUtils.createDeletionRow(1L, null);
		Row row2 = TableModelTestUtils.createDeletionRow(2L, null);
		rawSet = new RawRowSet(rawSet.getIds(), "aa", tableId, Lists.newArrayList(row1, row2));
		set.setRows(Lists.newArrayList(row1, row2));

		RowSelection rowSelection = new RowSelection();
		rowSelection.setRowIds(Lists.newArrayList(1L, 2L));
		rowSelection.setEtag("aa");

		RowReferenceSet deleteRows = manager.deleteRows(user, tableId, rowSelection);
		assertNotNull(deleteRows);

		// verify the correct row set was generated
		verify(mockTruthDao).appendRowSetToTable(eq(user.getId().toString()), eq(tableId), eq(range.getEtag()), eq(range.getVersionNumber()), anyListOf(ColumnModel.class), any(SparseChangeSetDto.class), anyLong(), eq(false));
		// verify the table status was set
		verify(mockTableManagerSupport, times(1)).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
	}
	
	@Test
	public void testValidateFileHandlesAuthorizedCreatedBy(){
		ColumnModel fileColumn = TableModelTestUtils.createColumn(1L, "a", ColumnType.FILEHANDLEID);
		List<SelectColumn> cols = new ArrayList<SelectColumn>();
		cols.add(TableModelUtils.createSelectColumn(fileColumn));
		
		List<Row> rows = new ArrayList<Row>();
		rows.add(TableModelTestUtils.createRow(1L, 0L, "1"));
		rows.add(TableModelTestUtils.createRow(1L, 0L, "5"));
		
		RowSet rowset = new RowSet();
		rowset.setHeaders(cols);
		rowset.setRows(rows);
		rowset.setTableId("syn123");
		SparseChangeSet sparse = TableModelUtils.createSparseChangeSet(rowset, Lists.newArrayList(fileColumn));
		
		// Setup the user as the creator of the files handles
		when(mockFileDao.getFileHandleIdsCreatedByUser(anyLong(), any(List.class))).thenReturn(Sets.newHashSet("1", "5"));
		
		// call under test
		manager.validateFileHandles(user, tableId, sparse);
		// should check the files created by the user.
		verify(mockFileDao).getFileHandleIdsCreatedByUser(user.getId(), Lists.newArrayList("1","5"));
		// since all of the files were created by the user there is no need to lookup the associated files.
		verify(mockTableIndexDAO, never()).getFileHandleIdsAssociatedWithTable(any(Set.class), any(IdAndVersion.class));
	}
	
	@Test
	public void testValidateFileHandlesAuthorizedCreatedByAndAssociated(){
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);

		ColumnModel fileColumn = TableModelTestUtils.createColumn(1L, "a", ColumnType.FILEHANDLEID);
		List<SelectColumn> cols = new ArrayList<SelectColumn>();
		cols.add(TableModelUtils.createSelectColumn(fileColumn));
		
		List<Row> rows = new ArrayList<Row>();
		rows.add(TableModelTestUtils.createRow(1L, 0L, "1"));
		rows.add(TableModelTestUtils.createRow(1L, 0L, "5"));
		
		RowSet rowset = new RowSet();
		rowset.setHeaders(cols);
		rowset.setRows(rows);
		rowset.setTableId("syn123");
		SparseChangeSet sparse = TableModelUtils.createSparseChangeSet(rowset, Lists.newArrayList(fileColumn));
		
		// Setup 1 to be created by.
		when(mockFileDao.getFileHandleIdsCreatedByUser(anyLong(), any(List.class))).thenReturn(Sets.newHashSet("1"));
		// setup 5 to be associated with.
		when(mockTableIndexDAO.getFileHandleIdsAssociatedWithTable(any(Set.class),any(IdAndVersion.class))).thenReturn(Sets.newHashSet( 5L ));
		
		// call under test
		manager.validateFileHandles(user, tableId, sparse);
		// should check the files created by the user.
		verify(mockFileDao).getFileHandleIdsCreatedByUser(user.getId(), Lists.newArrayList("1","5"));
		// since 1 was created by the user only 5 should be tested for association.
		verify(mockTableIndexDAO).getFileHandleIdsAssociatedWithTable(Sets.newHashSet( 5L ), idAndVersion);
	}
	
	@Test
	public void testValidateFileHandlesUnAuthorized(){
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		ColumnModel fileColumn = TableModelTestUtils.createColumn(1L, "a", ColumnType.FILEHANDLEID);
		List<SelectColumn> cols = new ArrayList<SelectColumn>();
		cols.add(TableModelUtils.createSelectColumn(fileColumn));
		
		List<Row> rows = new ArrayList<Row>();
		rows.add(TableModelTestUtils.createRow(1L, 0L, "1"));
		rows.add(TableModelTestUtils.createRow(1L, 0L, "5"));
		
		RowSet rowset = new RowSet();
		rowset.setHeaders(cols);
		rowset.setRows(rows);
		rowset.setTableId("syn123");
		SparseChangeSet sparse = TableModelUtils.createSparseChangeSet(rowset, Lists.newArrayList(fileColumn));
		
		// Setup 1 to be created by.
		when(mockFileDao.getFileHandleIdsCreatedByUser(anyLong(), any(List.class))).thenReturn(Sets.newHashSet("1"));
		// setup 5 to be not associated with.
		when(mockTableIndexDAO.getFileHandleIdsAssociatedWithTable(any(Set.class), any(IdAndVersion.class))).thenReturn(new HashSet<Long>());
		
		assertThrows(UnauthorizedException.class, ()->{
			// call under test
			manager.validateFileHandles(user, tableId, sparse);
		});
	}
	
	@Test
	public void testChangeFileHandles() throws DatastoreException, NotFoundException, IOException {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		RowSet replace = new RowSet();
		replace.setTableId(tableId);
		replace.setHeaders(TableModelUtils.getSelectColumns(models));
		replace.setEtag("etag");

		List<Row> replaceRows = TableModelTestUtils.createRows(models, 3);
		for (int i = 0; i < 3; i++) {
			replaceRows.get(i).setRowId((long) i);
			replaceRows.get(i).setVersionNumber(0L);
		}
		// different owned filehandle
		replaceRows.get(0).getValues().set(ColumnType.FILEHANDLEID.ordinal(), "3333");
		// erase file handle
		replaceRows.get(1).getValues().set(ColumnType.FILEHANDLEID.ordinal(), null);
		// unowned, but unchanged replaceRows[2]
		replace.setRows(replaceRows);
		
		// call under test
		manager.appendRows(user, tableId, replace, mockProgressCallback, transactionId);

		verify(mockTruthDao).appendRowSetToTable(eq(user.getId().toString()), eq(tableId), eq(range.getEtag()), eq(range.getVersionNumber()), anyListOf(ColumnModel.class), any(SparseChangeSetDto.class), anyLong(), eq(true));

		verify(mockFileDao).getFileHandleIdsCreatedByUser(anyLong(), any(List.class));
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
		verify(mockStatisticsCollector, times(1)).collectEvents(any(List.class));
	}
	
	@Test
	public void testAppendRowsWithoutFileHandles() throws DatastoreException, NotFoundException, IOException {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		RowSet replace = new RowSet();
		replace.setTableId(tableId);
		replace.setHeaders(TableModelUtils.getSelectColumns(models));
		replace.setEtag("etag");

		List<Row> replaceRows = TableModelTestUtils.createRows(models, 1);
		
		replaceRows.get(0).setRowId(0L);
		replaceRows.get(0).setVersionNumber(0L);
		replaceRows.get(0).getValues().set(ColumnType.FILEHANDLEID.ordinal(), null);
		replace.setRows(replaceRows);
		
		// call under test
		manager.appendRows(user, tableId, replace, mockProgressCallback, transactionId);
		

		verify(mockTruthDao).appendRowSetToTable(eq(user.getId().toString()), eq(tableId), eq(range.getEtag()), eq(range.getVersionNumber()), anyListOf(ColumnModel.class), any(SparseChangeSetDto.class), anyLong(), eq(false));

		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
		verify(mockStatisticsCollector, never()).collectEvents(any(List.class));
	}

	@Test
	public void testAddFileHandles() throws DatastoreException, NotFoundException, IOException {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);

		RowSet replace = new RowSet();
		replace.setTableId(tableId);
		replace.setHeaders(TableModelUtils.getSelectColumns(models));

		List<Row> updateRows = TableModelTestUtils.createRows(models, 2);
		for (int i = 0; i < 2; i++) {
			updateRows.get(i).setRowId(null);
		}
		// owned filehandle
		updateRows.get(0).getValues().set(ColumnType.FILEHANDLEID.ordinal(), "3333");
		// null file handle
		updateRows.get(1).getValues().set(ColumnType.FILEHANDLEID.ordinal(), null);
		replace.setRows(updateRows);

		manager.appendRows(user, tableId, replace, mockProgressCallback, transactionId);

		verify(mockTruthDao).appendRowSetToTable(eq(user.getId().toString()), eq(tableId), eq(range.getEtag()), eq(range.getVersionNumber()), anyListOf(ColumnModel.class), any(SparseChangeSetDto.class), anyLong(), eq(true));
		verify(mockFileDao).getFileHandleIdsCreatedByUser(anyLong(), any(List.class));
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
	}

	@Test
	public void testGetCellValues() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableManagerSupport.getTableEntityType(idAndVersion)).thenReturn(EntityType.table);
		setupQueryAsStream();
		
		RowReferenceSet rows = new RowReferenceSet();
		rows.setTableId(tableId);
		rows.setHeaders(TableModelUtils.getSelectColumns(models));
		rows.setEtag("444");
		rows.setRows(Lists.newArrayList(TableModelTestUtils.createRowReference(1L, 2L), TableModelTestUtils.createRowReference(3L, 4L)));

		// call under test
		RowSet result = manager.getCellValues(user, tableId, rows.getRows(), models);
		assertNotNull(result);
		assertEquals(tableId, tableId);
		assertEquals(rows.getHeaders(), result.getHeaders());
		assertNotNull(result.getRows());
		assertEquals(2, result.getRows().size());
		Row row = result.getRows().get(0);
		assertEquals(new Long(1), row.getRowId());
		row = result.getRows().get(1);
		assertEquals(new Long(3), row.getRowId());
		
		verify(mockTableManagerSupport).validateTableReadAccess(user, idAndVersion);
		verify(mockTableManagerSupport).getTableEntityType(idAndVersion);
	}
	
	/**
	 * Test for PLFM-4494, case where user requests file handles that are not on the table.
	 * @throws Exception 
	 */
	@Test
	public void testGetCellValuesMissing() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableManagerSupport.getTableEntityType(idAndVersion)).thenReturn(EntityType.table);
		setupQueryAsStream();
		
		RowReferenceSet rows = new RowReferenceSet();
		rows.setTableId(tableId);
		rows.setHeaders(TableModelUtils.getSelectColumns(models));
		rows.setEtag("444");
		// the second reference does not exist
		rows.setRows(Lists.newArrayList(TableModelTestUtils.createRowReference(1L, 2L), TableModelTestUtils.createRowReference(-1L, -1L)));

		// call under test
		RowSet result = manager.getCellValues(user, tableId, rows.getRows(), models);
		assertNotNull(result);
		assertEquals(tableId, tableId);
		assertEquals(rows.getHeaders(), result.getHeaders());
		assertNotNull(result.getRows());
		assertEquals(1, result.getRows().size());
		Row row = result.getRows().get(0);
		assertEquals(new Long(1), row.getRowId());
		verify(mockTableManagerSupport).validateTableReadAccess(user, idAndVersion);
		verify(mockTableManagerSupport).getTableEntityType(idAndVersion);
	}
	
	@Test
	public void testGetCellValuesNonTable() throws DatastoreException, NotFoundException, IOException {
		// get cell values is only authorized for tables.
		when(mockTableManagerSupport.getTableEntityType(idAndVersion)).thenReturn(EntityType.entityview);
		RowReferenceSet rows = new RowReferenceSet();
		rows.setTableId(tableId);
		rows.setHeaders(TableModelUtils.getSelectColumns(models));
		rows.setEtag("444");
		rows.setRows(Lists.newArrayList(TableModelTestUtils.createRowReference(1L, 2L), TableModelTestUtils.createRowReference(3L, 4L)));

		assertThrows(UnauthorizedException.class, ()->{
			// call under test
			manager.getCellValues(user, tableId, rows.getRows(), models);
		});
	}

	@Test
	public void testGetCellValuesFailNoAccess() throws DatastoreException, NotFoundException, IOException {
		doThrow(new UnauthorizedException()).when(mockTableManagerSupport).validateTableReadAccess(user, idAndVersion);

		assertThrows(UnauthorizedException.class, ()->{
			// call under test
			manager.getCellValues(user, tableId, null, null);
		});
	}

	@Test
	public void testGetCellValue() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableManagerSupport.getTableEntityType(idAndVersion)).thenReturn(EntityType.table);
		setupQueryAsStream();
		
		final int columnIndex = 1;
		RowReference rowRef = new RowReference();
		rowRef.setRowId(1L);
		Row result = manager.getCellValue(user, tableId, rowRef, models.get(columnIndex));
		assertNotNull(result);
		assertEquals("string1", result.getValues().get(0));
		verify(mockTableManagerSupport).validateTableReadAccess(user, idAndVersion);
	}
	
	@Test
	public void testGetCellValueNotFound() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableManagerSupport.getTableEntityType(idAndVersion)).thenReturn(EntityType.table);
		setupQueryAsStream();
		
		final int columnIndex = 1;
		RowReference rowRef = new RowReference();
		rowRef.setRowId(-1L);
		
		assertThrows(NotFoundException.class, ()->{
			// call under test.
			manager.getCellValue(user, tableId, rowRef, models.get(columnIndex));
		});
	}

	@Test
	public void testGetColumnValuesFailReadAccess() throws Exception {
		doThrow(new UnauthorizedException()).when(mockTableManagerSupport).validateTableReadAccess(user, idAndVersion);

		assertThrows(UnauthorizedException.class, ()->{
			// call under test.
			manager.getCellValue(user, tableId, null, null);
		});
	}
	
	@Test
	public void testPLFM_3041ReadOnly() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		// Start in read-write then go to read-only
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE, StatusEnum.READ_WRITE, StatusEnum.READ_ONLY);

		// three batches with this size
		manager.setMaxBytesPerChangeSet(300);
		RowReferenceSet results = new RowReferenceSet();

		assertThrows(ReadOnlyException.class, ()->{
			// call under test.
			manager.appendRowsAsStream(user, tableId, models, sparseChangeSet.writeToDto().getRows().iterator(),
					"etag",
					results, mockProgressCallback, transactionId);
		});
	}
	
	@Test
	public void testPLFM_3041Down() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		// Start in read-write then go to down
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE, StatusEnum.READ_WRITE, StatusEnum.DOWN);
		// three batches with this size
		manager.setMaxBytesPerChangeSet(300);
		RowReferenceSet results = new RowReferenceSet();

		assertThrows(ReadOnlyException.class, ()->{
			// call under test.
			manager.appendRowsAsStream(user, tableId, models, sparseChangeSet.writeToDto().getRows().iterator(),
					"etag",
					results, mockProgressCallback, transactionId);
		});
	}
	
	@Test
	public void testGetFileHandleIdsAssociatedWithTableHappy(){
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		TableRowChange lastChange = new TableRowChange();
		lastChange.setRowVersion(3L);
		when(mockTruthDao.getLastTableRowChange(tableId)).thenReturn(lastChange);
		Set<Long> input = Sets.newHashSet(0L, 1L, 2L, 3L);
		Set<Long> results = Sets.newHashSet(1L,2L);
		when(mockTableIndexDAO.getFileHandleIdsAssociatedWithTable(input, idAndVersion)).thenReturn(results);
		// call under test.
		Set<Long> out = manager.getFileHandleIdsAssociatedWithTable(tableId, input);
		assertEquals(results, out);
	}
	
	@Test
	public void testGetFileHandleIdsAssociatedWithTableNoChanges(){
		// no changes applied to the table.
		when(mockTruthDao.getLastTableRowChange(tableId)).thenReturn(null);
		Set<Long> input = Sets.newHashSet(0L, 1L, 2L, 3L);
		// call under test.
		Set<Long> out = manager.getFileHandleIdsAssociatedWithTable(tableId, input);
		assertNotNull(out);
		assertTrue(out.isEmpty());
	}
	
	@Test
	public void testGetFileHandleIdsAssociatedWithTableAlternateSignature() throws Exception{

		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		TableRowChange lastChange = new TableRowChange();
		lastChange.setRowVersion(3L);
		when(mockTruthDao.getLastTableRowChange(tableId)).thenReturn(lastChange);
		List<String> input = Lists.newArrayList("0","1","2","3");
		Set<Long> results = Sets.newHashSet(1L,2L);
		when(mockTableIndexDAO.getFileHandleIdsAssociatedWithTable(any(Set.class), any(IdAndVersion.class))).thenReturn(results);
		// call under test.
		Set<String> out = manager.getFileHandleIdsAssociatedWithTable(tableId, input);
		Set<String> expected = Sets.newHashSet("1","2");
		assertEquals(expected, out);
	}
	
	@Test
	public void testGetFileHandleIdsNotAssociatedWithTable(){
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		Set<Long> input = ImmutableSet.of(0L, 1L, 2L, 3L);
		Set<Long> results = Sets.newHashSet(1L,2L);
		when(mockTableIndexDAO.getFileHandleIdsAssociatedWithTable(any(Set.class), any(IdAndVersion.class))).thenReturn(results);
		// call under test.
		Set<Long> out = manager.getFileHandleIdsNotAssociatedWithTable(tableId, input);
		Set<Long> expected = ImmutableSet.of(0L, 3L);
		assertEquals(expected, out);
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testSetTableSchemaLockUnavailableException() throws Exception{
		// setup success.
		doAnswer(new Answer<Void>(){
			@Override
			public Void answer(InvocationOnMock invocation) throws Throwable {
				throw new LockUnavilableException("No Lock for you!");
			}}).when(mockTableManagerSupport).tryRunWithTableExclusiveLock(any(ProgressCallback.class), any(IdAndVersion.class), any(ProgressingCallable.class));
		
		List<String> schema = Lists.newArrayList("111","222");

		assertThrows(TemporarilyUnavailableException.class, ()->{
			// call under test.
			manager.setTableSchema(user, schema, tableId);
		});
	}
	
	@Test
	public void testDeleteTable(){
		// call under test
		manager.deleteTable(tableId);
		verify(mockColumModelManager).unbindAllColumnsAndOwnerFromObject(tableId);
		verify(mockTruthDao).deleteAllRowDataForTable(tableId);
		verify(mockTableTransactionDao).deleteTable(tableId);
	}
	
	@Test
	public void testSetTableDeleted(){
		// call under test
		manager.setTableAsDeleted(tableId);
		verify(mockTableManagerSupport).setTableDeleted(idAndVersion, ObjectType.TABLE);
	}
	
	@Test
	public void testIsTemporaryTableNeededToValidateAddAndDelete(){
		List<ColumnChange> changes = new LinkedList<ColumnChange>();
		
		ColumnChange delete = new ColumnChange();
		delete.setOldColumnId("123");
		delete.setNewColumnId(null);
		
		ColumnChange add = new ColumnChange();
		add.setOldColumnId(null);
		add.setNewColumnId("123");
		
		
		changes.add(delete);
		changes.add(add);
		// deletes and adds do not require a temp table.
		assertFalse(TableEntityManagerImpl.containsColumnUpdate(changes));
	}
	
	@Test
	public void testIsTemporaryTableNeededToValidateUpdate(){
		List<ColumnChange> changes = new LinkedList<ColumnChange>();
		ColumnChange update = new ColumnChange();
		update.setOldColumnId("123");
		update.setNewColumnId("456");
		changes.add(update);
		assertTrue(TableEntityManagerImpl.containsColumnUpdate(changes));
	}
	
	@Test
	public void testIsTemporaryTableNeededToValidateUpdateNoChange(){
		List<ColumnChange> changes = new LinkedList<ColumnChange>();
		ColumnChange update = new ColumnChange();
		update.setOldColumnId("123");
		update.setNewColumnId("123");
		changes.add(update);
		assertFalse(TableEntityManagerImpl.containsColumnUpdate(changes));
	}
	
	@Test
	public void testIsTemporaryTableNeededToValidate(){
		List<ColumnChange> changes = new LinkedList<ColumnChange>();
		ColumnChange update = new ColumnChange();
		update.setOldColumnId("123");
		update.setNewColumnId("456");
		changes.add(update);
		TableSchemaChangeRequest request = new TableSchemaChangeRequest();
		request.setChanges(changes);
		// call under test
		assertTrue(manager.isTemporaryTableNeededToValidate(request));
	}
	
	@Test
	public void testIsTemporaryTableNeededToValidateUploadToTableRequest(){
		UploadToTableRequest request = new UploadToTableRequest();
		request.setTableId(tableId);
		// call under test
		assertFalse(manager.isTemporaryTableNeededToValidate(request));
	}
	
	@Test
	public void testIsTemporaryTableNeededToValidateAppendableRowSetRequest(){
		AppendableRowSetRequest request = new AppendableRowSetRequest();
		request.setEntityId(tableId);
		// call under test
		assertFalse(manager.isTemporaryTableNeededToValidate(request));
	}
	
	@Test
	public void testIsTemporaryTableNeededToValidateUnknown(){
		TableUpdateRequest mockRequest = Mockito.mock(TableUpdateRequest.class);

		assertThrows(IllegalArgumentException.class, ()->{
			// call under test.
			manager.isTemporaryTableNeededToValidate(mockRequest);
		});
	}
	
	@Test
	public void testValidateUpdateNullProgress(){
		mockProgressCallbackVoid = null;

		assertThrows(IllegalArgumentException.class, ()->{
			// Call under test
			manager.validateUpdateRequest(mockProgressCallbackVoid, user, schemaChangeRequest, mockIndexManager);
		});
	}
	
	@Test
	public void testValidateUpdateNullUser(){
		user = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// Call under test
			manager.validateUpdateRequest(mockProgressCallbackVoid, user, schemaChangeRequest, mockIndexManager);
		});
	}
	
	@Test
	public void testValidateUpdateNullRequest(){
		schemaChangeRequest = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// Call under test
			manager.validateUpdateRequest(mockProgressCallbackVoid, user, schemaChangeRequest, mockIndexManager);
		});
	}
	
	@Test
	public void testValidateUpdateUnknownRequest(){
		TableUpdateRequest unknown = Mockito.mock(TableUpdateRequest.class);
		assertThrows(IllegalArgumentException.class, ()->{
			// Call under test
			manager.validateUpdateRequest(mockProgressCallbackVoid, user, unknown, mockIndexManager);
		});
	}
	
	@Test
	public void testValidateUpdateUploadToTableRequest(){
		UploadToTableRequest request = new UploadToTableRequest();
		// Call under test
		manager.validateUpdateRequest(mockProgressCallbackVoid, user, request, mockIndexManager);
	}
	
	@Test
	public void testValidateUpdateAppendableRowSetRequest(){
		AppendableRowSetRequest request = new AppendableRowSetRequest();
		// Call under test
		manager.validateUpdateRequest(mockProgressCallbackVoid, user, request, mockIndexManager);
	}
	
	@Test
	public void testValidateUpdateRequestSchema(){
		when(mockColumModelManager.getColumnChangeDetails(changes)).thenReturn(columChangedetails);
		when(mockColumModelManager.calculateNewSchemaIdsAndValidate(tableId, changes, null)).thenReturn(newColumnIds);
		
		// Call under test
		manager.validateSchemaUpdateRequest(mockProgressCallbackVoid, user, schemaChangeRequest, mockIndexManager);
		verify(mockColumModelManager).calculateNewSchemaIdsAndValidate(tableId, schemaChangeRequest.getChanges(), schemaChangeRequest.getOrderedColumnIds());
		verify(mockIndexManager).alterTempTableSchmea(idAndVersion, columChangedetails);
	}
	
	@Test
	public void testValidateUpdateRequestSchemaNullManagerWithUpdate(){
		mockIndexManager = null;

		assertThrows(IllegalStateException.class, ()->{
			// Call under test
			manager.validateSchemaUpdateRequest(mockProgressCallbackVoid, user, schemaChangeRequest, mockIndexManager);
		});
	}
	
	@Test
	public void testValidateUpdateRequestSchemaNoUpdate(){
		// this case only contains an add (no update)
		ColumnChange add = new ColumnChange();
		add.setNewColumnId("111");
		add.setOldColumnId(null);
		
		List<ColumnChange> changes = Lists.newArrayList(add);
		TableSchemaChangeRequest request = new TableSchemaChangeRequest();
		request.setChanges(changes);
		request.setEntityId(tableId);
		List<String> newColumnIds = Lists.newArrayList("111");
		request.setOrderedColumnIds(newColumnIds);
		
		// Call under test
		manager.validateSchemaUpdateRequest(mockProgressCallbackVoid, user, request, null);
		verify(mockColumModelManager).calculateNewSchemaIdsAndValidate(tableId, changes, newColumnIds);
		// temp table should not be used.
		verify(mockIndexManager, never()).alterTempTableSchmea(any(IdAndVersion.class), anyListOf(ColumnChangeDetails.class));
	}
	
	@Test
	public void testUpdateTable() throws Exception{
		when(mockColumModelManager.calculateNewSchemaIdsAndValidate(tableId, changes, null)).thenReturn(newColumnIds);
		// call under test.
		manager.updateTable(mockProgressCallbackVoid, user, schemaChangeRequest, transactionId);
		verify(mockTableManagerSupport).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockTableManagerSupport).touchTable(user, tableId);
	}
	
	@Test
	public void testUpdateTableUploadToTableRequest() throws IOException{
		UploadToTableRequest request = new UploadToTableRequest();
		request.setTableId(tableId);
		// call under test.
		manager.updateTable(mockProgressCallbackVoid, user, request, transactionId);
		verify(mockTableUploadManager).uploadCSV(eq(mockProgressCallbackVoid), eq(user), eq(request), any(UploadRowProcessor.class));
		verify(mockTableManagerSupport).touchTable(user, tableId);
	}
	
	@Test
	public void testUpdateTableAppendableRowSetRequest() throws IOException{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		AppendableRowSetRequest request = new AppendableRowSetRequest();
		request.setToAppend(partialSet);
		// call under test.
		manager.updateTable(mockProgressCallbackVoid, user, request, transactionId);
		verify(mockTruthDao).getLastTableRowChange(tableId, TableChangeType.ROW);
		verify(mockTableManagerSupport).touchTable(user, tableId);
	}
	
	@Test
	public void testUpdateTableNullProgress() throws IOException{
		mockProgressCallbackVoid = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test.
			manager.updateTable(mockProgressCallbackVoid, user, schemaChangeRequest, transactionId);
		});
	}
	
	@Test
	public void testUpdateTableNullUser() throws IOException{
		user = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test.
			manager.updateTable(mockProgressCallbackVoid, user, schemaChangeRequest, transactionId);
		});
	}
	
	@Test
	public void testUpdateTableNullRequset() throws IOException{
		schemaChangeRequest = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test.
			manager.updateTable(mockProgressCallbackVoid, user, schemaChangeRequest, transactionId);
		});
	}
	
	@Test
	public void testUpdateTableUnkownRequset() throws IOException{
		TableUpdateRequest unknown = Mockito.mock(TableUpdateRequest.class);
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test.
			manager.updateTable(mockProgressCallbackVoid, user, unknown, transactionId);
		});
	}
	
	@Test
	public void testAppendToTablePartial(){
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		AppendableRowSetRequest request = new AppendableRowSetRequest();
		request.setToAppend(partialSet);
		// call under test
		TableUpdateResponse response = manager.appendToTable(mockProgressCallback, user, request, transactionId);
		assertNotNull(response);
		assertTrue(response instanceof RowReferenceSetResults);
		RowReferenceSetResults  rrsr = (RowReferenceSetResults) response;
		assertNotNull(rrsr.getRowReferenceSet());
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
		verify(mockTruthDao).getLastTableRowChange(tableId, TableChangeType.ROW);
	}
	
	@Test
	public void testAppendToTableRowSet() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		setUserAsFileHandleCreator();
		when(mockColumModelManager.getColumnModelsForTable(user, tableId)).thenReturn(models);
		when(mockTruthDao.reserveIdsInRange(eq(tableId), anyLong())).thenReturn(range, range2, range3);
		when(mockTruthDao.hasAtLeastOneChangeOfType(anyString(), any(TableChangeType.class))).thenReturn(true);
		
		AppendableRowSetRequest request = new AppendableRowSetRequest();
		request.setToAppend(set);
		// call under test
		TableUpdateResponse response = manager.appendToTable(mockProgressCallback, user, request, transactionId);
		assertNotNull(response);
		assertTrue(response instanceof RowReferenceSetResults);
		RowReferenceSetResults  rrsr = (RowReferenceSetResults) response;
		assertNotNull(rrsr.getRowReferenceSet());
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
	}
	
	@Test
	public void testAppendToTableRowSetNull(){
		AppendableRowSetRequest request = new AppendableRowSetRequest();
		request.setToAppend(null);
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.appendToTable(mockProgressCallback, user, request, transactionId);
		});
	}
	
	@Test
	public void testUpdateTableSchema() throws IOException{

		when(mockColumModelManager.calculateNewSchemaIdsAndValidate(tableId, changes, null)).thenReturn(newColumnIds);
		
		when(mockColumModelManager.getColumnIdsForTable(idAndVersion)).thenReturn(new LinkedList<>());
		when(mockColumModelManager.bindColumnsToDefaultVersionOfObject(newColumnIds, tableId)).thenReturn(models);
		// call under test.
		TableSchemaChangeResponse response = manager.updateTableSchema(mockProgressCallbackVoid, user, schemaChangeRequest, transactionId);
		assertNotNull(response);
		assertEquals(models, response.getSchema());
		verify(mockColumModelManager).calculateNewSchemaIdsAndValidate(tableId, schemaChangeRequest.getChanges(), schemaChangeRequest.getOrderedColumnIds());
		verify(mockColumModelManager).getColumnIdsForTable(idAndVersion);
		verify(mockTableManagerSupport).touchTable(user, tableId);
		verify(mockTruthDao).appendSchemaChangeToTable(""+user.getId(), tableId, newColumnIds, schemaChangeRequest.getChanges(), transactionId);
		verify(mockTableManagerSupport).setTableToProcessingAndTriggerUpdate(idAndVersion);
		verify(mockColumModelManager, never()).getColumnModelsForObject(any(IdAndVersion.class));
	}

	@Test
	public void testUpdateTableSchemaNoUpdate() throws Exception{
		when(mockColumModelManager.getColumnModelsForObject(idAndVersion)).thenReturn(models);
		when(mockColumModelManager.getColumnModelsForObject(idAndVersion)).thenReturn(models);
		when(mockColumModelManager.calculateNewSchemaIdsAndValidate(tableId, changes, null)).thenReturn(newColumnIds);
		
		List<String> currentSchema = Lists.newArrayList("111","222");
		when(mockColumModelManager.getColumnIdsForTable(idAndVersion)).thenReturn(currentSchema);
		// The new schema matches the current
		List<String> newSchemaIds = Lists.newArrayList("111","222");
		when(mockColumModelManager.calculateNewSchemaIdsAndValidate(tableId, schemaChangeRequest.getChanges(), null)).thenReturn(newSchemaIds);
		// call under test.
		TableSchemaChangeResponse response = manager.updateTableSchema(mockProgressCallbackVoid, user, schemaChangeRequest, transactionId);
		assertNotNull(response);
		assertEquals(models, response.getSchema());
		verify(mockColumModelManager).calculateNewSchemaIdsAndValidate(tableId, schemaChangeRequest.getChanges(), schemaChangeRequest.getOrderedColumnIds());
		verify(mockColumModelManager).getColumnIdsForTable(idAndVersion);
		verify(mockTableManagerSupport, never()).touchTable(any(UserInfo.class), anyString());
		verify(mockTruthDao, never()).appendSchemaChangeToTable(anyString(), anyString(), any(List.class), any(List.class), anyLong());
		verify(mockTableManagerSupport, never()).setTableToProcessingAndTriggerUpdate(any(IdAndVersion.class));
		verify(mockColumModelManager).getColumnModelsForObject(idAndVersion);
	}
	
	@Test
	public void  testGetSchemaChangeForVersion() throws IOException{
		when(mockColumModelManager.getColumnChangeDetails(changes)).thenReturn(columChangedetails);
		
		long versionNumber = 123L;
		when(mockTruthDao.getSchemaChangeForVersion(tableId, versionNumber)).thenReturn(schemaChangeRequest.getChanges());
		// call under test
		List<ColumnChangeDetails> details = manager.getSchemaChangeForVersion(tableId, versionNumber);
		assertEquals(columChangedetails, details);
		verify(mockTruthDao).getSchemaChangeForVersion(tableId, versionNumber);
		verify(mockColumModelManager).getColumnChangeDetails(schemaChangeRequest.getChanges());
	}
	
	@Test
	public void testCheckForRowLevelConflictWithConflict() throws IOException{
		String etag = "anEtag";
		Long etagVersion = 25L;
		when(mockTruthDao.getVersionForEtag(tableId, etag)).thenReturn(25L);
		TableRowChange change = new TableRowChange();
		change.setKeyNew("someKey");
		change.setChangeType(TableChangeType.ROW);
		when(mockTruthDao.listRowSetsKeysForTableGreaterThanVersion(tableId, etagVersion)).thenReturn(Lists.newArrayList(change));
		SparseChangeSetDto conflictUpdate = new SparseChangeSetDto();
		SparseRowDto conflictRow = new SparseRowDto();
		conflictRow.setRowId(0L);
		conflictUpdate.setRows(Lists.newArrayList(conflictRow));
		when(mockTruthDao.getRowSet(change)).thenReturn(conflictUpdate);
		
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		SparseChangeSet changeSet = new SparseChangeSet(tableId, columns);
		changeSet.setEtag(etag);
		
		// add some rows
		SparseRow row = changeSet.addEmptyRow();
		row.setRowId(0L);
		row.setVersionNumber(2L);
		row.setCellValue("1", "1.1");
		
		row = changeSet.addEmptyRow();
		row.setRowId(1L);
		row.setVersionNumber(1L);
		row.setCellValue("1", "2.1");
		
		try {
			manager.checkForRowLevelConflict(tableId, changeSet);
			fail("Should have failed.");
		} catch (ConflictingUpdateException e) {
			assertTrue(e.getMessage().startsWith(""));
		}
		// The etag version should be used to list the values
		verify(mockTruthDao).listRowSetsKeysForTableGreaterThanVersion(tableId, etagVersion);
	}
	
	
	@Test
	public void testCheckForRowLevelConflictNoEtag() throws IOException{
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		SparseChangeSet changeSet = new SparseChangeSet(tableId, columns);
		changeSet.setEtag(null);
		
		// add some rows
		SparseRow row = changeSet.addEmptyRow();
		row.setRowId(0L);
		row.setVersionNumber(2L);
		row.setCellValue("1", "1.1");
		
		row = changeSet.addEmptyRow();
		row.setRowId(1L);
		row.setVersionNumber(1L);
		row.setCellValue("1", "2.1");
		
		manager.checkForRowLevelConflict(tableId, changeSet);
		// All versions greater than two should be scanned
		verify(mockTruthDao).listRowSetsKeysForTableGreaterThanVersion(tableId, 2L);
	}
	
	@Test
	public void testCheckForRowLevelConflictWithEtag() throws IOException{
		String etag = "anEtag";
		Long etagVersion = 25L;
		when(mockTruthDao.getVersionForEtag(tableId, etag)).thenReturn(25L);
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		SparseChangeSet changeSet = new SparseChangeSet(tableId, columns);
		changeSet.setEtag(etag);
		
		// add some rows
		SparseRow row = changeSet.addEmptyRow();
		row.setRowId(0L);
		row.setVersionNumber(2L);
		row.setCellValue("1", "1.1");
		
		row = changeSet.addEmptyRow();
		row.setRowId(1L);
		row.setVersionNumber(1L);
		row.setCellValue("1", "2.1");
		
		manager.checkForRowLevelConflict(tableId, changeSet);
		// The etag version should be used to list the values
		verify(mockTruthDao).listRowSetsKeysForTableGreaterThanVersion(tableId, etagVersion);
	}
	
	@Test
	public void testCheckForRowLevleConflictNoUpdate() throws IOException{
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		SparseChangeSet changeSet = new SparseChangeSet(tableId, columns);
		// the row does not have a rowId so it should not conflict
		SparseRow row = changeSet.addEmptyRow();
		row.setCellValue("1", "1.1");
		manager.checkForRowLevelConflict(tableId, changeSet);
		// the dao should not be used when no rows are updated.
		verifyNoMoreInteractions(mockTruthDao);
	}
	
	/**
	 * This is a test for PLFM-3355
	 * @throws IOException
	 */
	@Test
	public void testCheckForRowLevelConflictNullVersionNumber() throws IOException{
		List<ColumnModel> columns = TableModelTestUtils.createOneOfEachType();
		SparseChangeSet changeSet = new SparseChangeSet(tableId, columns);
		SparseRow row = changeSet.addEmptyRow();
		row.setRowId(1L);
		row.setVersionNumber(null);
		row.setCellValue("1", "1.1");
		// This should fail as a null version number is passed in. 
		try {
			manager.checkForRowLevelConflict(tableId, changeSet);
			fail("should have failed");
		} catch (IllegalArgumentException e) {
			assertEquals("Row version number cannot be null", e.getMessage());
		}
	}
	
	@Test
	public void testGetSparseChangeSetNewKeyNull() throws NotFoundException, IOException{
		Long versionNumber = 101L;
		TableRowChange change = new TableRowChange();
		change.setTableId(tableId);
		change.setRowVersion(versionNumber);
		// when the new key is null the change set needs to be upgraded.
		change.setKeyNew(null);
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.getSparseChangeSet(change);
		});
	}
	
	@Test
	public void testGetSparseChangeSetNewChangeNull() throws NotFoundException, IOException{
		TableRowChange change = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.getSparseChangeSet(change);
		});
	}
	
	
	/**
	 * Test added for PLFM-4284 which was caused by incorrect row size
	 * calculations.
	 * @throws Exception
	 */
	@Test
	public void testRowSize() throws Exception{
		int numberColumns = 200;
		int numberRows = 1000;
		int stringSize = 50;
		List<String[]> input = new LinkedList<>();
		for(int rowIndex=0; rowIndex<numberRows; rowIndex++){
			String[] row = new String[numberColumns];
			input.add(row);
			for(int colIndex=0; colIndex<numberColumns; colIndex++){
				char[] chars = new char[stringSize];
				Arrays.fill(chars, 'a');
				row[colIndex] = new String(chars);
			}
		}
		CSVReader reader = TableModelTestUtils.createReader(input);
		List<ColumnModel> columns = new LinkedList<>();
		// Create some columns
		for(int i=0; i<numberColumns; i++){
			columns.add(TableModelTestUtils.createColumn(i));
		}
		// Create the iterator.
		Iterator<SparseRowDto> iterator = new CSVToRowIterator(columns, reader, false, null);
		long startMemory = getMemoryUsed();
		List<SparseRowDto> rows = new LinkedList<>();
		for(int i=0; i<numberRows; i++){
			rows.add(iterator.next());
		}
		long endMemory = getMemoryUsed();
		int calcuatedSize = TableModelUtils.calculateActualRowSize(rows.get(0));
		long sizePerRow = (endMemory - startMemory)/numberRows;
		assertTrue(calcuatedSize > sizePerRow, "Calculated memory: "+calcuatedSize+" bytes actual memory: "+sizePerRow+" bytes");
	}
	
	/**
	 * Test added for PLFM-4284 which was caused by incorrect row size
	 * calculations for empty rows.
	 * @throws Exception
	 */
	@Test
	public void testRowSizeNullValues() throws Exception{
		int numberColumns = 200;
		int numberRows = 1000;
		List<String[]> input = new LinkedList<>();
		for(int rowIndex=0; rowIndex<numberRows; rowIndex++){
			String[] row = new String[numberColumns];
			input.add(row);
		}
		CSVReader reader = TableModelTestUtils.createReader(input);
		List<ColumnModel> columns = new LinkedList<>();
		// Create some columns
		for(int i=0; i<numberColumns; i++){
			columns.add(TableModelTestUtils.createColumn(i));
		}
		// Create the iterator.
		Iterator<SparseRowDto> iterator = new CSVToRowIterator(columns, reader, false, null);
		long startMemory = getMemoryUsed();
		List<SparseRowDto> rows = new LinkedList<>();
		for(int i=0; i<numberRows; i++){
			rows.add(iterator.next());
		}
		long endMemory = getMemoryUsed();
		int calcuatedSize = TableModelUtils.calculateActualRowSize(rows.get(0));
		long sizePerRow = (endMemory - startMemory)/numberRows;
		assertTrue(calcuatedSize > sizePerRow, "Calculated memory: "+calcuatedSize+" bytes actual memory: "+sizePerRow+" bytes");
	}
	
	@Test
	public void testTeleteTableIfDoesNotExistShouldNotDelete() {
		// the table exists
		when(mockTableManagerSupport.doesTableExist(idAndVersion)).thenReturn(true);
		//call under test
		manager.deleteTableIfDoesNotExist(tableId);
		// since the table exist do not delete anything
		verify(mockColumModelManager, never()).unbindAllColumnsAndOwnerFromObject(anyString());
		verify(mockTruthDao, never()).deleteAllRowDataForTable(anyString());
		verify(mockTableManagerSupport, never()).setTableDeleted(any(IdAndVersion.class), any(ObjectType.class));
	}
	
	@Test
	public void testTeleteTableIfDoesNotExistShouldDelete() {
		// the table does not exist
		when(mockTableManagerSupport.doesTableExist(idAndVersion)).thenReturn(false);
		//call under test
		manager.deleteTableIfDoesNotExist(tableId);
		// since the table does not exist, delete all of the table's data.
		verify(mockColumModelManager).unbindAllColumnsAndOwnerFromObject(tableId);
		verify(mockTruthDao).deleteAllRowDataForTable(tableId);
		// deleting the table should not send out another delete change. (PLFM-4799).
		verify(mockTableManagerSupport, never()).setTableDeleted(any(IdAndVersion.class), any(ObjectType.class));
	}
	
	/**
	 * Calculate the current used memory.
	 * 
	 * @return
	 */
	public long getMemoryUsed(){
		System.gc();
		return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}
	
	@Test
	public void testGetTableSchemaNoVersion(){
		when(mockColumModelManager.getColumnIdsForTable(idAndVersion)).thenReturn(newColumnIds);
		List<String> retrievedSchema = manager.getTableSchema(idAndVersion);
		assertEquals(newColumnIds, retrievedSchema);
		// should only lookup the current version when a a version number is requested.
		verify(mockNodeManager, never()).getCurrentRevisionNumber(anyString());
	}
	
	/**
	 * Create test details for given column changes.
	 * 
	 * @param changes
	 * @return
	 */
	public static List<ColumnChangeDetails> createDetailsForChanges(List<ColumnChange> changes){
		List<ColumnChangeDetails> results = new LinkedList<>();
		for(ColumnChange change: changes){
			ColumnModel oldModel = null;
			ColumnModel newModel = null;
			if(change.getOldColumnId() != null){
				oldModel = TableModelTestUtils.createColumn(Long.parseLong(change.getOldColumnId()));
			}
			if(change.getNewColumnId() != null){
				newModel = TableModelTestUtils.createColumn(Long.parseLong(change.getNewColumnId()));
			}
			results.add(new ColumnChangeDetails(oldModel, newModel));
		}
		return results;
	}
	
	@Test
	public void testGetTableChangePage() throws IOException {
		long limit = 3L;
		long offset = 0L;
		when(mockTruthDao.getRowSet(any(TableRowChange.class))).thenReturn(rowDto);
		when(mockTruthDao.getTableChangePage(tableId, limit, offset)).thenReturn(createChange(tableId, (int) limit));
		// call under test
		List<TableChangeMetaData> results = manager.getTableChangePage(tableId, limit, offset);
		assertNotNull(results);
		assertEquals((int)limit, results.size());
		verify(mockTruthDao).getTableChangePage(tableId, limit, offset);
		// at this point only metadata should be loaded and not the actual changes
		verify(mockTruthDao, never()).getRowSet(any(TableRowChange.class));
		verify(mockTruthDao, never()).getSchemaChangeForVersion(anyString(), anyLong());
		
		// one 
		TableChangeMetaData metaOne = results.get(0);
		assertEquals(new Long(0), metaOne.getChangeNumber());
		assertEquals(TableChangeType.ROW, metaOne.getChangeType());
		// load the row.
		ChangeData<SparseChangeSet> changeData = metaOne.loadChangeData(SparseChangeSet.class);
		assertNotNull(changeData);
		assertEquals(0L, changeData.getChangeNumber());
		verify(mockTruthDao, times(1)).getRowSet(any(TableRowChange.class));
		
		// two
		TableChangeMetaData metaTwp = results.get(1);
		assertEquals(new Long(1), metaTwp.getChangeNumber());
		assertEquals(TableChangeType.COLUMN, metaTwp.getChangeType());
		// load the row.
		ChangeData<SchemaChange> schemaChange = metaTwp.loadChangeData(SchemaChange.class);
		assertNotNull(schemaChange);
		assertEquals(1L, schemaChange.getChangeNumber());
		verify(mockTruthDao, times(1)).getSchemaChangeForVersion(tableId, 1L);
	}
	
	@Test
	public void testSetTableSchema() throws Exception {
		List<String> newSchema = Lists.newArrayList("1", "2");
		// call under test
		manager.setTableSchema(user, newSchema, tableId);
		verify(mockTableManagerSupport).tryRunWithTableExclusiveLock(any(ProgressCallback.class),
				any(IdAndVersion.class), any(ProgressingCallable.class));
	}
	
	/**
	 * LockUnavilableException should translate to TemporarilyUnavailableException.
	 * @throws Exception
	 */
	@Test
	public void testSetTableSchemaLockUnavilableException() throws Exception {
		LockUnavilableException exception = new LockUnavilableException("No lock");
		when(mockTableManagerSupport.tryRunWithTableExclusiveLock(any(ProgressCallback.class),
				any(IdAndVersion.class), any(ProgressingCallable.class))).thenThrow(exception);
		List<String> newSchema = Lists.newArrayList("1", "2");
		assertThrows(TemporarilyUnavailableException.class, ()->{
			// call under test
			manager.setTableSchema(user, newSchema, tableId);
		});
	}
	
	@Test
	public void testSetTableSchemaWithExclusiveLock() {
		List<String> oldSchema = Lists.newArrayList("1","2");
		when(mockColumModelManager.getColumnIdsForTable(idAndVersion)).thenReturn(oldSchema);
		List<String> newSchema = Lists.newArrayList("2","3");
		List<ColumnChange> expectedChanges = TableModelUtils.createChangesFromOldSchemaToNew(oldSchema, newSchema);
		// call under test
		manager.setTableSchemaWithExclusiveLock(mockProgressCallback, user, newSchema, tableId);
		verify(mockTableTransactionDao).startTransaction(tableId, user.getId());
		verify(mockTableManagerSupport).touchTable(user, tableId);
		verify(mockColumModelManager).calculateNewSchemaIdsAndValidate(tableId, expectedChanges, newSchema);
	}
	
	@Test
	public void testLinkVersionToTransaction() {
		long version = 14;
		when(mockTableTransactionDao.getTableIdWithLock(transactionId)).thenReturn(tableIdLong);
		// call under test
		manager.linkVersionToTransaction(tableId, version, transactionId);
		verify(mockTableTransactionDao).getTableIdWithLock(transactionId);
		verify(mockTableTransactionDao).linkTransactionToVersion(transactionId, version);
		verify(mockTableTransactionDao).updateTransactionEtag(transactionId);
		verify(mockColumModelManager).bindCurrentColumnsToVersion(
				IdAndVersion.newBuilder().setId(tableIdLong).setVersion(version).build());
	}
	
	@Test
	public void testLinkVersionToTransactionWrongTable() {
		long version = 14;
		long otherTableId = tableIdLong+1;
		when(mockTableTransactionDao.getTableIdWithLock(transactionId)).thenReturn(otherTableId);

		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.linkVersionToTransaction(tableId, version, transactionId);
		});
	}
	
	@Test
	public void testCreateSnapshotBindToTransaction() {
		long newVersionNumber = 333L;
		when(mockTableTransactionDao.getTableIdWithLock(transactionId)).thenReturn(tableIdLong);
		when(mockNodeManager.createSnapshotAndVersion(any(UserInfo.class), anyString(), any(SnapshotRequest.class)))
				.thenReturn(newVersionNumber);
		// call under test
		long resultVersionNumber = manager.createSnapshotAndBindToTransaction(user, tableId, snapshotRequest, transactionId);
		assertEquals(newVersionNumber, resultVersionNumber);
		verify(mockNodeManager).createSnapshotAndVersion(user, tableId, snapshotRequest);
		verify(mockTableTransactionDao).getTableIdWithLock(transactionId);
		verify(mockTableTransactionDao).linkTransactionToVersion(transactionId, newVersionNumber);
		verify(mockTableTransactionDao).updateTransactionEtag(transactionId);
		IdAndVersion expectedIdAndVersion = IdAndVersion.parse("syn"+tableIdLong+"."+newVersionNumber);
		verify(mockColumModelManager).bindCurrentColumnsToVersion(expectedIdAndVersion);
		// fix for PLFM-5957
		verify(mockTableManagerSupport).setTableToProcessingAndTriggerUpdate(expectedIdAndVersion);
	}
	
	@Test
	public void testCreateSnapshotBindToTransactionNullInfo() throws Exception {
		snapshotRequest = null;
		when(mockTableTransactionDao.getTableIdWithLock(transactionId)).thenReturn(tableIdLong);
		// call under test
		manager.createSnapshotAndBindToTransaction(user, tableId, snapshotRequest, transactionId);
		verify(mockNodeManager).createSnapshotAndVersion(user, tableId, snapshotRequest);
		verify(mockTableTransactionDao).getTableIdWithLock(transactionId);
	}
	
	@Test
	public void testCreateTableSnapshot() {
		Long snapshotVersion = 441L;
		when(mockNodeManager.createSnapshotAndVersion(user, tableId, snapshotRequest)).thenReturn(snapshotVersion);
		when(mockTruthDao.getLastTransactionId(tableId)).thenReturn(Optional.of(transactionId));
		when(mockTableTransactionDao.getTableIdWithLock(transactionId)).thenReturn(tableIdLong);
		when(mockTableManagerSupport.getTableType(IdAndVersion.parse(tableId))).thenReturn(ObjectType.TABLE);
		// call under test
		SnapshotResponse response = manager.createTableSnapshot(user, tableId, snapshotRequest);
		assertNotNull(response);
		assertEquals(snapshotVersion, response.getSnapshotVersionNumber());
		verify(mockTableManagerSupport).validateTableWriteAccess(user, idAndVersion);
		verify(mockNodeManager).createSnapshotAndVersion(user, tableId, snapshotRequest);
	}
	
	@Test
	public void testCreateTableSnapshotView() {
		when(mockTableManagerSupport.getTableType(IdAndVersion.parse(tableId))).thenReturn(ObjectType.ENTITY_VIEW);
		// call under test
		try {
			manager.createTableSnapshot(user, tableId, snapshotRequest);
			fail();
		} catch (IllegalArgumentException e) {
			// expected
			assertTrue(e.getMessage().contains("EntityView"));
		}
	}
	
	@Test
	public void testCreateTableSnapshotNoTransaction() {
		when(mockTruthDao.getLastTransactionId(tableId)).thenReturn(Optional.empty());
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.createTableSnapshot(user, tableId, snapshotRequest);
		});
	}
	
	@Test
	public void testCreateTableSnapshotNullUser() {
		user = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.createTableSnapshot(user, tableId, snapshotRequest);
		});
	}
	
	@Test
	public void testCreateTableSnapshotNullTableId() {
		tableId = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.createTableSnapshot(user, tableId, snapshotRequest);
		});
	}
	
	@Test
	public void testCreateTableSnapshotNullRequest() {
		snapshotRequest = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.createTableSnapshot(user, tableId, snapshotRequest);
		});
	}
	
	@Test
	public void testGetTableChangeIdRange() {
		
		org.sagebionetworks.repo.model.IdRange expeceted = new org.sagebionetworks.repo.model.IdRange(0, 10);
		
		when(mockTruthDao.getTableRowChangeIdRange()).thenReturn(expeceted);
		
		// Call under test
		org.sagebionetworks.repo.model.IdRange result = manager.getTableRowChangeIdRange();
		
		assertEquals(expeceted, result);
		
		verify(mockTruthDao).getTableRowChangeIdRange();
	}
	
	@Test
	public void testNewTableRowChangeWithFileRefsIterator() {
		org.sagebionetworks.repo.model.IdRange idRange = new org.sagebionetworks.repo.model.IdRange(0, 10);
		
		List<TableRowChange> page = createChange(tableId, 10);
		
		when(mockTruthDao.getTableRowChangeWithFileRefsPage(any(), anyLong(), anyLong())).thenReturn(page, Collections.emptyList());
		
		// Call under test
		List<TableRowChange> result = IteratorUtils.toList(manager.newTableRowChangeWithFileRefsIterator(idRange));
		
		assertEquals(page, result);
		
		verify(mockTruthDao).getTableRowChangeWithFileRefsPage(idRange, 1000, 0);
		verify(mockTruthDao).getTableRowChangeWithFileRefsPage(idRange, 1000, 1000);
		
	}
	
	@Test
	public void testNewTableRowChangeWithFileRefsIteratorWithMultiplePages() {
		org.sagebionetworks.repo.model.IdRange idRange = new org.sagebionetworks.repo.model.IdRange(0, 10);
		
		List<TableRowChange> page = createChange(tableId, 1000);
		
		when(mockTruthDao.getTableRowChangeWithFileRefsPage(any(), anyLong(), anyLong())).thenReturn(page, page, Collections.emptyList());
		
		// Call under test
		List<TableRowChange> result = IteratorUtils.toList(manager.newTableRowChangeWithFileRefsIterator(idRange));
		
		assertEquals(ListUtils.union(page, page), result);
		
		verify(mockTruthDao).getTableRowChangeWithFileRefsPage(idRange, 1000, 0);
		verify(mockTruthDao).getTableRowChangeWithFileRefsPage(idRange, 1000, 1000);
		verify(mockTruthDao).getTableRowChangeWithFileRefsPage(idRange, 1000, 2000);
	}
	
	@Test
	public void testNewTableRowChangeWithFileRefsIteratorWithNoIdRange() {
		org.sagebionetworks.repo.model.IdRange idRange = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.newTableRowChangeWithFileRefsIterator(idRange);
		}).getMessage();
		
		assertEquals("The idRange is required.", message);
	}
	
	@Test
	public void testNewTableRowChangeWithFileRefsIteratorWithNoInvalidIdRange() {
		org.sagebionetworks.repo.model.IdRange idRange = new org.sagebionetworks.repo.model.IdRange(10, 0);

		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// Call under test
			manager.newTableRowChangeWithFileRefsIterator(idRange);
		}).getMessage();
		
		assertEquals("Invalid idRange, the minId must be lesser or equal than the maxId", message);
	}
	
	@Test
	public void testBackFillTableRowChangesBatch() throws IOException {
		
		int batchSize = 10;
		int pageSize = 10;
		
		List<TableRowChange> changePage = createChange(tableId, pageSize);
		
		when(mockTruthDao.getTableRowChangeWithNullFileRefsPage(anyLong(), anyLong())).thenReturn(changePage, Collections.emptyList());
		when(mockTruthDao.getRowSet(any())).thenReturn(rowDto);
		when(mockColumModelManager.getAndValidateColumnModels(any())).thenReturn(models);
		
		// Call under test
		manager.backfillTableRowChangesBatch(batchSize);
		
		verify(mockTruthDao).getTableRowChangeWithNullFileRefsPage(batchSize, 0);
		verify(mockTruthDao, times(pageSize)).getRowSet(any());
		verify(mockColumModelManager, times(pageSize)).getAndValidateColumnModels(rowDto.getColumnIds());
		verify(mockTruthDao).updateRowChangeHasFileRefsBatch(LongStream.range(0, pageSize).boxed().collect(Collectors.toList()), false);
	}
	
	@Test
	public void testBackFillTableRowChangesBatchWithFileRefs() throws IOException {
		
		int batchSize = 10;
		
		List<TableRowChange> changePage = createChange(tableId, batchSize);
		
		SparseRowDto row = new SparseRowDto();
		row.setValues(Collections.singletonMap(String.valueOf(ColumnType.FILEHANDLEID.ordinal()), "123"));
		
		rowDto.setRows(Arrays.asList(row));
		
		when(mockTruthDao.getTableRowChangeWithNullFileRefsPage(anyLong(), anyLong())).thenReturn(changePage, Collections.emptyList());
		when(mockTruthDao.getRowSet(any())).thenReturn(rowDto);
		when(mockColumModelManager.getAndValidateColumnModels(any())).thenReturn(models);
		
		// Call under test
		manager.backfillTableRowChangesBatch(batchSize);
		
		verify(mockTruthDao).getTableRowChangeWithNullFileRefsPage(batchSize, 0);
		verify(mockTruthDao, times(batchSize)).getRowSet(any());
		verify(mockColumModelManager, times(batchSize)).getAndValidateColumnModels(rowDto.getColumnIds());
		verify(mockTruthDao).updateRowChangeHasFileRefsBatch(LongStream.range(0, batchSize).boxed().collect(Collectors.toList()), true);
	}
	
	@Test
	public void testBackFillTableRowChangesBatchWithAmazonS3NotFound() throws IOException {
		
		int batchSize = 10;
		
		List<TableRowChange> changePage = createChange(tableId, batchSize);
		
		when(mockTruthDao.getTableRowChangeWithNullFileRefsPage(anyLong(), anyLong())).thenReturn(changePage, Collections.emptyList());
		
		AmazonS3Exception ex = new AmazonS3Exception("Not found");
		ex.setStatusCode(HttpStatus.SC_NOT_FOUND);
		
		doThrow(ex).when(mockTruthDao).getRowSet(any());
		
		// Call under test
		manager.backfillTableRowChangesBatch(batchSize);
		
		verify(mockTruthDao).getTableRowChangeWithNullFileRefsPage(batchSize, 0);
		verify(mockTruthDao, times(batchSize)).getRowSet(any());
		verify(mockColumModelManager, never()).getAndValidateColumnModels(anyList());
		verify(mockTruthDao).updateRowChangeHasFileRefsBatch(LongStream.range(0, batchSize).boxed().collect(Collectors.toList()), false);
	}
	
	@Test
	public void testBackFillTableRowChangesBatchWithNotFound() throws IOException {

		int batchSize = 10;
		
		List<TableRowChange> changePage = createChange(tableId, batchSize);
		
		when(mockTruthDao.getTableRowChangeWithNullFileRefsPage(anyLong(), anyLong())).thenReturn(changePage, Collections.emptyList());
		
		NotFoundException ex = new NotFoundException("Not found");
		
		doThrow(ex).when(mockTruthDao).getRowSet(any());
		
		// Call under test
		manager.backfillTableRowChangesBatch(batchSize);
		
		verify(mockTruthDao).getTableRowChangeWithNullFileRefsPage(batchSize, 0);
		verify(mockTruthDao, times(batchSize)).getRowSet(any());
		verify(mockColumModelManager, never()).getAndValidateColumnModels(anyList());
		verify(mockTruthDao).updateRowChangeHasFileRefsBatch(LongStream.range(0, batchSize).boxed().collect(Collectors.toList()), false);
	}
	
	@Test
	public void testBackFillTableRowChangesBatchWithOtherAmazonException() throws IOException {
		
		int batchSize = 10;
		
		List<TableRowChange> changePage = createChange(tableId, batchSize);
		
		when(mockTruthDao.getTableRowChangeWithNullFileRefsPage(anyLong(), anyLong())).thenReturn(changePage, Collections.emptyList());
		
		AmazonServiceException ex = new AmazonServiceException("Something wrong");
		ex.setErrorType(ErrorType.Client);
		
		doThrow(ex).when(mockTruthDao).getRowSet(any());
		
		AmazonServiceException result = assertThrows(AmazonServiceException.class, () -> {			
			// Call under test
			manager.backfillTableRowChangesBatch(batchSize);
		});
		
		assertEquals(ex, result);
	}
	
	@Test
	public void testBackFillTableRowChangesBatchWithIOException() throws IOException {
		
		int batchSize = 10;
		
		List<TableRowChange> changePage = createChange(tableId, batchSize);
		
		when(mockTruthDao.getTableRowChangeWithNullFileRefsPage(anyLong(), anyLong())).thenReturn(changePage, Collections.emptyList());
		
		IOException ex = new IOException("Something wrong");
		
		doThrow(ex).when(mockTruthDao).getRowSet(any());
		
		IllegalStateException result = assertThrows(IllegalStateException.class, () -> {			
			// Call under test
			manager.backfillTableRowChangesBatch(batchSize);
		});
		
		assertEquals(ex, result.getCause());
	}
	
	/**
	 * Helper to create a list of TableRowChange for the given tableId and count.
	 * @param tableId
	 * @param count
	 * @return
	 */
	public static List<TableRowChange> createChange(String tableId, int count){
		List<TableRowChange> results = new LinkedList<>();
		int enumLenght = TableChangeType.values().length;
		for(int i=0; i<count; i++) {
			TableRowChange change = new TableRowChange();
			change.setId(new Long(i));
			change.setTableId(tableId);
			change.setRowVersion(new Long(i));
			change.setChangeType(TableChangeType.values()[i%enumLenght]);
			change.setKeyNew("someKey"+i);
			results.add(change);
		}
		return results;
	}
}
