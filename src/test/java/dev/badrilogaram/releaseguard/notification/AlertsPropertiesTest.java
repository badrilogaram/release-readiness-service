package dev.badrilogaram.releaseguard.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;

class AlertsPropertiesTest {
    @Test
    void absentConfigurationDefaultsToDisabledWithoutAddressRequirements() {
        Binder binder = new Binder(List.<ConfigurationPropertySource>of());
        AlertsProperties properties = binder.bindOrCreate("releaseguard.alerts", Bindable.of(AlertsProperties.class));
        assertFalse(properties.enabled());
        assertEquals("", properties.from());
        assertEquals(List.of(), properties.to());
    }

    @Test
    void enabledAlertsRequireAValidSenderAndRecipients() {
        assertThrows(IllegalArgumentException.class, () -> new AlertsProperties(true, null, List.of("team@example.org")));
        assertThrows(IllegalArgumentException.class, () -> new AlertsProperties(true, "sender@example.org", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AlertsProperties(true, "sender@example.org", List.of("invalid")));
        assertThrows(IllegalArgumentException.class, () -> new AlertsProperties(true, "invalid", List.of("team@example.org")));
        assertThrows(IllegalArgumentException.class, () -> new AlertsProperties(true, "sender@example.org",
                List.of("team@example.org\r\nBcc: other@example.org")));
    }
}
