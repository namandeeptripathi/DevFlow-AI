package com.devflow.user.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

/**
 * Data Transfer Object representing user notification channel preferences.
 *
 * <p>Supports standard notification channels (email, push, mention) as explicit fields,
 * while allowing extensible dynamic key-value notification preferences via {@link JsonAnyGetter}
 * and {@link JsonAnySetter}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationPreferencesDto {

    /** Whether email notifications are enabled for the user. */
    @Builder.Default
    private Boolean emailNotifications = true;

    /** Whether push notifications are enabled for the user. */
    @Builder.Default
    private Boolean pushNotifications = true;

    /** Whether mention notifications are enabled for the user. */
    @Builder.Default
    private Boolean mentionNotifications = true;

    /** Container for custom or dynamic notification channel preferences. */
    @Builder.Default
    private Map<String, Object> additionalChannels = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getAdditionalChannels() {
        return additionalChannels;
    }

    @JsonAnySetter
    public void setAdditionalChannel(String name, Object value) {
        if (additionalChannels == null) {
            additionalChannels = new HashMap<>();
        }
        additionalChannels.put(name, value);
    }
}
