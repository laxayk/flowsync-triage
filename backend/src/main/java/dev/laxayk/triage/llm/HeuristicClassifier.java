package dev.laxayk.triage.llm;

import dev.laxayk.triage.domain.Category;
import dev.laxayk.triage.domain.Classification;
import dev.laxayk.triage.domain.ParsedEmail;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword fallback classifier. It runs when no LLM is configured or the LLM fails, so the pipeline never stalls.
 * It is deliberately simple; results from it are flagged needs_review so a human checks them.
 */
@Component
public class HeuristicClassifier {

    private static final Map<Category, Pattern> RULES = new EnumMap<>(Category.class);
    static {
        RULES.put(Category.OUTAGE, p("(all|every|multiple)\\s+(our\\s+)?(syncs?|jobs?|connectors?|customers)\\s+(are\\s+)?(down|failing|stopped)|outage|nothing\\s+is\\s+syncing|everything\\s+is\\s+down|production\\s+(is\\s+)?down|service\\s+is\\s+down|status\\s+page"));
        RULES.put(Category.ACCOUNT_ACCESS, p("(can'?t|cannot|unable\\s+to)\\s+(log\\s*in|sign\\s*in|access)|locked\\s+out|reset\\s+(my\\s+)?password|2fa|two[- ]factor|sso\\s+(error|fails?)|invite\\s+(link|email)\\s+(expired|not)"));
        RULES.put(Category.BILLING, p("invoice|refund|charged\\s+(twice|again)|double\\s+charge|billing|subscription\\s+(renewal|cancel)|credit\\s+note|payment\\s+(failed|declined)|gst|tax\\s+id"));
        RULES.put(Category.WRONG_BEHAVIOUR, p("says\\s+success|shows\\s+success|marked\\s+(as\\s+)?(success|complete)|rows\\s+(are\\s+)?missing|missing\\s+(rows|records)|not\\s+what\\s+i\\s+expected|unexpected(ly)?|wrong\\s+(data|value|mapping)|overwr(ote|itten)|silently"));
        RULES.put(Category.BUG, p("duplicat|crash|stack\\s*trace|exception|error\\s+(code|500|502|503)|\\b5\\d\\d\\b\\s+error|fails?\\s+(with|after|every)|null\\s*pointer|regression|broken|bug"));
        RULES.put(Category.ENHANCEMENT, p("feature\\s+request|would\\s+be\\s+(great|nice|useful)|it\\s+would\\s+help\\s+if|please\\s+add|can\\s+you\\s+add|support\\s+for\\s+\\w+|dry[- ]run|wish\\s+(you|there)|suggest(ion)?"));
        RULES.put(Category.QUERY, p("how\\s+(do|can|to|should)|is\\s+it\\s+possible|can\\s+i\\s+|does\\s+(flowsync|it)\\s+support|what\\s+is\\s+the\\s+(limit|difference)|\\?"));
        RULES.put(Category.PRAISE, p("thank(s|\\s+you)\\s+(so\\s+much|a\\s+lot|team)|love\\s+(the|using)|great\\s+(product|job|support)|kudos|awesome\\s+(work|product)"));
        RULES.put(Category.SALES, p("book\\s+a\\s+demo|partnership|our\\s+agency|lead\\s+generation"));
    }

    private static Pattern p(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE); }

    public Classification classify(ParsedEmail e, String why) {
        String text = (e.subject() + "\n" + e.body()).toLowerCase(Locale.ROOT);
        Category best = null;
        // Priority order matters: first matching rule in this order wins.
        for (Category c : new Category[]{Category.OUTAGE, Category.ACCOUNT_ACCESS, Category.BILLING, Category.WRONG_BEHAVIOUR,
                Category.BUG, Category.ENHANCEMENT, Category.SALES, Category.PRAISE, Category.QUERY}) {
            if (RULES.get(c).matcher(text).find()) { best = c; break; }
        }
        // A how-to question that merely mentions a failure ("can I replay failed rows?") is a query, not a bug report.
        if (best == Category.BUG && e.subject().contains("?") && !text.contains("exception") && !text.contains("crash")
                && !text.contains("every run") && !text.contains("stack")) best = Category.QUERY;
        boolean matched = best != null;
        if (best == null) best = Category.IRRELEVANT;
        String priority = switch (best) {
            case OUTAGE -> "P1";
            case ACCOUNT_ACCESS, BILLING -> "P2";
            case BUG, WRONG_BEHAVIOUR -> text.contains("data loss") || text.contains("production") ? "P2" : "P3";
            default -> "P4";
        };
        String summary = e.subject();
        return new Classification(best, priority, summary, matched ? 0.55 : 0.3, "heuristic", true, why);
    }
}
