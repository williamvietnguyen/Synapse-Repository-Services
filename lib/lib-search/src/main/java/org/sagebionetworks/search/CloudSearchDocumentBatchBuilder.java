package org.sagebionetworks.search;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import org.apache.commons.io.output.CountingOutputStream;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.util.FileProvider;
import org.sagebionetworks.util.ValidateArgument;

public class CloudSearchDocumentBatchBuilder implements Closeable {

	private static final long MEBIBYTE = 1048576; // CloudSearch's Limits say MB but they really mean MiB
	private static final long DEFAULT_MAX_SINGLE_DOCUMENT_SIZE = MEBIBYTE; //1MiB
	private static final long DEFAULT_MAX_DOCUMENT_BATCH_SIZE = 5 * MEBIBYTE; //5MiB

	private static final Charset CHARSET = StandardCharsets.UTF_8;
	static final byte[] PREFIX_BYTES = "[".getBytes(CHARSET);
	static final byte[] SUFFIX_BYTES = "]".getBytes(CHARSET);
	static final byte[] DELIMITER_BYTES = ",".getBytes(CHARSET);

	FileProvider fileProvider;

	File documentBatchFile;

	CountingOutputStream countingOutputStream;

	Set<String> documentIds;

	//maximum bytes
	private final long maxSingleDocumentSizeInBytes;
	private final long maxDocumentBatchSizeInBytes;

	boolean built;

	public CloudSearchDocumentBatchBuilder(FileProvider fileProvider) throws IOException {
		this(fileProvider, DEFAULT_MAX_SINGLE_DOCUMENT_SIZE, DEFAULT_MAX_SINGLE_DOCUMENT_SIZE);
	}

	public CloudSearchDocumentBatchBuilder(FileProvider fileProvider, long maxSingleDocumentSizeInBytes, long maxDocumentBatchSizeInBytes) throws IOException {
		if (maxSingleDocumentSizeInBytes + PREFIX_BYTES.length + SUFFIX_BYTES.length > maxDocumentBatchSizeInBytes){
			throw new IllegalArgumentException("maxSingleDocumentSizeInBytes + " + (PREFIX_BYTES.length + SUFFIX_BYTES.length) + " must be less or equal to than maxDocumentBatchSizeInBytes");
		}
		this.built = false;


		this.documentBatchFile = fileProvider.createTempFile("CloudSearchDocument", ".json");
		this.countingOutputStream = new CountingOutputStream(fileProvider.createFileOutputStream(documentBatchFile));

		this.maxSingleDocumentSizeInBytes = maxSingleDocumentSizeInBytes;
		this.maxDocumentBatchSizeInBytes = maxDocumentBatchSizeInBytes;


		//always append prefix to the file
		this.countingOutputStream.write(PREFIX_BYTES);
	}

	public CloudSearchDocumentBatch build() throws IOException {
		//append suffix
		countingOutputStream.write(SUFFIX_BYTES);
		countingOutputStream.flush();
		built = true;

		countingOutputStream.close();

		return new CloudSearchDocumentBatchImpl(documentBatchFile, documentIds, countingOutputStream.getByteCount());
	}

	/**
	 * Attempt to add bytes to the document
	 * @return true if bytes could be added, false otherwise
	 */
	public boolean tryAddDocumentToBatch(Document document) throws IOException {
		ValidateArgument.required(document.getId(), "document.Id");

		byte[] documentBytes = SearchUtil.convertSearchDocumentToJSONString(document).getBytes(CHARSET);

		//if a single document exceeds the single document size limit throw exception
		if (documentBytes.length > maxSingleDocumentSizeInBytes) {
			throw new IllegalArgumentException("The document for " + document.getId() + " is " + documentBytes.length + " bytes and exceeds the maximum allowed " + maxSingleDocumentSizeInBytes + " bytes.");
		}

		boolean isNotFirstDocument = countingOutputStream.getByteCount() > PREFIX_BYTES.length;

		//determine how many bytes need to be written
		//always reserve space for the suffix because the next document being added may not fit into this document batch.
		long bytesToBeAdded = documentBytes.length + SUFFIX_BYTES.length;
		if(isNotFirstDocument) {
			bytesToBeAdded += DELIMITER_BYTES.length;
		}

		// Check there is enough space to write into this batch
		if(countingOutputStream.getByteCount() + bytesToBeAdded > maxDocumentBatchSizeInBytes){
			return false;
		}

		if(isNotFirstDocument){
			countingOutputStream.write(DELIMITER_BYTES);
		}
		countingOutputStream.write(documentBytes);

		documentIds.add(document.getId());
		return true;
	}

	@Override
	public void close() throws IOException {
		if(countingOutputStream != null){
			countingOutputStream.close();
		}

		if(!built && documentBatchFile != null) {
			documentBatchFile.delete();
		}
	}
}
