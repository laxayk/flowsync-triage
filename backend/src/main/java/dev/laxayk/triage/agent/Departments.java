package dev.laxayk.triage.agent;

import dev.laxayk.triage.domain.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Which department owns a category, and which mailbox is that department's owner. Recipients come only from this config. */
@Component
public class Departments {
    public record Dept(String name, String ownerEmail) {}

    private final Map<String, String> owners = new HashMap<>();

    public Departments(@Value("${app.departments:ENGINEERING=priya@flowsync.example,PRODUCT=neha@flowsync.example,SUPPORT=sam@flowsync.example,FINANCE=maya@flowsync.example,SRE=ravi@flowsync.example}") String config) {
        for (String pair : config.split(",")) {
            String[] kv = pair.trim().split("=");
            if (kv.length == 2) owners.put(kv[0].trim().toUpperCase(), kv[1].trim());
        }
    }

    public static String nameFor(Category c) {
        return switch (c) {
            case BUG, WRONG_BEHAVIOUR -> "ENGINEERING";
            case ENHANCEMENT -> "PRODUCT";
            case BILLING -> "FINANCE";
            case OUTAGE -> "SRE";
            default -> "SUPPORT";
        };
    }

    public Optional<Dept> forCategory(Category c) {
        String n = nameFor(c);
        return Optional.ofNullable(owners.get(n)).map(e -> new Dept(n, e));
    }
}
