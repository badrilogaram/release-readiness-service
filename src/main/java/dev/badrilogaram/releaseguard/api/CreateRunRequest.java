package dev.badrilogaram.releaseguard.api;

import jakarta.validation.constraints.*;
import java.util.List;

public record CreateRunRequest(@NotBlank @Size(max = 80) String release,
        @NotEmpty @Size(max = 12) List<@NotBlank String> componentIds,
        @Min(1) @Max(3600) int windowSeconds, @Min(1) @Max(300) int intervalSeconds) {
}
