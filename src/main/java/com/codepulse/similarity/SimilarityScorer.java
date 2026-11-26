package com.codepulse.similarity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimilarityScorer {

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*|\\d+|==|!=|<=|>=|&&|\\|\\||\\+\\+|--|->|\\S");
    private static final int SHINGLE_SIZE = 5;

    private SimilarityScorer() {}

    public static double jaccardSimilarity(String firstSource, String secondSource) {
        Set<String> first = shingles(firstSource);
        Set<String> second = shingles(secondSource);
        if (first.isEmpty() && second.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> shingles(String source) {
        Matcher matcher = TOKEN_PATTERN.matcher(source);
        List<String> tokens = matcher.results().map(result -> result.group()).toList();
        Set<String> shingles = new HashSet<>();
        for (int start = 0; start + SHINGLE_SIZE <= tokens.size(); start++) {
            shingles.add(String.join(" ", tokens.subList(start, start + SHINGLE_SIZE)));
        }
        return shingles;
    }
}
