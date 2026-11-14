package rw.rra.roomiq.booking.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.booking.domain.entity.BookingRecordLegalHold;

import java.util.Optional;
import java.util.UUID;

public interface BookingRecordLegalHoldRepository extends JpaRepository<BookingRecordLegalHold, UUID> {
    Optional<BookingRecordLegalHold> findByHoldId(UUID holdId);

    boolean existsByBookingRequestIdAndStatus(
            UUID bookingRequestId, BookingRecordLegalHold.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hold from BookingRecordLegalHold hold where hold.holdId = :holdId")
    Optional<BookingRecordLegalHold> findByHoldIdForUpdate(@Param("holdId") UUID holdId);
}
