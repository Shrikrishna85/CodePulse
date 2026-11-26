package com.codepulse.similarity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SimilarityScorerTest {

    @Test
    void ignoresFormattingWhenComparingTokenShingles() {
        String first = "int sum(int a,int b){return a+b;}";
        String second = """
                int sum ( int a , int b )
                {
                    return a + b ;
                }
                """;

        assertEquals(1.0, SimilarityScorer.jaccardSimilarity(first, second));
    }

    @Test
    void unrelatedProgramsHaveLowerOverlap() {
        String first = "int add(int a,int b){return a+b;}";
        String second = "while (true) { printf(\"hello\"); }";

        assertEquals(0.0, SimilarityScorer.jaccardSimilarity(first, second));
    }
}
