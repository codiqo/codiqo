package io.codiqo.core;

import java.util.List;
import java.util.Properties;

import com.google.common.collect.ImmutableList;

import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;

public class OSDetector extends kr.motd.maven.os.Detector {
    private final Log log;
    private final Properties properties = new Properties();

    public OSDetector(LogFactory logFactory) {
        this.log = logFactory.getLogger(getClass());
        super.detect(properties, ImmutableList.of());
    }
    @Override
    protected void log(String message) {
        log.info(message);
    }
    @Override
    protected void logProperty(String name, String value) {
        log.info(name + ": " + value);
    }
    @Override
    public void detect(Properties props, List<String> classifierWithLikes) {
        props.putAll(properties);
    }
}
