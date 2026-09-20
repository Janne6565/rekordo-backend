package com.rekordo.controller.v1.schema;

import com.rekordo.model.action.RegisterDeviceRequest;
import com.rekordo.model.action.UpdateNotificationPreferenceRequest;
import com.rekordo.model.core.NotificationDeviceDto;
import com.rekordo.model.core.NotificationPreferencesDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

@Tag(name = "Notifications", description = "What may reach you outside the app")
@RequestMapping("/api/v1/notifications")
public interface NotificationsApi {

    @GetMapping("/preferences")
    @Operation(
            summary = "What may reach you, and on which channel",
            description = "Follows the account rather than the device it was set on, unlike everything "
                    + "under Settings. Every category is always present: the answer is the whole grid, "
                    + "defaults included, so no client has to know what a default is.")
    @ApiResponse(responseCode = "200", description = "The grid")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<NotificationPreferencesDto> preferences();

    @PatchMapping("/preferences")
    @Operation(
            summary = "Flip one row of the grid",
            description = "The screen saves as you go, so a request is one category rather than the "
                    + "whole grid -- and the answer is the whole grid, so nothing has to be re-derived. "
                    + "The mail switch on a locked category is ignored: a notice you can silence is not "
                    + "a notice, and that is not enforced in a client.")
    @ApiResponse(responseCode = "200", description = "The grid, as it now reads")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<NotificationPreferencesDto> updatePreference(
            @Valid @RequestBody UpdateNotificationPreferenceRequest request);

    @GetMapping("/devices")
    @Operation(
            summary = "Where a push could arrive",
            description = "The second, shorter question on 22a: the grid says what may reach you, this "
                    + "says which devices may buzz. A phone in a drawer and a phone in a pocket "
                    + "disagree, which is why one mute lives per device and the categories do not.")
    @ApiResponse(responseCode = "200", description = "The devices, oldest first")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<List<NotificationDeviceDto>> devices(
            @RequestHeader(name = "X-Device-Id", required = false) String currentDeviceId);

    @PostMapping("/devices")
    @Operation(
            summary = "Register where this device can be reached",
            description = "Keyed on the client's own device id rather than on the token: a token is "
                    + "reissued on reinstall, so keying on it would grow a row per phone per reinstall "
                    + "and buzz the same person twice. A device that re-registers keeps its mute.")
    @ApiResponse(responseCode = "200", description = "The devices, as they now read")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<List<NotificationDeviceDto>> registerDevice(@Valid @RequestBody RegisterDeviceRequest request);

    @PatchMapping("/devices/{id}/mute")
    @Operation(summary = "Mute or unmute one device")
    @ApiResponse(responseCode = "200", description = "The devices, as they now read")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<List<NotificationDeviceDto>> muteDevice(
            @PathVariable UUID id,
            @RequestBody MuteDeviceRequest request,
            @RequestHeader(name = "X-Device-Id", required = false) String currentDeviceId);

    /** Inline because it is one boolean and will never be anything else. */
    record MuteDeviceRequest(boolean muted) {}
}
