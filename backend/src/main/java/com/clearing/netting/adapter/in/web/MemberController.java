package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.AuthContext;
import com.clearing.netting.application.MemberApplicationService;
import com.clearing.netting.application.MemberPositionHistoryService;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NettingRunStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberApplicationService memberService;
    private final MemberPositionHistoryService positionHistoryService;

    public MemberController(
            MemberApplicationService memberService,
            MemberPositionHistoryService positionHistoryService) {
        this.memberService = memberService;
        this.positionHistoryService = positionHistoryService;
    }

    @GetMapping
    public List<MemberResponse> list() {
        AuthContext.require();
        return memberService.listMembers().stream().map(MemberResponse::from).collect(Collectors.toList());
    }

    @PostMapping
    public MemberResponse create(@Valid @RequestBody CreateMemberRequest request) {
        AuthContext.requireOperator();
        return MemberResponse.from(memberService.createMember(request.name()));
    }

    @PostMapping("/{id}/status")
    public MemberResponse updateStatus(@PathVariable("id") String id, @Valid @RequestBody StatusRequest request) {
        AuthContext.requireOperator();
        return MemberResponse.from(memberService.updateStatus(id, request.status()));
    }

    @GetMapping("/{id}/position-history")
    public PositionHistoryResponse positionHistory(@PathVariable("id") String id) {
        AuthContext.require();
        MemberPositionHistoryService.MemberPositionHistory history = positionHistoryService.getHistory(id);
        return PositionHistoryResponse.from(history);
    }

    public record CreateMemberRequest(@NotBlank String name) {
    }

    public record StatusRequest(@NotNull MemberStatus status) {
    }

    public record MemberResponse(String memberId, String name, MemberStatus status) {
        static MemberResponse from(Member m) {
            return new MemberResponse(m.getMemberId(), m.getName(), m.getStatus());
        }
    }

    public record PositionHistoryResponse(
            String memberId,
            String memberName,
            String aggregationRule,
            List<PositionHistoryRow> rows) {
        static PositionHistoryResponse from(MemberPositionHistoryService.MemberPositionHistory h) {
            return new PositionHistoryResponse(
                    h.member().getMemberId(),
                    h.member().getName(),
                    h.aggregationRule(),
                    h.rows().stream().map(PositionHistoryRow::from).collect(Collectors.toList()));
        }
    }

    public record PositionHistoryRow(
            LocalDate settleDate,
            String currency,
            BigDecimal totalNetAmount,
            int sourceCount,
            List<PositionHistorySource> sources) {
        static PositionHistoryRow from(MemberPositionHistoryService.HistoryRow r) {
            return new PositionHistoryRow(
                    r.settleDate(),
                    r.currency(),
                    r.totalNetAmount(),
                    r.sources().size(),
                    r.sources().stream().map(PositionHistorySource::from).collect(Collectors.toList()));
        }
    }

    public record PositionHistorySource(
            String runId,
            Instant createdAt,
            NettingRunStatus runStatus,
            boolean settled,
            BigDecimal netAmount) {
        static PositionHistorySource from(MemberPositionHistoryService.HistorySource s) {
            return new PositionHistorySource(
                    s.runId(),
                    s.createdAt(),
                    s.runStatus(),
                    s.settled(),
                    s.netAmount());
        }
    }
}
