package com.nexus.backend.repository;

import com.nexus.backend.domain.company.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    /**
     * Loads a company holding a row lock for the rest of the transaction.
     *
     * <p>Used to hand out the next employee code. {@code employee_seq} is a
     * counter, and two admins creating an account at the same moment would
     * otherwise read the same value and the second insert would fail on
     * {@code uq_users_company_employee_code}. The lock serialises just those two
     * allocations rather than the whole table.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") Long id);

    /** Matches the case-insensitive unique index on company names. */
    @Query("select c from Company c where lower(c.name) = lower(:name)")
    Optional<Company> findByNameIgnoreCase(@Param("name") String name);
}
