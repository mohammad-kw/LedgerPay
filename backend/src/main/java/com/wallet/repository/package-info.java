/**
 * Spring Data JPA repository interfaces - one per entity in
 * com.wallet.entity (e.g. UserRepository extends JpaRepository&lt;User,
 * Long&gt;). We only ever need to declare an interface here; Spring Data
 * JPA generates the actual implementation (basic CRUD, plus derived query
 * methods like findByEmail(String email) just from the method name) at
 * application startup - no hand-written SQL/JPQL needed for the common
 * cases.
 *
 * Repositories are the ONLY layer that should talk to
 * EntityManager/Hibernate directly (indirectly, through Spring Data).
 * Controllers and services should never issue raw SQL themselves.
 *
 * Empty for now - implemented in a later phase, per PROJECT_SPEC.md
 * Section 10's phase-by-phase build plan (the entity classes these will
 * wrap already exist in com.wallet.entity as of this phase).
 */
package com.wallet.repository;
