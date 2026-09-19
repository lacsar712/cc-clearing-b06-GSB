package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
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

/**
 * Read-side query: a member's historical net positions across netting runs.
 *
 * Aggregation rule: only positions from runs in status COMPLETED are counted
 * (a settled run keeps status COMPLETED — settle only flips its obligations to
 * SETTLED — so settled batches are included; CREATED/RUNNING/FAILED are not).
 * Positions are grouped by (settleDate, currency); when the member appears in
 * several runs for the same date and currency, totalNetAmount is the algebraic
 * sum of the per-run net amounts, and every contributing run is listed in
 * {@code sources} so the total can be verified against each run's detail.
 */
@Service
public class PositionHistoryApplicationService {

    private final MemberRepositoryPort memberRepository;
    private final NetPositionRepositoryPort positionRepository;
    private final NettingRunRepositoryPort runRepository;

    public PositionHistoryApplicationService(
            MemberRepositoryPort memberRepository,
            NetPositionRepositoryPort positionRepository,
            NettingRunRepositoryPort runRepository) {
        this.memberRepository = memberRepository;
        this.positionRepository = positionRepository;
        this.runRepository = runRepository;
    }

    @Transactional(readOnly = true)
    public List<MemberPositionHistoryRow> getMemberPositionHistory(String memberId) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new DomainException("MEMBER_NOT_FOUND", "member not found: " + memberId));

        List<NetPosition> positions = positionRepository.findByMemberId(memberId);
        if (positions.isEmpty()) {
            return List.of();
        }

        Map<String, NettingRun> runsById = new HashMap<>();
        for (NettingRun run : runRepository.findAllOrderByCreatedAtDesc()) {
            runsById.put(run.getRunId(), run);
        }

        Map<GroupKey, List<SourceRun>> sourcesByGroup = new HashMap<>();
        for (NetPosition position : positions) {
            NettingRun run = runsById.get(position.getRunId());
            if (run == null || run.getStatus() != NettingRunStatus.COMPLETED) {
                continue;
            }
            GroupKey key = new GroupKey(run.getSettleDate(), run.getCurrency());
            sourcesByGroup.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new SourceRun(run.getRunId(), run.getStatus(), run.getCreatedAt(), position.getNetAmount()));
        }

        List<MemberPositionHistoryRow> rows = new ArrayList<>();
        for (Map.Entry<GroupKey, List<SourceRun>> entry : sourcesByGroup.entrySet()) {
            GroupKey key = entry.getKey();
            List<SourceRun> sources = entry.getValue();
            sources.sort(Comparator
                    .comparing(SourceRun::runCreatedAt)
                    .thenComparing(SourceRun::runId));
            BigDecimal total = sources.stream()
                    .map(SourceRun::netAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(8, RoundingMode.HALF_UP);
            rows.add(new MemberPositionHistoryRow(
                    key.settleDate(),
                    key.currency(),
                    total,
                    sources.size(),
                    List.copyOf(sources)));
        }
        rows.sort(Comparator
                .comparing(MemberPositionHistoryRow::settleDate).reversed()
                .thenComparing(MemberPositionHistoryRow::currency));
        return rows;
    }

    private record GroupKey(LocalDate settleDate, String currency) {
    }

    public record SourceRun(
            String runId,
            NettingRunStatus runStatus,
            Instant runCreatedAt,
            BigDecimal netAmount) {
    }

    public record MemberPositionHistoryRow(
            LocalDate settleDate,
            String currency,
            BigDecimal totalNetAmount,
            int runCount,
            List<SourceRun> sources) {
    }
}
