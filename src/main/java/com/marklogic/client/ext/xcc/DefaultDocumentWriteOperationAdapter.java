package com.marklogic.client.ext.xcc;

import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.client.io.*;
import com.marklogic.client.io.marker.AbstractWriteHandle;
import com.marklogic.client.io.marker.DocumentMetadataWriteHandle;
import com.marklogic.xcc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class DefaultDocumentWriteOperationAdapter implements DocumentWriteOperationAdapter {

	private final static Logger logger = LoggerFactory.getLogger(DefaultDocumentWriteOperationAdapter.class);

	@Override
	public Content adapt(DocumentWriteOperation operation) {
		String uri = operation.getUri();
		ContentCreateOptions options = adaptMetadata(operation.getMetadata());
		AbstractWriteHandle handle = operation.getContent();
		if (handle instanceof StringHandle) {
			return ContentFactory.newContent(uri, ((StringHandle) handle).get(), options);
		} else if (handle instanceof FileHandle) {
			return ContentFactory.newContent(uri, ((FileHandle) handle).get(), options);
		} else if (handle instanceof BytesHandle) {
			return ContentFactory.newContent(uri, ((BytesHandle) handle).get(), options);
		} else if (handle instanceof InputStreamHandle) {
			try {
				return ContentFactory.newContent(uri, ((InputStreamHandle) handle).get(), options);
			} catch (IOException e) {
				throw new RuntimeException("Unable to read content input stream: " + e.getMessage(), e);
			}
		} else if (handle instanceof DOMHandle) {
			return ContentFactory.newContent(uri, ((DOMHandle) handle).get(), options);
		} else throw new IllegalArgumentException("No support yet for content class: " + handle.getClass().getName());
	}

	/**
	 * TODO Only adapts collections, quality, format, and permissions so far.
	 *
	 * @param handle
	 * @return
	 */
	protected ContentCreateOptions adaptMetadata(DocumentMetadataWriteHandle handle) {
		ContentCreateOptions options = new ContentCreateOptions();
		if (handle instanceof DocumentMetadataHandle) {
			DocumentMetadataHandle metadata = (DocumentMetadataHandle) handle;
			options.setQuality(metadata.getQuality());
			options.setCollections(metadata.getCollections().toArray(new String[]{}));
			adaptPermissions(options, metadata);
			adaptFormat(options, metadata);
		} else {
			logger.warn("Only supports DocumentMetadataHandle; unsupported metadata class: " + handle.getClass().getName());
		}
		return options;
	}

	/**
	 * The REST API Format class has a "getDefaultMimetype" method on it, but there doesn't appear to be anything
	 * useful to do with that for XCC. So we just do a simple translation from Format to DocumentFormat.
	 *
	 * @param options
	 * @param metadata
	 */
	protected void adaptFormat(ContentCreateOptions options, DocumentMetadataHandle metadata) {
		Format format = metadata.getFormat();
		DocumentFormat xccFormat = null;
		if (format != null) {
			if (Format.BINARY.equals(format)) {
				xccFormat = DocumentFormat.BINARY;
			} else if (Format.JSON.equals(format)) {
				xccFormat = DocumentFormat.JSON;
			} else if (Format.TEXT.equals(format)) {
				xccFormat = DocumentFormat.TEXT;
			} else if (Format.XML.equals(format)) {
				xccFormat = DocumentFormat.XML;
			} else if (Format.UNKNOWN.equals(format)) {
				xccFormat = DocumentFormat.NONE;
			} else if (logger.isDebugEnabled()) {
				logger.debug("Unsupported format, can't adapt to an XCC DocumentFormat; " + format.toString());
			}
		}
		if (xccFormat != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("Adapted REST format " + format + " to XCC format: " + xccFormat.toString());
			}
			options.setFormat(xccFormat);
		}
	}

	protected void adaptPermissions(ContentCreateOptions options, DocumentMetadataHandle metadata) {
		Set<ContentPermission> contentPermissions = new HashSet<>();
		DocumentMetadataHandle.DocumentPermissions permissions = metadata.getPermissions();
		for (String role : permissions.keySet()) {
			for (DocumentMetadataHandle.Capability capability : permissions.get(role)) {
				ContentCapability contentCapability;
				if (DocumentMetadataHandle.Capability.EXECUTE.equals(capability)) {
					contentCapability = ContentCapability.EXECUTE;
				} else if (DocumentMetadataHandle.Capability.INSERT.equals(capability)) {
					contentCapability = ContentCapability.INSERT;
				} else if (DocumentMetadataHandle.Capability.READ.equals(capability)) {
					contentCapability = ContentCapability.READ;
				} else if (DocumentMetadataHandle.Capability.UPDATE.equals(capability)) {
					contentCapability = ContentCapability.UPDATE;
				} else throw new IllegalArgumentException("Unrecognized permission capability: " + capability);
				contentPermissions.add(new ContentPermission(contentCapability, role));
			}
		}
		options.setPermissions(contentPermissions.toArray(new ContentPermission[]{}));
	}
}