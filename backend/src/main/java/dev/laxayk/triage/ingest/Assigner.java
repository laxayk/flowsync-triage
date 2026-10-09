package dev.laxayk.triage.ingest;

import dev.laxayk.triage.domain.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Maps a category to an owner. Config format: BUG=priya,OUTAGE=ravi,... (unknown categories stay unassigned). */
@Component
public class Assigner {
    private final Map<Category, String> owners = new HashMap<>();

    public Assigner(@Value("${app.owners:BUG=priya,WRONG_BEHAVIOUR=priya,ENHANCEMENT=neha,QUERY=sam,ACCOUNT_ACCESS=sam,BILLING=maya,OUTAGE=ravi}") String config) {
        for (String pair : config.split(",")) {
            String[] kv = pair.trim().split("=");
            if (kv.length == 2) owners.put(Category.parse(kv[0], null), kv[1].trim());
        }
    }

    public String ownerFor(Category c) { return owners.get(c); }
}
