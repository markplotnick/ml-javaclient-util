package com.marklogic.client.ext.file;

import com.marklogic.client.AbstractIntegrationTest;
import com.marklogic.client.io.Format;
import org.junit.Test;

import java.util.List;

public class SetAdditionalBinaryExtensionsTest extends AbstractIntegrationTest {

	@Test
	public void test() {
		client = newClient("Documents");
		client.newServerEval().xquery("cts:uris((), (), cts:true-query()) ! xdmp:document-delete(.)").eval();

		GenericFileLoader loader = new GenericFileLoader(client);
		loader.setAdditionalBinaryExtensions("test1", "test2");
		List<DocumentFile> files = loader.loadFiles("src/test/resources/binary-test");
		for (DocumentFile file : files) {
			String name = file.getFile().getName();
			if ("file.test1".equals(name) || "file.test2".equals(name)) {
				assertEquals(Format.BINARY, file.getFormat());
			} else {
				assertEquals("Unrecognized extensions should default to text", Format.TEXT, file.getFormat());
			}
		}
	}
}