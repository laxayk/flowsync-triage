package dev.laxayk.triage.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-sent events so the dashboard updates live without polling. */
@Component
public class EventHub {
    private static final Logger log = LoggerFactory.getLogger(EventHub.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter e = new SseEmitter(0L);
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(t -> emitters.remove(e));
        try { e.send(SseEmitter.event().name("hello").data("ok")); } catch (IOException ex) { emitters.remove(e); }
        return e;
    }

    public void publish(String type, Map<String, Object> data) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(type).data(data));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    public int subscribers() { return emitters.size(); }
}
