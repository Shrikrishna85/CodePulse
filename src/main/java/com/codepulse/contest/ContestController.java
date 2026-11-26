package com.codepulse.contest;

import java.util.List;

import com.codepulse.auth.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contests")
public class ContestController {

    private final ContestService contestService;

    public ContestController(ContestService contestService) {
        this.contestService = contestService;
    }

    @GetMapping
    public List<ContestService.ContestSummary> listContests() {
        return contestService.listContests();
    }

    @PostMapping("/{contestId}/register")
    public void register(
            @PathVariable long contestId,
            @AuthenticationPrincipal UserPrincipal principal) {
        contestService.register(contestId, principal.id());
    }

    @GetMapping("/{contestId}/problems")
    public List<ContestService.ContestProblem> listProblems(@PathVariable long contestId) {
        return contestService.listProblems(contestId);
    }

    @GetMapping("/{contestId}/scoreboard")
    public List<ContestScoring.ScoreboardEntry> getScoreboard(@PathVariable long contestId) {
        return contestService.getScoreboard(contestId);
    }
}
