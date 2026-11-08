package rw.rra.roomiq.lifecycle.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LifecyclePolicyRuleRepository extends JpaRepository<LifecyclePolicyRule, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rule from LifecyclePolicyRule rule where rule.ruleId = :id")
    java.util.Optional<LifecyclePolicyRule> findByIdForUpdate(@Param("id") UUID id);

    List<LifecyclePolicyRule> findByPolicyVersion_PolicyVersionIdOrderByRuleCode(UUID policyVersionId);

    List<LifecyclePolicyRule> findByPolicyVersion_PolicyVersionIdAndCategoryCode(
            UUID policyVersionId, String categoryCode);

    List<LifecyclePolicyRule> findByPolicyVersion_PolicyVersionIdInAndCategoryCode(
            List<UUID> policyVersionIds, String categoryCode);
}
