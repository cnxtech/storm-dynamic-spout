/*
 * Copyright (c) 2017, 2018, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *   disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 *   derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Using imports from shaded libraries is a bad practice and makes upgrading difficult.
 */
public class DisallowUsingShadedImports {
    private static final Logger logger = LoggerFactory.getLogger(DisallowUsingShadedImports.class);

    // Find any imports using .shade.
    private static final Pattern regexPattern = Pattern.compile("import .*(\\.storm\\.shade\\.).*;");

    private List<String> failedFiles = new ArrayList<>();

    @Test
    public void doTest() throws FileNotFoundException {
        // Hacky way to determine root path
        final File currentPath = new File(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
        final File projectRootPath = currentPath.getParentFile().getParentFile();

        // Walk all the files in the path
        walk(projectRootPath);
    }

    private void walk(File root) throws FileNotFoundException {
        File[] list = root.listFiles();

        if (list == null) {
            return;
        }

        for (File f : list) {
            if (f.isDirectory()) {
                walk(f);
            } else {
                // Skip non java source files
                if (!f.getAbsoluteFile().getPath().endsWith(".java")) {
                    continue;
                }
                testFile(f);
            }
        }

        failedFiles.forEach(logger::error);

        assertTrue(failedFiles.isEmpty(), "Should have not found any files");
    }

    private void testFile(File myFile) throws FileNotFoundException {
        String fileData = new Scanner(myFile).useDelimiter("\\Z").next();

        // Look for our pattern
        Matcher matches = regexPattern.matcher(fileData);

        // If we didn't find a match
        if (!matches.find()) {
            return;
        }

        // Found shade import usage!
        failedFiles.add("Found instance of logger using wrong class? " + myFile.getPath() + " Using " + matches.toMatchResult().toString());
    }
}
