package com.marklogic.client.ext.file;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.ext.batch.BatchWriter;
import com.marklogic.client.ext.batch.RestBatchWriter;
import com.marklogic.client.ext.helper.LoggingObject;
import com.marklogic.client.ext.tokenreplacer.TokenReplacer;

import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Generic implementation of FileLoader. Delegates to a DocumentFileReader for reading from a set of file paths, and
 * delegates to a BatchWriter for writing to MarkLogic (where that BatchWriter could use XCC, the REST API, or the
 * Data Movement SDK in ML9).
 *
 * The batchSize property defaults to null, which means all files are written in one call via the BatchWriter. Setting
 * this means that the List of DocumentFile objects read from the DocumentFileReader will be written in batches, each
 * the size of the batchSize property, except for the final one that may be less than this size.
 */
public class GenericFileLoader extends LoggingObject implements FileLoader {

	private DocumentFileReader documentFileReader;
	private BatchWriter batchWriter;
	private boolean waitForCompletion = true;
	private boolean logFileUris = true;
	private Integer batchSize;

	// These are passed on to the DefaultDocumentFileReader that is created if one isn't set
	private List<FileFilter> fileFilters;
	private List<DocumentFileProcessor> documentFileProcessors;

	// Properties that affect the DocumentFileProcessors that are created for the DefaultDocumentFileReader
	private String permissions;
	private String[] collections;
	private TokenReplacer tokenReplacer;
	private String[] additionalBinaryExtensions;

	/**
	 * The given DatabaseClient is used to construct a BatchWriter that writes to MarkLogic via the REST API. The
	 * expectation is that the client will then either call setDocumentFileReader or will rely on the default one
	 * that's created by this class if one has not yet been set.
	 *
	 * @param client
	 */
	public GenericFileLoader(DatabaseClient client) {
		RestBatchWriter restBatchWriter = new RestBatchWriter(client);
		restBatchWriter.setReleaseDatabaseClients(false);
		this.batchWriter = restBatchWriter;
	}

	/**
	 * Clients should use this when they already have a BatchWriter ready to go.
	 *
	 * @param batchWriter
	 */
	public GenericFileLoader(BatchWriter batchWriter) {
		this.batchWriter = batchWriter;
	}

	/**
	 * Load files from each of the given paths, using the underlying DocumentFileReader.
	 * <p>
	 * If a DocumentFileReader has not been set yet, then one will be constructed before any files are read.
	 *
	 * @param paths
	 * @return
	 */
	public List<DocumentFile> loadFiles(String... paths) {
		batchWriter.initialize();
		initializeDocumentFileReader();

		List<DocumentFile> documentFiles = documentFileReader.readDocumentFiles(paths);
		if (documentFiles != null && !documentFiles.isEmpty()) {
			writeBatchOfDocuments(documentFiles, 0);
			if (waitForCompletion) {
				batchWriter.waitForCompletion();
			}
		}
		return documentFiles;
	}

	/**
	 * If batchSize is not set, then this method will load all the documents in one call to the BatchWriter. Otherwise,
	 * this will divide up the list of documentFiles into batches matching the value of batchSize, with the last batch
	 * possibly being less than batchSize.
	 * 
	 * @param documentFiles
	 * @param startPosition
	 */
	protected void writeBatchOfDocuments(List<DocumentFile> documentFiles, final int startPosition) {
		final int documentFilesSize = documentFiles.size();
		if (startPosition >= documentFilesSize) {
			return;
		}

		if (batchSize != null && batchSize < 1) {
			batchSize = null;
		}

		// The "end" param to subList below is exclusive, so the highest valid value is the list size
		int endPosition = batchSize == null ? documentFilesSize : startPosition + batchSize;
		if (endPosition > documentFilesSize) {
			endPosition = documentFilesSize;
		}

		List<DocumentFile> batch = documentFiles.subList(startPosition, endPosition);
		if (!batch.isEmpty()) {
			if (logger.isInfoEnabled()) {
				logger.info(format("Writing %d files", batch.size()));
				if (logFileUris) {
					for (DocumentFile df : batch) {
						logger.info("Writing: " + df.getUri());
					}
				}
			}
			batchWriter.write(batch);
		}

		if (endPosition < documentFilesSize) {
			writeBatchOfDocuments(documentFiles, endPosition);
		}
	}

