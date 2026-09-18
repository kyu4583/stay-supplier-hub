package stay.supplierhub.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;

@StaySearchPolicy
public record StaySearchRequest(
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull Integer adults,
        @NotNull Integer children) {}

@ConfigurationProperties(prefix = "stay.search")
record SearchPolicyProperties(int maxNights, int maxCheckInDaysAhead, int maxGuests) {}

@Documented
@Constraint(validatedBy = StaySearchPolicyValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface StaySearchPolicy {
    String message() default "invalid stay search request";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

class StaySearchPolicyValidator implements ConstraintValidator<StaySearchPolicy, StaySearchRequest> {

    private final Clock clock;
    private final SearchPolicyProperties policy;

    StaySearchPolicyValidator(Clock clock, SearchPolicyProperties policy) {
        this.clock = clock;
        this.policy = policy;
    }

    @Override
    public boolean isValid(StaySearchRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        LocalDate today = LocalDate.now(clock);
        LocalDate checkIn = request.checkIn();
        LocalDate checkOut = request.checkOut();
        Integer adults = request.adults();
        Integer children = request.children();

        if (checkIn != null && checkOut != null) {
            long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
            if (nights < 1) {
                context.buildConstraintViolationWithTemplate("checkOut must be after checkIn")
                        .addConstraintViolation();
                valid = false;
            } else if (nights > policy.maxNights()) {
                context.buildConstraintViolationWithTemplate("stay must not exceed max nights")
                        .addConstraintViolation();
                valid = false;
            }
            if (checkIn.isBefore(today)) {
                context.buildConstraintViolationWithTemplate("checkIn must not be before today")
                        .addConstraintViolation();
                valid = false;
            }
            if (ChronoUnit.DAYS.between(today, checkIn) > policy.maxCheckInDaysAhead()) {
                context.buildConstraintViolationWithTemplate("checkIn must be within max days ahead")
                        .addConstraintViolation();
                valid = false;
            }
        }
        if (adults != null && adults < 1) {
            context.buildConstraintViolationWithTemplate("adults must be at least 1")
                    .addConstraintViolation();
            valid = false;
        }
        if (children != null && children < 0) {
            context.buildConstraintViolationWithTemplate("children must be at least 0")
                    .addConstraintViolation();
            valid = false;
        }
        if (adults != null && children != null && adults + children > policy.maxGuests()) {
            context.buildConstraintViolationWithTemplate("guest count must not exceed max guests")
                    .addConstraintViolation();
            valid = false;
        }
        return valid;
    }
}
