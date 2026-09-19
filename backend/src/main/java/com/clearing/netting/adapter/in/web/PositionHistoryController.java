package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.AuthContext;
import com.clearing.netting.application.PositionHistoryApplicationService;
import com.clearing.netting.domain.model.NettingRunStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/members")
public class PositionHistoryController {

    private final PositionHistoryApplicationService positionHistoryService;

    public PositionHistoryController(PositionHistoryApplicationService positionHistoryService) {
        this.positionHistoryService = positionHistoryService;
    }

    @GetMapping("/{memberId}/position-history")
    public List<PositionHistoryRowResponse> history(@PathVariable("memberId") String memberId) {
        AuthContext.require();
        return positionHistoryService.getMemberPositionHistory(memberId).stream()
                .map(PositionHistoryRowResponse::from)
                .collect(Collectors.toList());
    }

    public record SourceRunResponse(
            String runId,
            NettingRunStatus runStatus,
            Instant runCreatedAt,
            BigDecimal netAmount) {
        static SourceRunResponse from(PositionHistoryApplicationService.SourceRun s) {
            return new SourceRunResponse(s.runId(), s.runStatus(), s.runCreatedAt(), s.netAmount());
        }
    }

    public record PositionHistoryRowResponse(
            LocalDate settleDate,
            String currency,
            BigDecimal totalNetAmount,
            int runCount,
            List<SourceRunResponse> sources) {
        static PositionHistoryRowResponse from(PositionHistoryApplicationService.MemberPositionHistoryRow r) {
            return new PositionHistoryRowResponse(
                    r.settleDate(),
                    r.currency(),
                    r.totalNetAmount(),
                    r.runCount(),
                    r.sources().stream().map(SourceRunResponse::from).collect(Collectors.toList()));
        }
    }
}
