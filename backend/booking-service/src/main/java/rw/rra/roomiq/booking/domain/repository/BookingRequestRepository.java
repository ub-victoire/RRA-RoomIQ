package rw.rra.roomiq.booking.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.common.web.DomainException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

public interface BookingRequestRepository extends JpaRepository<BookingRequest, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from BookingRequest request where request.id = :id")
    Optional<BookingRequest> findByIdForUpdate(@Param("id") UUID id);

    Optional<BookingRequest> findByIdempotencyKey(String idempotencyKey);

    @Query("select count(request) > 0 from BookingRequest request where request.id = :id")
    boolean existsByIdForHold(@Param("id") UUID id);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM booking_record_legal_hold
                WHERE booking_request_id = :id AND status = 'ACTIVE'
            )
            """, nativeQuery = true)
    boolean existsActiveLegalHold(@Param("id") UUID id);

    @Query(value = "SELECT id FROM booking_request WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockForHoldCheck(@Param("id") UUID id);

    default void requireNoActiveLegalHold(UUID id) {
        lockForHoldCheck(id);
        if (existsActiveLegalHold(id)) {
            throw new DomainException(HttpStatus.CONFLICT, "BOOKING_REQUEST_ON_LEGAL_HOLD",
                    "The booking record is protected by an active records hold");
        }
    }

    Page<BookingRequest> findByRequestedByUserId(UUID requestedByUserId, Pageable pageable);

    Page<BookingRequest> findByRequestedByUserIdAndStatus(
            UUID requestedByUserId, BookingRequestStatus status, Pageable pageable);

    Page<BookingRequest> findByOfficeBuildingId(UUID officeBuildingId, Pageable pageable);

    Page<BookingRequest> findByOfficeBuildingIdAndStatus(
            UUID officeBuildingId, BookingRequestStatus status, Pageable pageable);
}
