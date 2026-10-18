package com.nexus.backend.service;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out employee codes ({@code NX-0007}), numbered per company.
 *
 * <p>Codes used to come from a single global sequence, which stopped being
 * correct the moment two companies shared this database: it would leak one
 * company's headcount to another through gaps in its own numbering, and V19
 * deliberately dropped the global unique index in favour of
 * {@code uq_users_company_employee_code}. Numbering now lives on the company row
 * itself, so each company counts its own people from one.
 */
@Service
@RequiredArgsConstructor
public class EmployeeCodeService {

    /** Zero-padded to four digits, matching the codes the seed data already uses. */
    private static final String CODE_FORMAT = "NX-%04d";

    private final CompanyRepository companyRepository;

    /**
     * Reserves and returns the next code for a company.
     *
     * <p>Must run inside a transaction: the row lock it takes on the company is
     * only held until that transaction ends, and it is the lock — not the read —
     * that stops two concurrent admins being handed the same number.
     */
    @Transactional
    public String nextCode(Long companyId) {
        if (companyId == null) {
            throw new IllegalStateException(
                "An employee code is numbered per company, so a company must be known before one is issued.");
        }
        Company company = companyRepository.findByIdForUpdate(companyId)
            .orElseThrow(() -> new ResourceNotFoundException("Company", "id", companyId));

        long next = company.getEmployeeSeq() + 1;
        company.setEmployeeSeq(next);
        companyRepository.save(company);
        return format(next);
    }

    /** The display form of a sequence value, e.g. 7 -> {@code NX-0007}. */
    public static String format(long sequence) {
        return CODE_FORMAT.formatted(sequence);
    }
}
