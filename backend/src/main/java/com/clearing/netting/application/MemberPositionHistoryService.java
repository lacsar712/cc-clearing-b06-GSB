package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only aggregation of one member's historical net positions.
 *
 * <p>Only netting runs in status {@link NettingRunStatus#COMPLETED} are included. A run whose
 * obligations have been settled keeps status COMPLETED (settle flips obligation status only),
 * so "completed" already covers both completed-not-settled and settled runs. Positions of the
 * member are grouped by (settleDate, currency) and algebraically summed; each contributing run
 * is preserved so every aggregate row can be traced back to its source batch.
 */
@Service
public class MemberPositionHistoryService {

    public static final String AGGREGATION_RULE =
            "仅聚合状态为 COMPLETED 的轧差批次（义务已 SETTLED 的批次其批次状态仍为 COMPLETED，故同样纳入）；"
                    + "同一会员在多个批次中的净头寸，按【交割日 + 币种】分组做代数求和（正=应收，负=应付），"
                    + "每个来源批次的净头寸在展开行中逐项列出，可与批次详情逐笔核对。";

    private final MemberRepositoryPort memberRepository;
    private final NettingRunRepositoryPort runRepository;
    private final NetPositionRepositoryPort positionRepository;
    private final ObligationRepositoryPort obligationRepository;

    public MemberPositionHistoryService(
            MemberRepositoryPort memberRepository,
            NettingRunRepositoryPort runRepository,
            NetPositionRepositoryPort positionRepository,
            ObligationRepositoryPort obligationRepository) {
        this.memberRepository = memberRepository;
        this.runRepository = runRepository;
        this.positionRepository = positionRepository;
        this.obligationRepository = obligationRepository;
    }

    @Transactional(readOnly = true)
    public MemberPositionHistory getHistory(String memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new DomainException("MEMBER_NOT_FOUND", "member not found: " + memberId));

        // Only COMPLETED runs count (settled runs remain COMPLETED at the run level).
        List<NettingRun> completedRuns = runRepository.findByStatus(NettingRunStatus.COMPLETED);
        if (completedRuns.isEmpty()) {
            return new MemberPositionHistory(member, AGGREGATION_RULE, List.of());
        }

        Map<String, NettingRun> runById = new HashMap<>();
        List<String> runIds = new ArrayList<>();
        for (NettingRun run : completedRuns) {
            runById.put(run.getRunId(), run);
            runIds.add(run.getRunId());
        }

        List<NetPosition> positions = positionRepository.findByMemberIdAndRunIdIn(memberId, runIds);
        if (positions.isEmpty()) {
            return new MemberPositionHistory(member, AGGREGATION_RULE, List.of());
        }

        Map<String, Boolean> settledByRun = resolveSettledFlags(runIds);

        // Key order: settleDate asc, then currency asc — stable, readable rows.
        TreeMap<RowKey, List<HistorySource>> grouped = new TreeMap<>(
                Comparator.comparing(RowKey::settleDate).thenComparing(RowKey::currency));

        for (NetPosition p : positions) {
            NettingRun run = runById.get(p.getRunId());
            if (run == null) {
                continue; // defensive: position references an unexpected run
            }
            boolean settled = settledByRun.getOrDefault(p.getRunId(), Boolean.FALSE);
            RowKey key = new RowKey(run.getSettleDate(), run.getCurrency());
            grouped.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new HistorySource(
                            run.getRunId(),
                            run.getCreatedAt(),
                            run.getStatus(),
                            settled,
                            p.getNetAmount()));
        }

        List<HistoryRow> rows = new ArrayList<>();
        for (Map.Entry<RowKey, List<HistorySource>> e : grouped.entrySet()) {
            List<HistorySource> sources = e.getValue();
            sources.sort(Comparator.comparing(HistorySource::createdAt).thenComparing(HistorySource::runId));
            BigDecimal total = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
            for (HistorySource s : sources) {
                total = total.add(s.netAmount());
            }
            rows.add(new HistoryRow(e.getKey().settleDate(), e.getKey().currency(), total, sources));
        }

        return new MemberPositionHistory(member, AGGREGATION_RULE, rows);
    }

    private Map<String, Boolean> resolveSettledFlags(List<String> runIds) {
        Map<String, List<TradeObligation>> obligationsByRun = new HashMap<>();
        for (TradeObligation o : obligationRepository.findByNettingRunIdIn(runIds)) {
            obligationsByRun.computeIfAbsent(o.getNettingRunId(), k -> new ArrayList<>()).add(o);
        }
        Map<String, Boolean> flags = new HashMap<>();
        for (Map.Entry<String, List<TradeObligation>> e : obligationsByRun.entrySet()) {
            List<TradeObligation> obs = e.getValue();
            boolean settled = !obs.isEmpty()
                    && obs.stream().allMatch(o -> o.getStatus() == ObligationStatus.SETTLED);
            flags.put(e.getKey(), settled);
        }
        return flags;
    }

    private record RowKey(LocalDate settleDate, String currency) {
    }

    public record MemberPositionHistory(Member member, String aggregationRule, List<HistoryRow> rows) {
    }

    public record HistoryRow(
            LocalDate settleDate,
            String currency,
            BigDecimal totalNetAmount,
            List<HistorySource> sources) {
    }

    public record HistorySource(
            String runId,
            Instant createdAt,
            NettingRunStatus runStatus,
            boolean settled,
            BigDecimal netAmount) {
    }
}
