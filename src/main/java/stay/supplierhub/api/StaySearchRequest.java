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
import java.time.LocalDate;
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

    @Override
    public boolean isValid(StaySearchRequest request, ConstraintValidatorContext context) {
        if (request == null || request.checkIn() == null || request.checkOut() == null) {
            return true;
        }
        if (request.checkOut().isAfter(request.checkIn())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("checkOut must be after checkIn")
                .addConstraintViolation();
        return false;
    }
}
