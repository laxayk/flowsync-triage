package dev.laxayk.triage.ingest;

import dev.laxayk.triage.pipeline.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;

/** Loads the bundled FlowSync sample mailbox (.eml files). Safe to run repeatedly: Message-ID dedupe makes it idempotent. */
@Component
public class SampleLoader {
    private static final Logger log = LoggerFactory.getLogger(SampleLoader.class);
    private final Pipeline pipeline;
    private final boolean onStart;

    public SampleLoader(Pipeline pipeline, @Value("${app.ingest.samples-on-start:true}") boolean onStart) {
        this.pipeline = pipeline;
        this.onStart = onStart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (onStart) log.info("Loaded {} sample emails", load());
    }

    public int load() {
        int n = 0;
        try {
            Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath:/samples/*.eml");
            Arrays.sort(all, Comparator.comparing(Resource::getFilename));
            for (Resource r : all) {
                try (var in = r.getInputStream()) {
                    if (pipeline.ingest(EmailParser.parse(in), "sample").isPresent()) n++;
                }
            }
        } catch (Exception e) {
            log.error("Sample load failed", e);
        }
        return n;
    }
}
