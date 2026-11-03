package rw.rra.roomiq.audit.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class AuditEventSearchRepositoryImpl implements AuditEventSearchRepository {
    private final EntityManager entityManager;

    public AuditEventSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<AuditEvent> search(Specification<AuditEvent> specification, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditEvent> contentQuery = builder.createQuery(AuditEvent.class);
        Root<AuditEvent> contentRoot = contentQuery.from(AuditEvent.class);
        Predicate contentPredicate = specification.toPredicate(contentRoot, contentQuery, builder);
        contentQuery.select(contentRoot).where(contentPredicate).orderBy(toOrders(pageable.getSort(), contentRoot, builder));

        TypedQuery<AuditEvent> typedContentQuery = entityManager.createQuery(contentQuery);
        typedContentQuery.setFirstResult(Math.toIntExact(pageable.getOffset()));
        typedContentQuery.setMaxResults(pageable.getPageSize());
        List<AuditEvent> content = typedContentQuery.getResultList();

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<AuditEvent> countRoot = countQuery.from(AuditEvent.class);
        Predicate countPredicate = specification.toPredicate(countRoot, countQuery, builder);
        countQuery.select(builder.count(countRoot)).where(countPredicate);
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private static List<Order> toOrders(Sort sort, Root<AuditEvent> root, CriteriaBuilder builder) {
        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            orders.add(order.isAscending()
                    ? builder.asc(root.get(order.getProperty()))
                    : builder.desc(root.get(order.getProperty())));
        }
        return orders;
    }
}