	/**
	 * If no DocumentFileReader is set, this will construct a DefaultDocumentFileReader, which is then configured based
	 * on several properties of this class.
	 */
	public void initializeDocumentFileReader() {
		if (this.documentFileReader == null) {
			DefaultDocumentFileReader reader = new DefaultDocumentFileReader();

			if (fileFilters != null) {
				for (FileFilter filter : fileFilters) {
					reader.addFileFilter(filter);
				}
			}

			prepareAbstractDocumentFileReader(reader);
			this.documentFileReader = reader;
		}
	}

	/**
	 * This was initially part of building a DefaultDocumentFileReader. But in the event that a client sets a custom
	 * DocumentFileReader on this class that extends AbstractDocumentFileReader, it's useful to reuse this code on
	 * that custom DocumentFileReader. Thus, it's public.
	 *
	 * @param reader
	 */
	public void prepareAbstractDocumentFileReader(AbstractDocumentFileReader reader) {
		for (DocumentFileProcessor processor : buildDocumentFileProcessors()) {
			reader.addDocumentFileProcessor(processor);
		}

		if (additionalBinaryExtensions != null) {
			FormatDocumentFileProcessor processor = reader.getFormatDocumentFileProcessor();
			FormatGetter formatGetter = processor.getFormatGetter();
			if (formatGetter instanceof DefaultDocumentFormatGetter) {
				DefaultDocumentFormatGetter ddfg = (DefaultDocumentFormatGetter)formatGetter;
				for (String ext : additionalBinaryExtensions) {
					ddfg.getBinaryExtensions().add(ext);
				}
			} else {
				logger.warn("FormatGetter is not an instanceof DefaultDocumentFormatGetter, " +
					"so unable to add additionalBinaryExtensions: " + Arrays.asList(additionalBinaryExtensions));
			}
		}
	}

	/**
	 * Builds a set of DocumentFileProcessor objects based on how this class has been configured.
	 *
	 * @return
	 */
	protected List<DocumentFileProcessor> buildDocumentFileProcessors() {
		List<DocumentFileProcessor> processors = new ArrayList<>();
		if (permissions != null) {
			processors.add(new PermissionsDocumentFileProcessor(permissions));
		}
		if (collections != null) {
			processors.add(new CollectionsDocumentFileProcessor(collections));
		}
		if (tokenReplacer != null) {
			processors.add(new TokenReplacerDocumentFileProcessor(tokenReplacer));
		}
		if (documentFileProcessors != null) {
			for (DocumentFileProcessor dfp : documentFileProcessors) {
				processors.add(dfp);
			}
		}
		return processors;
	}

	public void addFileFilter(FileFilter fileFilter) {
		if (fileFilters == null) {
			fileFilters = new ArrayList<>();
		}
		fileFilters.add(fileFilter);
	}

	public void addDocumentFileProcessor(DocumentFileProcessor processor) {
		if (documentFileProcessors == null) {
			documentFileProcessors = new ArrayList<>();
		}
		documentFileProcessors.add(processor);
	}

	public void setWaitForCompletion(boolean waitForCompletion) {
		this.waitForCompletion = waitForCompletion;
	}

	public void setDocumentFileReader(DocumentFileReader documentFileReader) {
		this.documentFileReader = documentFileReader;
	}

	public void setPermissions(String permissions) {
		this.permissions = permissions;
	}

	public void setCollections(String... collections) {
		this.collections = collections;
	}

	public void setTokenReplacer(TokenReplacer tokenReplacer) {
		this.tokenReplacer = tokenReplacer;
	}

	public void setFileFilters(List<FileFilter> fileFilters) {
		this.fileFilters = fileFilters;
	}

	public void setDocumentFileProcessors(List<DocumentFileProcessor> documentFileProcessors) {
		this.documentFileProcessors = documentFileProcessors;
	}

	public void setAdditionalBinaryExtensions(String... additionalBinaryExtensions) {
		this.additionalBinaryExtensions = additionalBinaryExtensions;
	}

	public boolean isLogFileUris() {
		return logFileUris;
	}

	public void setLogFileUris(boolean logFileUris) {
		this.logFileUris = logFileUris;
	}

	public DocumentFileReader getDocumentFileReader() {
		return documentFileReader;
	}

	public BatchWriter getBatchWriter() {
		return batchWriter;
	}

	public TokenReplacer getTokenReplacer() {
		return tokenReplacer;
	}

	public List<FileFilter> getFileFilters() {
		return fileFilters;
	}

	public List<DocumentFileProcessor> getDocumentFileProcessors() {
		return documentFileProcessors;
	}

	public void setBatchSize(Integer batchSize) {
		this.batchSize = batchSize;
	}
}
