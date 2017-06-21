package de.mbur.jlua;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestLuaTableParser {
	private static final Logger log = LoggerFactory.getLogger(TestLuaTableParser.class);

	@Test
	public void testDirectory() {
		File inDir = new File(
				"c:/Users/Martin Burchard/Documents/Elder Scrolls Online/live/SavedVariables");
		File outDir = new File("c:/Temp/Eso");
		if (inDir.exists() && inDir.isDirectory() && outDir.exists() && outDir.isDirectory()) {
			for (File file : inDir.listFiles()) {
				if (file.isFile() && !file.getName().startsWith(".")) {
					log.debug("File: {}", file.getName());
					LuaTableParser ltp = new LuaTableParser();
					ltp.setEncoding("UTF-8");
					try (final InputStream in = new FileInputStream(file)) {
						ltp.parse(in);
					} catch (IOException e) {
						log.error("", e);
					}
					try (final OutputStream out = new FileOutputStream(outDir.getAbsolutePath()
							+ File.separatorChar + file.getName())) {
						ltp.writeTo(out);
					} catch (IOException e) {
						log.error("", e);
					}
				}
			}
		}
	}

}
