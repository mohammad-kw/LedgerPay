package com.wallet.repository;

import com.wallet.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link User}.
 *
 * We only declare an INTERFACE here - we never write an implementation
 * class ourselves. At application startup, Spring Data JPA generates a
 * real implementation behind the scenes (using a dynamic proxy) that:
 *   - Implements every method inherited from JpaRepository (save,
 *     findById, findAll, delete, etc.) using Hibernate underneath.
 *   - Implements our own custom method below (findByEmail) by PARSING its
 *     method name: "findBy" + "Email" tells Spring Data to generate the
 *     JPQL equivalent of
 *     "SELECT u FROM User u WHERE u.email = :email" automatically. No SQL
 *     or JPQL was hand-written for this - the method name IS the query
 *     definition.
 *
 * JpaRepository<User, Long> - the two generic types are:
 *   User -> the entity type this repository manages
 *   Long -> the type of that entity's @Id field
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Used during registration (to reject duplicate emails with a clear
     * error before hitting the database's UNIQUE constraint) and during
     * login (since email is the username - see PROJECT_SPEC.md Section 4:
     * "also doubles as the login username").
     *
     * Optional<User> (rather than returning User directly, or null) forces
     * every caller to explicitly handle the "no such user" case instead of
     * accidentally causing a NullPointerException somewhere down the
     * line - a well-known modern Java best practice for methods that may
     * have nothing to return.
     */
    Optional<User> findByEmail(String email);

    /**
     * A fast existence check - "does a user with this email already
     * exist?" - without paying the cost of loading and constructing an
     * entire User object like findByEmail would. Useful for registration
     * validation where we only care about a yes/no answer.
     */
    boolean existsByEmail(String email);
}
