package io.codiqo.llm;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import lombok.Value;

/** one tool's finding of one rule on one line: the tool in lower case and the path in unix form, however it was written */
@Value
public class FindingKey {
    String tool;
    String rule;
    String file;
    int line;

    public static FindingKey of(String tool, String rule, String file, int line) {
        return new FindingKey(StringUtils.lowerCase(tool), rule, FilenameUtils.separatorsToUnix(StringUtils.defaultString(file)), line);
    }
}
