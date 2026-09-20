package com.rekordo.controller.v1.implementation;

import com.rekordo.controller.v1.schema.NotificationsApi;
import com.rekordo.model.action.RegisterDeviceRequest;
import com.rekordo.model.action.UpdateNotificationPreferenceRequest;
import com.rekordo.model.core.NotificationDeviceDto;
import com.rekordo.model.core.NotificationPreferencesDto;
import com.rekordo.security.CurrentUser;
import com.rekordo.services.notifications.NotificationDeviceService;
import com.rekordo.services.notifications.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class NotificationsController implements NotificationsApi {

    private final NotificationPreferenceService preferenceService;
    private final NotificationDeviceService deviceService;
    private final CurrentUser currentUser;

    @Override
    public ResponseEntity<NotificationPreferencesDto> preferences() {
        return ResponseEntity.ok(preferenceService.forUser(currentUser.require()));
    }

    @Override
    public ResponseEntity<NotificationPreferencesDto> updatePreference(UpdateNotificationPreferenceRequest request) {
        return ResponseEntity.ok(preferenceService.update(
                currentUser.require(), request.category(), request.mail(), request.push()));
    }

    @Override
    public ResponseEntity<List<NotificationDeviceDto>> devices(String currentDeviceId) {
        return ResponseEntity.ok(deviceService.list(currentUser.require(), currentDeviceId));
    }

    @Override
    public ResponseEntity<List<NotificationDeviceDto>> registerDevice(RegisterDeviceRequest request) {
        var user = currentUser.require();
        deviceService.register(user, request.deviceId(), request.pushToken(), request.platform(), request.label());
        return ResponseEntity.ok(deviceService.list(user, request.deviceId()));
    }

    @Override
    public ResponseEntity<List<NotificationDeviceDto>> muteDevice(
            UUID id, MuteDeviceRequest request, String currentDeviceId) {
        var user = currentUser.require();
        deviceService.setMuted(user, id, request.muted());
        return ResponseEntity.ok(deviceService.list(user, currentDeviceId));
    }
}
