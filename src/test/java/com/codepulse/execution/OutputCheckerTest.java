package com.codepulse.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutputCheckerTest {

    @Test
    void normalizesWindowsLineEndingsAndTrailingHorizontalWhitespace() {
        assertTrue(OutputChecker.matches("first  \r\nsecond\t\r\n", "first\nsecond\n"));
    }

    @Test
    void doesNotIgnoreDifferentTokensOrExtraBlankLines() {
        assertFalse(OutputChecker.matches("answer\n", "answer\n\n"));
        assertFalse(OutputChecker.matches("10\n", "01\n"));
    }
}
