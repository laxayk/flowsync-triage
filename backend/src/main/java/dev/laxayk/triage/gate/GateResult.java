package dev.laxayk.triage.gate;

import dev.laxayk.triage.domain.Verdict;

import java.util.List;

public record GateResult(Verdict verdict, List<String> reasons) {
    public String reasonText() { return String.join("; ", reasons); }
}
