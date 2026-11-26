package com.codepulse.similarity;

import java.util.List;

import com.codepulse.auth.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/similarity")
public class AdminSimilarityController {

    private final SimilarityService similarityService;

    public AdminSimilarityController(SimilarityService similarityService) {
        this.similarityService = similarityService;
    }

    @GetMapping("/review")
    public List<SimilarityService.SimilarityPair> listReviewPairs() {
        return similarityService.listReviewPairs();
    }

    @PostMapping("/{pairId}/review")
    public ResponseEntity<Void> markReviewed(
            @PathVariable long pairId,
            @AuthenticationPrincipal UserPrincipal principal) {
        similarityService.markReviewed(pairId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
