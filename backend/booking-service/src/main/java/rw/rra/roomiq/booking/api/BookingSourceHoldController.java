package rw.rra.roomiq.booking.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.booking.config.BookingSourceHoldAuthorizationInterceptor;
import rw.rra.roomiq.booking.domain.service.BookingSourceHoldService;
import rw.rra.roomiq.booking.integration.BookingHoldAcknowledgement;
import rw.rra.roomiq.booking.integration.BookingHoldInstruction;

@RestController
@RequestMapping("/internal/v1/legal-holds/instructions")
public class BookingSourceHoldController {
    private final BookingSourceHoldService service;

    public BookingSourceHoldController(BookingSourceHoldService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BookingHoldAcknowledgement> receive(
            @Valid @RequestBody BookingHoldInstruction instruction, HttpServletRequest request) {
        Object source = request.getAttribute(BookingSourceHoldAuthorizationInterceptor.AUTHENTICATED_SOURCE);
        if (!(source instanceof String authenticatedSource)) {
            throw new IllegalStateException("Trusted RLC service identity is missing");
        }
        return ResponseEntity.ok(service.receive(instruction, authenticatedSource));
    }
}
