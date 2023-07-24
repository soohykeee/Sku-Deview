package kr.co.skudeview.service;

import jakarta.transaction.Transactional;
import kr.co.skudeview.domain.Company;
import kr.co.skudeview.domain.Member;
import kr.co.skudeview.infra.exception.InvalidRequestException;
import kr.co.skudeview.infra.exception.NotFoundException;
import kr.co.skudeview.infra.model.ResponseStatus;
import kr.co.skudeview.repository.CompanyRepository;
import kr.co.skudeview.repository.MemberRepository;
import kr.co.skudeview.service.dto.request.CompanyRequestDto;
import kr.co.skudeview.service.dto.response.CompanyResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanyServiceImpl implements CompanyService {

    private final MemberRepository memberRepository;

    private final CompanyRepository companyRepository;

    @Override
    @Transactional
    public void createCompany(Long memberId, CompanyRequestDto.CREATE company) {
        Optional<Member> member = memberRepository.findMemberByIdAndDeleteAtFalse(memberId);

        isMember(member);
        isDateRange(company.getStartDate(), company.getEndDate());

        companyRepository.save(toEntity(company, member.get()));
    }

    @Override
    public List<CompanyResponseDto.READ> getCompaniesByMember(Long memberId) {
        return companyRepository.findCompaniesByMember_IdAndDeleteAtFalse(memberId)
                .stream()
                .map(this::toReadDto)
                .collect(Collectors.toList());
    }

    @Override
    public CompanyResponseDto.DETAIL getCompanyDetail(Long memberId, Long companyId) {
        return toDetailDto(companyRepository.findCompanyByMember_IdAndIdAndDeleteAtFalse(memberId, companyId).get());
    }

    @Override
    @Transactional
    public void updateCompany(Long memberId, CompanyRequestDto.UPDATE company) {
        final Optional<Member> member = memberRepository.findMemberByIdAndDeleteAtFalse(memberId);
        isMember(member);

        final Optional<Company> findCompany = companyRepository.findCompanyByIdAndDeleteAtFalse(company.getCompanyId());
        isCompany(findCompany);

        findCompany.get().updateCompany(company);

        companyRepository.save(findCompany.get());
    }

    @Override
    @Transactional
    public void deleteCompany(Long memberId, Long companyId) {
        Optional<Company> company = companyRepository.findCompanyByMember_IdAndIdAndDeleteAtFalse(memberId, companyId);

        isCompany(company);

        company.get().changeDeleteAt();

        companyRepository.save(company.get());
    }

    private void isMember(Optional<Member> member) {
        if (member.isEmpty()) {
            throw new NotFoundException(ResponseStatus.FAIL_MEMBER_NOT_FOUND);
        }
    }

    private void isCompany(Optional<Company> company) {
        if (company.isEmpty()) {
            throw new NotFoundException(ResponseStatus.FAIL_COMPANY_NOT_FOUND);
        }
    }

    private void isDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate) || startDate.isEqual(endDate)) {
            throw new InvalidRequestException(ResponseStatus.FAIL_DATE_RANGE_INVALID);
        }
    }
}

