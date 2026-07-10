package com.wallet.security;

import com.wallet.entity.User;
import com.wallet.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security's standard extension point for "given a username, go
 * fetch that user's details from wherever they're actually stored" (in
 * our case: the `users` table, via UserRepository). Spring Security's
 * AuthenticationManager calls loadUserByUsername(...) automatically
 * during the login process (see SecurityConfig for how the
 * AuthenticationManager bean is wired up) - we never call this method
 * ourselves directly.
 *
 * @RequiredArgsConstructor is a Lombok annotation that generates a
 * constructor accepting every `final` field below (just `userRepository`
 * here) as a parameter. This is how we do "constructor injection" with
 * less boilerplate: Spring sees this is the only constructor and
 * automatically calls it, supplying its own managed UserRepository bean -
 * we never write `new CustomUserDetailsService(...)` ourselves anywhere.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * "username" here is really an email address, per User.java's javadoc
     * ("also doubles as the login username") - Spring Security's interface
     * just calls the parameter `username` generically since it has no
     * concept of "email" specifically.
     *
     * Throwing UsernameNotFoundException (a checked-looking but actually
     * unchecked exception defined by Spring Security itself) is the
     * CONTRACT this method must follow - Spring Security's login machinery
     * specifically catches this exception type and turns it into a
     * generic authentication failure. We deliberately do NOT reveal here
     * whether the problem was "no such email" vs "wrong password" (that
     * distinction happens later, inside AuthenticationManager, comparing
     * BCrypt hashes) - and even that failure is reported to the end user
     * as a generic "invalid credentials" message. This is a basic but
     * important security practice: telling an attacker "that email
     * doesn't exist" vs "that password is wrong" leaks information about
     * which emails are registered at all (this is called a user
     * enumeration vulnerability).
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("No user found with email: " + username));
        return new UserPrincipal(user);
    }
}
