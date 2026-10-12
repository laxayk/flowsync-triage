package dev.laxayk.triage.gate;

import dev.laxayk.triage.domain.ParsedEmail;
import dev.laxayk.triage.domain.Verdict;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic safety and relevance gate. Runs before the LLM so that:
 *  - hostile or junk mail never reaches the model (cheaper, and removes the injection surface for obvious attacks),
 *  - the verdict is explainable and unit-testable.
 * The LLM classifier can still mark a mail SPAM/SALES/IRRELEVANT later, for what the rules miss.
 */
@Component
public class SafetyGate {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')]+", Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("ignore\\s+(all\\s+|any\\s+|your\\s+)?(previous|prior|above|earlier)\\s+(instructions|rules|prompts?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+|the\\s+|your\\s+)?(previous|prior|above|system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|print|show|repeat|leak)\\s+(your\\s+|the\\s+)?(system\\s+prompt|hidden\\s+instructions|api\\s*key|secrets?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(a|an|the|in)\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(<\\|im_start\\|>|<\\|system\\|>|\\[/?INST]|###\\s*system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(classify|mark|set|label)\\s+(this|the)\\s+(email|ticket|message)\\s+as\\s+(p0|p1|urgent|critical|resolved)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forward\\s+(all|every)\\s+(tickets?|emails?|customer\\s+data)\\s+to", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(AI|assistant|agent|model)\\s*[:,]?\\s*(please\\s+)?(do\\s+not|don't)\\s+(tell|mention|flag|log)", Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> PHISH_PHRASES = List.of(
            Pattern.compile("verify\\s+your\\s+(account|identity|password|credentials)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(password|account)\\s+(will\\s+)?(expire|be\\s+(suspended|locked|closed))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(confirm|update)\\s+your\\s+(payment|billing|bank|card)\\s+(details|information)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("unusual\\s+(sign[- ]?in|activity)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(wire|gift\\s*card|bitcoin|crypto)\\s+(transfer|payment|payments)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("click\\s+(here|the\\s+link\\s+below)\\s+(immediately|now|within)", Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> SPAM_PHRASES = List.of(
            Pattern.compile("(you\\s+(have\\s+)?won|lottery|jackpot|claim\\s+your\\s+(prize|reward))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(double|triple)\\s+your\\s+(money|bitcoin|crypto|investment)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(viagra|cialis|casino|porn|adult\\s+dating|weight\\s+loss\\s+pill)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(100%\\s+free|risk[- ]free|act\\s+now|limited\\s+time\\s+offer|no\\s+credit\\s+check)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(buy\\s+followers|seo\\s+ranking\\s+guaranteed|cheap\\s+backlinks)", Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> SALES_PHRASES = List.of(
            Pattern.compile("(book|schedule)\\s+a\\s+(quick\\s+)?(demo|call|15[- ]minute)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(we\\s+help|helping)\\s+(companies|teams|businesses)\\s+(like\\s+yours|to)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(partnership|collaboration)\\s+opportunit", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(our\\s+(agency|team)\\s+(offers|provides|specializes)|lead\\s+generation|outsourc(e|ing)\\s+(your|dev))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reply\\s+['\"]?(stop|unsubscribe)|to\\s+unsubscribe|opt\\s+out\\s+of\\s+future)", Pattern.CASE_INSENSITIVE));

    private static final Pattern AUTO_SUBJECT = Pattern.compile(
            "^(automatic\\s+reply|auto[- ]?reply|out\\s+of\\s+(the\\s+)?office|undeliverable|delivery\\s+status\\s+notification|mail\\s+delivery\\s+(failed|failure))",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> RISKY_EXT = Set.of("exe", "scr", "bat", "cmd", "js", "jse", "vbs", "msi", "jar",
            "iso", "img", "lnk", "ps1", "dll", "hta", "docm", "xlsm", "pptm");
    private static final Set<String> SHORTENERS = Set.of("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "cutt.ly", "rb.gy");
    private static final Set<String> RISKY_TLDS = Set.of("zip", "top", "xyz", "click", "country", "gq", "tk", "ml", "cf");
    private static final Pattern IP_HOST = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    public GateResult evaluate(ParsedEmail e) {
        String subject = e.subject() == null ? "" : e.subject();
        String text = subject + "\n" + (e.body() == null ? "" : e.body());

        // 1. Prompt injection: hold for a human. Never reaches the LLM.
        List<String> inj = matches(INJECTION, text, "instruction override");
        if (!inj.isEmpty()) return new GateResult(Verdict.PROMPT_INJECTION, inj);

        // 2. Dangerous attachments.
        List<String> att = new ArrayList<>();
        for (var a : e.attachments()) {
            String n = a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT);
            String[] parts = n.split("\\.");
            String ext = parts.length > 1 ? parts[parts.length - 1] : "";
            if (RISKY_EXT.contains(ext)) att.add("risky attachment type ." + ext);
            else if (parts.length > 2 && Set.of("pdf", "doc", "docx", "xls", "xlsx", "jpg", "png", "txt").contains(parts[parts.length - 2])
                    && RISKY_EXT.contains(ext)) att.add("double extension " + n);
        }
        if (!att.isEmpty()) return new GateResult(Verdict.SUSPICIOUS_ATTACHMENT, att);

        // 3. Phishing: scored from phrases and link features.
        List<String> phish = new ArrayList<>(matches(PHISH_PHRASES, text, "credential/payment lure"));
        int linkFlags = 0;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String host = hostOf(m.group());
            if (host == null) continue;
            String tld = host.substring(host.lastIndexOf('.') + 1);
            if (IP_HOST.matcher(host).matches()) { phish.add("link to raw IP " + host); linkFlags++; }
            else if (SHORTENERS.contains(host)) { phish.add("shortened link " + host); linkFlags++; }
            else if (RISKY_TLDS.contains(tld)) { phish.add("link on risky TLD ." + tld); linkFlags++; }
            else if (host.contains("xn--")) { phish.add("punycode host " + host); linkFlags++; }
            else if (host.split("\\.").length > 4) { phish.add("deeply nested host " + host); linkFlags++; }
        }
        long lures = phish.stream().filter(s -> s.contains("lure")).count();
        boolean lure = lures > 0;
        if ((lure && linkFlags > 0) || lures >= 2 || phish.size() >= 3) return new GateResult(Verdict.PHISHING, phish);

        // 4. Spam.
        List<String> spam = new ArrayList<>(matches(SPAM_PHRASES, text, "spam phrase"));
        int strongSpam = spam.size();
        long excl = text.chars().filter(c -> c == '!').count();
        if (excl >= 5) spam.add("excessive exclamation marks");
        if (subject.length() > 8 && subject.equals(subject.toUpperCase(Locale.ROOT)) && subject.chars().anyMatch(Character::isLetter))
            spam.add("all-caps subject");
        // Caps and exclamation marks alone are weak: a customer shouting about an outage must not be quarantined.
        if (strongSpam > 0 && (spam.size() >= 2 || lure || linkFlags > 0)) return new GateResult(Verdict.SPAM, spam);

        // 5. Auto-replies: headers first, subject as backup.
        if (e.autoGenerated() || AUTO_SUBJECT.matcher(subject.trim()).find())
            return new GateResult(Verdict.AUTO_REPLY, List.of("automatic or bounce message, no human request"));

        // 6. Cold sales outreach.
        List<String> sales = matches(SALES_PHRASES, text, "sales outreach phrase");
        if (sales.size() >= 2) return new GateResult(Verdict.SALES, sales);

        if (strongSpam > 0) return new GateResult(Verdict.SPAM, spam);
        return new GateResult(Verdict.CLEAN, List.of("passed all gate checks"));
    }

    private static List<String> matches(List<Pattern> patterns, String text, String label) {
        List<String> out = new ArrayList<>();
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) out.add(label + ": \"" + abbreviate(m.group(), 60) + "\"");
        }
        return out;
    }

    private static String abbreviate(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url.replaceAll("[.,;]+$", "")).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
