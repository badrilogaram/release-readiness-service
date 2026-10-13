package dev.badrilogaram.releaseguard.notification;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "releaseguard.alerts")
public record AlertsProperties(@DefaultValue("false") boolean enabled, String from, List<String> to) {
    public AlertsProperties {
        from = from == null ? "" : from.trim();
        to = to == null ? List.of() : List.copyOf(to);
        if (enabled) {
            validateAddress(from, "from");
            if (to.isEmpty()) {
                throw new IllegalArgumentException("releaseguard.alerts.to must contain a recipient when alerts are enabled");
            }
            to.forEach(address -> validateAddress(address, "to"));
        }
    }

    private static void validateAddress(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("releaseguard.alerts." + field + " must contain a valid email address");
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(value, true);
            if (parsed.length != 1) {
                throw new AddressException("Expected one address");
            }
            parsed[0].validate();
        } catch (AddressException exception) {
            throw new IllegalArgumentException("releaseguard.alerts." + field + " must contain a valid email address");
        }
    }
}
