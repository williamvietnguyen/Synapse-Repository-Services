package org.sagebionetworks.repo.model.dbo.file;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;

public class DBOMultipartUploadTest {
	
	private MigratableTableTranslation<DBOMultipartUpload, DBOMultipartUpload> migrationTranslator;

	@BeforeEach
	public void before() {
		migrationTranslator = new DBOMultipartUpload().getTranslator();
	}
	
	@Test
	public void testCreateDatabaseObjectFromBackup() {
		
		// Mimic the json stored without the concrete type
		String json = "{\"partSizeBytes\":5,\"fileSizeBytes\":1024}";
		String requestHash = "OldHash";
		
		DBOMultipartUpload dbo = new DBOMultipartUpload();
		
		dbo.setRequestType(null);
		dbo.setRequestBlob(json.getBytes(StandardCharsets.UTF_8));
		dbo.setRequestHash(requestHash);
		
		DBOMultipartUpload result = migrationTranslator.createDatabaseObjectFromBackup(dbo);
		
		assertEquals("{\"concreteType\":\"org.sagebionetworks.repo.model.file.MultipartUploadRequest\",\"partSizeBytes\":5,\"fileSizeBytes\":1024}", new String(result.getRequestBlob(), StandardCharsets.UTF_8));
		assertEquals("UPLOAD", result.getRequestType());
		assertEquals("a1c51581494bde3a04b61c4f57184d36", result.getRequestHash());
		assertEquals(1024L, result.getFileSize());
		assertEquals(5L, result.getPartSize());
	}
	
}
