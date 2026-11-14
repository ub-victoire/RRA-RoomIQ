package rw.rra.roomiq.booking.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.booking.domain.entity.Reservation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from Reservation reservation where reservation.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM reservation r
                JOIN booking_record_legal_hold h ON h.booking_request_id = r.booking_request_id
                WHERE r.id = :reservationId AND h.status = 'ACTIVE'
            )
            """, nativeQuery = true)
    boolean existsActiveLegalHoldForReservation(@Param("reservationId") UUID reservationId);

    @Query(value = """
            SELECT request.id
            FROM booking_request request
            JOIN reservation r ON r.booking_request_id = request.id
            WHERE r.id = :reservationId
            FOR UPDATE OF request
            """, nativeQuery = true)
    Optional<UUID> lockBookingRequestForReservation(@Param("reservationId") UUID reservationId);

    default void requireNoActiveLegalHold(UUID reservationId) {
        lockBookingRequestForReservation(reservationId);
        if (existsActiveLegalHoldForReservation(reservationId)) {
            throw new DomainException(HttpStatus.CONFLICT, "BOOKING_REQUEST_ON_LEGAL_HOLD",
                    "The booking record is protected by an active records hold");
        }
    }

    boolean existsByBookingRequest_Id(UUID bookingRequestId);
    Optional<Reservation> findByBookingRequest_Id(UUID bookingRequestId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM reservation
                WHERE room_id = :roomId
                  AND status NOT IN ('CANCELLED', 'RELEASED')
                  AND occupied_period && tstzrange(:startsAt, :occupiedUntil, '[)')
            )
            """, nativeQuery = true)
    boolean existsRoomOccupancyConflict(@Param("roomId") UUID roomId,
                                        @Param("startsAt") Instant startsAt,
                                        @Param("occupiedUntil") Instant occupiedUntil);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM reservation
                WHERE room_id = :roomId
                  AND id <> :reservationId
                  AND status NOT IN ('CANCELLED', 'RELEASED')
                  AND occupied_period && tstzrange(:startsAt, :occupiedUntil, '[)')
            )
            """, nativeQuery = true)
    boolean existsRoomOccupancyConflictExcludingReservation(@Param("roomId") UUID roomId,
                                                              @Param("reservationId") UUID reservationId,
                                                              @Param("startsAt") Instant startsAt,
                                                              @Param("occupiedUntil") Instant occupiedUntil);
}
