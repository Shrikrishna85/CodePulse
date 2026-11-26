package com.codepulse.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserPrincipalTest {

    @Test
    void exposesStudentAuthorityAndNonLockedStatus() {
        UserPrincipal principal = new UserPrincipal(1, "student", "hash", "STUDENT", true, false);

        assertEquals("student", principal.getUsername());
        assertTrue(principal.isAccountNonLocked());
        assertEquals("ROLE_STUDENT", principal.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void exposesAdminAuthorityAndLockedStatus() {
        UserPrincipal principal = new UserPrincipal(2, "admin", "hash", "ADMIN", true, true);

        assertFalse(principal.isAccountNonLocked());
        assertEquals("ROLE_ADMIN", principal.getAuthorities().iterator().next().getAuthority());
    }
}
