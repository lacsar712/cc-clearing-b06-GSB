package com.clearing.netting.application;

import com.clearing.netting.application.PositionHistoryApplicationService.MemberPositionHistoryRow;
import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionHistoryApplicationServiceTest {

    private static final String MEMBER = "M1";
    private static final LocalDate DAY1 = LocalDate.of(2026, 9, 10);
    private static final LocalDate DAY2 = LocalDate.of(2026, 9, 11);

    private MemberRepositoryPort memberRepository;
    private NetPositionRepositoryPort positionRepository;
    private NettingRunRepositoryPort runRepository;
    private PositionHistoryApplicationService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepositoryPort.class);
        positionRepository = mock(NetPositionRepositoryPort.class);
        runRepository = mock(NettingRunRepositoryPort.class);
        service = new PositionHistoryApplicationService(memberRepository, positionRepository, runRepository);
        when(memberRepository.findById(MEMBER))
                .thenReturn(Optional.of(new Member(MEMBER, "Alpha Bank", MemberStatus.ACTIVE)));
    }

    @Test
    void groupsBySettleDateAndCurrencyAndSumsMultiRunRows() {
        NettingRun run1 = run("run-1", DAY1, "USD", NettingRunStatus.COMPLETED, 3);
        NettingRun run2 = run("run-2", DAY1, "USD", NettingRunStatus.COMPLETED, 2);
        NettingRun run3 = run("run-3", DAY2, "USD", NettingRunStatus.COMPLETED, 1);
        NettingRun run4 = run("run-4", DAY1, "CNY", NettingRunStatus.COMPLETED, 0);
        when(runRepository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(run1, run2, run3, run4));
        when(positionRepository.findByMemberId(MEMBER)).thenReturn(List.of(
                position("run-1", "USD", "-85000"),
                position("run-2", "USD", "20000"),
                position("run-3", "USD", "-20000"),
                position("run-4", "CNY", "-12000")
        ));

        List<MemberPositionHistoryRow> rows = service.getMemberPositionHistory(MEMBER);

        assertEquals(3, rows.size());

        // sorted by settleDate desc, then currency asc
        MemberPositionHistoryRow day2Usd = rows.get(0);
        assertEquals(DAY2, day2Usd.settleDate());
        assertEquals("USD", day2Usd.currency());
        assertEquals(0, day2Usd.totalNetAmount().compareTo(new BigDecimal("-20000.00000000")));
        assertEquals(1, day2Usd.runCount());

        MemberPositionHistoryRow day1Cny = rows.get(1);
        assertEquals(DAY1, day1Cny.settleDate());
        assertEquals("CNY", day1Cny.currency());
        assertEquals(0, day1Cny.totalNetAmount().compareTo(new BigDecimal("-12000.00000000")));

        // same member, same day, same currency across two runs: algebraic sum, both runs traceable
        MemberPositionHistoryRow day1Usd = rows.get(2);
        assertEquals(DAY1, day1Usd.settleDate());
        assertEquals("USD", day1Usd.currency());
        assertEquals(0, day1Usd.totalNetAmount().compareTo(new BigDecimal("-65000.00000000")));
        assertEquals(2, day1Usd.runCount());
        assertEquals(2, day1Usd.sources().size());
        // sources ordered by run createdAt asc: run-2 created before run-1
        assertEquals("run-2", day1Usd.sources().get(0).runId());
        assertEquals("run-1", day1Usd.sources().get(1).runId());
        BigDecimal sourceSum = day1Usd.sources().stream()
                .map(PositionHistoryApplicationService.SourceRun::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sourceSum.compareTo(day1Usd.totalNetAmount()));
    }

    @Test
    void excludesPositionsFromNonCompletedRuns() {
        NettingRun failed = run("run-f", DAY1, "USD", NettingRunStatus.FAILED, 0);
        NettingRun running = run("run-r", DAY1, "USD", NettingRunStatus.RUNNING, 1);
        NettingRun done = run("run-c", DAY1, "USD", NettingRunStatus.COMPLETED, 2);
        when(runRepository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(failed, running, done));
        when(positionRepository.findByMemberId(MEMBER)).thenReturn(List.of(
                position("run-f", "USD", "999"),
                position("run-r", "USD", "888"),
                position("run-c", "USD", "100")
        ));

        List<MemberPositionHistoryRow> rows = service.getMemberPositionHistory(MEMBER);

        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).totalNetAmount().compareTo(new BigDecimal("100.00000000")));
        assertEquals(1, rows.get(0).runCount());
        assertEquals("run-c", rows.get(0).sources().get(0).runId());
    }

    @Test
    void emptyWhenMemberHasNoPositions() {
        when(positionRepository.findByMemberId(MEMBER)).thenReturn(List.of());

        List<MemberPositionHistoryRow> rows = service.getMemberPositionHistory(MEMBER);

        assertTrue(rows.isEmpty());
    }

    @Test
    void throwsWhenMemberMissing() {
        when(memberRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        DomainException ex = assertThrows(DomainException.class,
                () -> service.getMemberPositionHistory("UNKNOWN"));
        assertEquals("MEMBER_NOT_FOUND", ex.getCode());
    }

    private NettingRun run(String runId, LocalDate settleDate, String currency,
                           NettingRunStatus status, long createdAtOffsetSeconds) {
        return new NettingRun(
                runId,
                settleDate,
                currency,
                status,
                Instant.parse("2026-09-10T00:00:00Z").plusSeconds(createdAtOffsetSeconds),
                null);
    }

    private NetPosition position(String runId, String currency, String amount) {
        return new NetPosition(
                java.util.UUID.randomUUID().toString(),
                runId,
                MEMBER,
                currency,
                new BigDecimal(amount));
    }
}
