package com.turbospaces.jdtls;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.Fetch;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.core.logging.SlfLogFactory;

public class GitDeltaAnalysisMicroDemo {
    public static void main(String[] args) throws Exception {
        LogFactory logFactory = new SlfLogFactory();
        Log log = logFactory.getLogger(GitDeltaAnalysisMicroDemo.class);
        Options options = RunArgs.options();
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        RunArgs run = RunArgs.from(cmd);

        log.info("running with args: " + run);

        String[] infoByDefault = { "org.eclipse.jgit" };
        LoggerContext slf = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (String it : infoByDefault) {
            slf.getLogger(it).setLevel(Level.INFO);
        }

        try (Fetch fetch = new Fetch(run)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, run, fetch)) {
                registry.load().block();

                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, registry, run);
                CommitAnalysis analysis = analyzer.analyze();
                IndexingSummary index = registry.index(analysis);
                registry.collectAndCapture(index, analysis);
            }
        }
    }
}
