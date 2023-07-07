package kr.co.skudeview.repository;

import kr.co.skudeview.domain.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<Skill, Long> {

    Optional<Skill> findSkillById(Long SkillId);

    boolean existsSkillByName(String name);

}
