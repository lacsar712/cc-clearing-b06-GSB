package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberPositionHistoryServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 10);

    @Mock
    private MemberRepositoryPort memberRepository;
    @Mock
    private NettingRunRepositoryPort runRepository;
    @Mock
    private NetPositionRepositoryPort positionRepository;
    @Mock
    private ObligationRepositoryPort obligationRepository;

    @InjectMocks
    private MemberPositionHistoryService service;

    @Test
    void aggregatesOnlyCompletedRunsAndMarksSettledFlag() {
        Member m = member();
        NettingRun r1 = run("run-1", D, "USD", NettingRunStatus.COMPLETED);
        NettingRun r2 = run("run-2", D, "USD", NettingRunStatus.COMPLETED);

        when(memberRepository.findById("m1")).thenReturn(Optional.of(m));
        when(runRepository.findByStatus(NettingRunStatus.COMPLETED)).thenReturn(List.of(r1, r2));
        when(positionRepository.findByMemberIdAndRunIdIn(eq("m1"), anyList())).thenReturn(List.of(
                position("p1", "run-1", "USD", "100"),
                position("p2", "run-2", "USD", "-40")));
        // run-1 obligations still NETTED (not settled); run-2 obligations all SETTLED.
        when(obligationRepository.findByNettingRunIdIn(anyList())).thenReturn(List.of(
                obligation("o1", "run-1", ObligationStatus.NETTED),
                obligation("o2", "run-2", ObligationStatus.SETTLED),
                obligation("o3", "run-2", ObligationStatus.SETTLED)));

        MemberPositionHistoryService.MemberPositionHistory history = service.getHistory("m1");

        assertEquals(1, history.rows().size());
        MemberPositionHistoryService.HistoryRow row = history.rows().get(0);
        assertEquals(D, row.settleDate());
        assertEquals("USD", row.currency());
        assertEquals(0, new BigDecimal("60.00000000").compareTo(row.totalNetAmount()));
        assertEquals(2, row.sources().size());

        MemberPositionHistoryService.HistorySource s1 =
                row.sources().stream().filter(s -> s.runId().equals("run-1")).findFirst().orElseThrow();
        MemberPositionHistoryService.HistorySource s2 =
                row.sources().stream().filter(s -> s.runId().equals("run-2")).findFirst().orElseThrow();
        assertFalse(s1.settled(), "run with NETTED obligations must not be flagged settled");
        assertTrue(s2.settled(), "run with all SETTLED obligations must be flagged settled");

        // Only COMPLETED runs are ever requested from the repository / passed downstream.
        verify(runRepository).findByStatus(NettingRunStatus.COMPLETED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> runIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(positionRepository).findByMemberIdAndRunIdIn(eq("m1"), runIdsCaptor.capture());
        assertEquals(2, runIdsCaptor.getValue().size());
        assertTrue(runIdsCaptor.getValue().containsAll(List.of("run-1", "run-2")));
    }

    @Test
    void groupsByDateAndCurrencyAndSortsRows() {
        Member m = member();
        NettingRun r1 = run("run-1", D, "USD", NettingRunStatus.COMPLETED);
        NettingRun r2 = run("run-2", D, "USD", NettingRunStatus.COMPLETED);
        NettingRun r3 = run("run-3", D.plusDays(1), "EUR", NettingRunStatus.COMPLETED);

        when(memberRepository.findById("m1")).thenReturn(Optional.of(m));
        when(runRepository.findByStatus(NettingRunStatus.COMPLETED)).thenReturn(List.of(r1, r2, r3));
        when(positionRepository.findByMemberIdAndRunIdIn(eq("m1"), anyList())).thenReturn(List.of(
                position("p1", "run-1", "USD", "100"),
                position("p2", "run-2", "USD", "25"),
                position("p3", "run-3", "EUR", "7")));
        when(obligationRepository.findByNettingRunIdIn(anyList())).thenReturn(List.of(
                obligation("o1", "run-1", ObligationStatus.NETTED),
                obligation("o2", "run-2", ObligationStatus.NETTED),
                obligation("o3", "run-3", ObligationStatus.NETTED)));

        MemberPositionHistoryService.MemberPositionHistory history = service.getHistory("m1");

        assertEquals(2, history.rows().size());
        MemberPositionHistoryService.HistoryRow usd = history.rows().get(0);
        MemberPositionHistoryService.HistoryRow eur = history.rows().get(1);

        assertEquals(D, usd.settleDate());
        assertEquals("USD", usd.currency());
        assertEquals(0, new BigDecimal("125.00000000").compareTo(usd.totalNetAmount()));
        assertEquals(2, usd.sources().size());

        assertEquals(D.plusDays(1), eur.settleDate());
        assertEquals("EUR", eur.currency());
        assertEquals(0, new BigDecimal("7.00000000").compareTo(eur.totalNetAmount()));
        assertEquals(1, eur.sources().size());
    }

    @Test
    void partiallySettledRunIsNotFlaggedSettled() {
        Member m = member();
        NettingRun r1 = run("run-1", D, "USD", NettingRunStatus.COMPLETED);

        when(memberRepository.findById("m1")).thenReturn(Optional.of(m));
        when(runRepository.findByStatus(NettingRunStatus.COMPLETED)).thenReturn(List.of(r1));
        when(positionRepository.findByMemberIdAndRunIdIn(eq("m1"), anyList()))
                .thenReturn(List.of(position("p1", "run-1", "USD", "10")));
        when(obligationRepository.findByNettingRunIdIn(anyList())).thenReturn(List.of(
                obligation("o1", "run-1", ObligationStatus.SETTLED),
                obligation("o2", "run-1", ObligationStatus.NETTED)));

        MemberPositionHistoryService.MemberPositionHistory history = service.getHistory("m1");

        assertEquals(1, history.rows().size());
        assertFalse(history.rows().get(0).sources().get(0).settled());
    }

    @Test
    void noCompletedRunsReturnsEmptyRows() {
        Member m = member();
        when(memberRepository.findById("m1")).thenReturn(Optional.of(m));
        when(runRepository.findByStatus(NettingRunStatus.COMPLETED)).thenReturn(List.of());

        MemberPositionHistoryService.MemberPositionHistory history = service.getHistory("m1");

        assertTrue(history.rows().isEmpty());
        verify(positionRepository, never()).findByMemberIdAndRunIdIn(eq("m1"), anyList());
        verify(obligationRepository, never()).findByNettingRunIdIn(anyList());
    }

    @Test
    void memberWithoutPositionsReturnsEmptyRows() {
        Member m = member();
        NettingRun r1 = run("run-1", D, "USD", NettingRunStatus.COMPLETED);
        when(memberRepository.findById("m1")).thenReturn(Optional.of(m));
        when(runRepository.findByStatus(NettingRunStatus.COMPLETED)).thenReturn(List.of(r1));
        when(positionRepository.findByMemberIdAndRunIdIn(eq("m1"), anyList())).thenReturn(List.of());

        MemberPositionHistoryService.MemberPositionHistory history = service.getHistory("m1");

        assertTrue(history.rows().isEmpty());
        verify(obligationRepository, never()).findByNettingRunIdIn(anyList());
    }

    @Test
    void unknownMemberThrows() {
        when(memberRepository.findById("ghost")).thenReturn(Optional.empty());

        DomainException ex = assertThrows(DomainException.class, () -> service.getHistory("ghost"));
        assertEquals("MEMBER_NOT_FOUND", ex.getCode());
    }

    private Member member() {
        return new Member("m1", "Bank One", MemberStatus.ACTIVE);
    }

    private NettingRun run(String id, LocalDate settleDate, String currency, NettingRunStatus status) {
        return new NettingRun(id, settleDate, currency, status, Instant.parse("2026-09-10T08:00:00Z"), null);
    }

    private NetPosition position(String id, String runId, String currency, String amount) {
        return new NetPosition(id, runId, "m1", currency, new BigDecimal(amount));
    }

    private TradeObligation obligation(String id, String runId, ObligationStatus status) {
        return new TradeObligation(
                id, "other-a", "other-b", "USD", new BigDecimal("1"),
                D.minusDays(1), D, status, runId);
    }
}
