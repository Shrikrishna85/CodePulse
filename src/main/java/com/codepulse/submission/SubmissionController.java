package com.codepulse.submission;

import java.net.URI;
import java.util.List;

import com.codepulse.auth.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    public ResponseEntity<SubmissionCreated> submit(
            @RequestBody SubmissionService.CreateSubmission request,
            @AuthenticationPrincipal UserPrincipal principal) {
        long submissionId = submissionService.submit(request, principal);
        return ResponseEntity.accepted()
                .location(URI.create("/api/submissions/" + submissionId))
                .body(new SubmissionCreated(submissionId, "QUEUED"));
    }

    @GetMapping("/mine")
    public List<SubmissionService.SubmissionSummary> listMine(
            @AuthenticationPrincipal UserPrincipal principal) {
        return submissionService.listMine(principal.id());
    }

    @GetMapping("/{submissionId}")
    public SubmissionService.SubmissionDetail getMine(
            @PathVariable long submissionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return submissionService.getMine(submissionId, principal.id());
    }

    public record SubmissionCreated(long id, String verdict) {}
}
