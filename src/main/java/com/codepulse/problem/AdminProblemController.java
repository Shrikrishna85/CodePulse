package com.codepulse.problem;

import java.net.URI;
import java.util.List;

import com.codepulse.auth.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/problems")
public class AdminProblemController {

    private final ProblemService problemService;

    public AdminProblemController(ProblemService problemService) {
        this.problemService = problemService;
    }

    @PostMapping
    public ResponseEntity<ProblemCreated> createProblem(
            @RequestBody ProblemService.CreateProblem request,
            @AuthenticationPrincipal UserPrincipal principal) {
        long problemId = problemService.createProblem(request, principal.id());
        return ResponseEntity.created(URI.create("/api/admin/problems/" + problemId))
                .body(new ProblemCreated(problemId));
    }

    @PostMapping("/{problemId}/test-cases")
    public ResponseEntity<TestCaseCreated> addTestCase(
            @PathVariable long problemId,
            @RequestBody ProblemService.NewTestCase request,
            @AuthenticationPrincipal UserPrincipal principal) {
        int ordinal = problemService.addTestCase(problemId, request, principal.id());
        return ResponseEntity.status(201).body(new TestCaseCreated(ordinal));
    }

    @GetMapping("/{problemId}/test-cases")
    public List<ProblemService.AdminTestCase> listTestCases(@PathVariable long problemId) {
        return problemService.listTestCases(problemId);
    }

    @PutMapping("/{problemId}/published")
    public ResponseEntity<Void> setPublished(
            @PathVariable long problemId,
            @RequestBody PublishRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        problemService.setPublished(problemId, request.published(), principal.id());
        return ResponseEntity.noContent().build();
    }

    public record ProblemCreated(long id) {}

    public record TestCaseCreated(int ordinal) {}

    public record PublishRequest(boolean published) {}
}
