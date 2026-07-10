package com.wallet.security;

import com.wallet.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * Spring Security's authentication/authorization machinery works in terms
 * of its own {@link UserDetails} interface - it has no idea our domain
 * has a `User` @Entity, and it shouldn't (Spring Security is a generic
 * library usable by any application). This class is the small ADAPTER
 * that bridges the two: it wraps our actual `User` entity so Spring
 * Security's filters can ask it the handful of yes/no questions they need
 * ("what's the username?", "what's the password hash?", "is this account
 * expired?") without needing to understand anything else about our
 * domain.
 *
 * This pattern (wrap your own entity to implement UserDetails) is the
 * standard, idiomatic way nearly every Spring Security + JPA tutorial or
 * real project does this - worth remembering by name for interviews:
 * it's often just called a "UserDetails adapter/implementation".
 */
public class UserPrincipal implements UserDetails {

    private final User user;

    public UserPrincipal(User user) {
        this.user = user;
    }

    /** Lets other code (e.g. AuthController, a future @RestController) get back the real entity - e.g. to read the numeric id or name - once Spring Security has already authenticated the request. */
    public User getUser() {
        return user;
    }

    /**
     * Spring Security calls this "username", but for us it's simply the
     * user's email (see User.java: "also doubles as the login username").
     * We don't maintain a separate username field anywhere - one less
     * thing to keep in sync.
     */
    @Override
    public String getUsername() {
        return user.getEmail();
    }

    /** The BCrypt hash (never the raw password - see User.passwordHash's javadoc) that AuthenticationManager compares a login attempt's raw password against. */
    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    /**
     * Roles/permissions this user has. We map the user's single {@link
     * com.wallet.entity.Role} to a Spring Security authority string with the
     * conventional "ROLE_" prefix (ROLE_USER or ROLE_ADMIN). That prefix is
     * what lets SecurityConfig's {@code .hasRole("ADMIN")} rule work - Spring
     * automatically re-adds "ROLE_" when you use hasRole(...), so the two
     * halves must agree on the convention.
     *
     * AuthorityUtils.createAuthorityList is just a small Spring Security
     * helper that builds a List<GrantedAuthority> from plain strings.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList("ROLE_" + user.getRole().name());
    }

    /**
     * The four account-status checks below all return `true` (i.e.
     * "nothing is wrong") because this project has no account
     * expiration, locking, or credential-expiry features implemented in
     * this phase. Returning true simply means Spring Security won't
     * reject an otherwise-valid login for any of these reasons. If a
     * future phase added, say, an account-suspension feature, that flag
     * would be read from the User entity and returned from
     * isEnabled() instead of a hardcoded true.
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
