/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.McDeviceBrandingDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.persistence.domain.DeviceSyncRow;
import com.hmdm.persistence.domain.McDeviceBranding;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.rest.json.AgentBulkCommandRequest;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.CommandHistoryView;
import com.hmdm.rest.json.agent.ConfigStatusView;
import com.hmdm.rest.json.agent.ConfigSyncSummary;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.DesiredConfigBuilder;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <p>Authenticated admin resource for the from-scratch Android agent v1 protocol: mints enrollment
 * tokens and queues opaque commands. As with {@link AgentResource}, command {@code type}/{@code
 * payload} are never interpreted by the server.</p>
 */
@Singleton
@Path("/private/agent/v1")
@Api(tags = {"Agent v1 admin"})
public class AgentAdminResource {

    private static final Logger logger = LoggerFactory.getLogger(AgentAdminResource.class);

    /** Default lifetime of a freshly-minted enrollment token (24h). */
    private static final long DEFAULT_TOKEN_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    private AgentEnrollmentTokenDAO tokenDAO;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private AgentWakeHub wakeHub;
    private com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller;
    private ConfigReconciler configReconciler;
    private McDeviceBrandingDAO brandingDAO;

    /** MeinConnect fork: limits of the per-device branding override (match the configuration columns). */
    private static final int MAX_BRAND_TITLE = 100;
    private static final int MAX_BRAND_LOGO_URL = 500;
    private static final int MAX_TOKEN_DESCRIPTION = 200;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public AgentAdminResource() {
    }

    @Inject
    public AgentAdminResource(AgentEnrollmentTokenDAO tokenDAO,
                              AgentCommandDAO commandDAO,
                              UnsecureDAO unsecureDAO,
                              AgentWakeHub wakeHub,
                              com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller,
                              ConfigReconciler configReconciler,
                              McDeviceBrandingDAO brandingDAO) {
        this.brandingDAO = brandingDAO;
        this.tokenDAO = tokenDAO;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.wakeHub = wakeHub;
        this.configAppInstaller = configAppInstaller;
        this.configReconciler = configReconciler;
    }

    /**
     * <p>Every mutation here acts on devices (commands incl. wipe/passcode reset, wake-ups, enrollment
     * tokens), so it needs {@code edit_devices}, exactly like {@link DeviceResource}. Reads stay open to
     * any user of the customer.</p>
     */
    private static boolean canEditDevices(String action) {
        if (SecurityContext.get().hasPermission("edit_devices")) {
            return true;
        }
        logger.warn("Permission denied: {} requires edit_devices (user {})", action,
                SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
        return false;
    }

    // =================================================================================================================
    @ApiOperation(value = "Mint enrollment token", notes = "Creates a single-use enrollment token for the current customer. "
            + "An optional configurationId binds the enrolled device to that configuration.")
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mintToken(AgentEnrollmentToken body) {
        if (!canEditDevices("mint enrollment token")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        // Optional config binding: the enrolled device lands in this configuration instead of the
        // customer's settings default. Validate ownership — a token must not be able to place a
        // device into another customer's configuration.
        Integer configurationId = body == null ? null : body.getConfigurationId();
        if (configurationId != null) {
            com.hmdm.persistence.domain.Configuration cfg = unsecureDAO.getConfigurationById(configurationId);
            if (cfg == null || cfg.getCustomerId() != customerId.get()) {
                return Response.ERROR("error.configuration.not.found");
            }
        }

        long now = System.currentTimeMillis();
        AgentEnrollmentToken token = new AgentEnrollmentToken();
        token.setToken(UUID.randomUUID().toString());
        token.setCustomerId(customerId.get());
        token.setConfigurationId(configurationId);
        // MeinConnect fork: optional name for the enrolled device (applied at enrollment).
        String description = body == null ? null : body.getMcDescription();
        if (description != null) {
            description = description.trim();
            if (description.isEmpty()) {
                description = null;
            } else if (description.length() > MAX_TOKEN_DESCRIPTION) {
                description = description.substring(0, MAX_TOKEN_DESCRIPTION);
            }
        }
        token.setMcDescription(description);
        token.setUsed(false);
        token.setCreatedAt(now);
        token.setExpiresAt(now + DEFAULT_TOKEN_TTL_MILLIS);
        tokenDAO.insert(token);

        logger.info("Agent enrollment token {} minted for customer {} (configuration {})",
                token.getId(), customerId.get(), configurationId);
        return Response.OK(token);
    }

    // =================================================================================================================
    @ApiOperation(value = "Sync configuration apps", notes = "Queues app.install commands for the device's "
            + "configuration apps marked action=install. Use after enrolling an older device or editing a configuration.")
    @POST
    @Path("/devices/{deviceId}/syncApps")
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncConfigApps(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("sync configuration apps")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        int queued = configAppInstaller.enqueueConfigApps(device);
        logger.info("Sync apps for device {}: {} app.install queued", deviceId, queued);
        return Response.OK(java.util.Collections.singletonMap("queued", queued));
    }

    // =================================================================================================================
    @ApiOperation(value = "Queue agent command", notes = "Queues an opaque command for a device.")
    @POST
    @Path("/devices/{deviceId}/commands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response queueCommand(@PathParam("deviceId") String deviceId, AgentCommand body) {
        if (!canEditDevices("queue agent command")) {
            return Response.PERMISSION_DENIED();
        }
        if (body == null || body.getType() == null || body.getType().trim().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }

        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }

        AgentCommand command = new AgentCommand();
        command.setDeviceNumber(deviceId);
        command.setType(body.getType());
        command.setPayload(body.getPayload());
        command.setRequiresCapability(body.getRequiresCapability());
        command.setStatus("pending");
        command.setCreatedAt(System.currentTimeMillis());
        commandDAO.insert(command);

        // Wake the device so it pulls the command immediately (best-effort; floor reconcile backs it up).
        wakeHub.wake(deviceId, "commands");

        logger.info("Agent command {} queued for device {}", command.getId(), deviceId);
        // Payload-free view (same shape as the history): never echo a payload back to the console.
        return Response.OK(CommandHistoryView.from(command));
    }

    /** Command types that must never be issued in bulk (destructive group). Lowercased — matched
     *  case-insensitively so a mixed-case type can't slip past this destructive-action guard. */
    private static final Set<String> BULK_FORBIDDEN_TYPES =
            Set.of("device.wipe", "device.passcodereset");

    // =================================================================================================================
    @ApiOperation(value = "Queue agent command for many devices",
            notes = "Fans one opaque command out to a list of device ids (destructive types rejected).")
    @POST
    @Path("/bulk/commands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response queueCommandBulk(AgentBulkCommandRequest req) {
        if (!canEditDevices("queue bulk agent command")) {
            return Response.PERMISSION_DENIED();
        }
        if (req == null || req.getCommand() == null
                || req.getCommand().getType() == null || req.getCommand().getType().trim().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }
        if (req.getDeviceIds() == null || req.getDeviceIds().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }
        final String type = req.getCommand().getType().trim();
        if (BULK_FORBIDDEN_TYPES.contains(type.toLowerCase(java.util.Locale.ROOT))) {
            return Response.ERROR("error.agent.command.bulkForbidden");
        }

        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        int queued = 0;
        List<Integer> skipped = new ArrayList<>();
        for (Integer id : req.getDeviceIds()) {
            if (id == null) { continue; }
            Device device = unsecureDAO.getDeviceById(id);
            if (device == null || device.getCustomerId() != customerId.get()) {
                skipped.add(id);
                continue;
            }
            AgentCommand command = new AgentCommand();
            command.setDeviceNumber(device.getNumber());
            command.setType(type);
            command.setPayload(req.getCommand().getPayload());
            command.setRequiresCapability(req.getCommand().getRequiresCapability());
            command.setStatus("pending");
            command.setCreatedAt(System.currentTimeMillis());
            commandDAO.insert(command);
            wakeHub.wake(device.getNumber(), "commands");
            queued++;
        }

        logger.info("Bulk command {} queued for {} device(s), {} skipped", type, queued, skipped.size());
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("queued", queued);
        result.put("skipped", skipped);
        return Response.OK(result);
    }

    // =================================================================================================================
    @ApiOperation(value = "Device state", notes = "Latest agent-reported device-state snapshot.")
    @GET
    @Path("/devices/{deviceId}/state")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getState(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(commandDAO.getState(deviceId));
    }

    // =================================================================================================================
    @ApiOperation(value = "Device configuration status", notes = "Desired-state revision vs the revision the agent last applied.")
    @GET
    @Path("/devices/{deviceId}/configStatus")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigStatus(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();

        ConfigStatusView v = new ConfigStatusView();
        v.setConfigurationId(device.getConfigurationId());
        v.setCurrentRevision(configReconciler.currentRevision(device));
        DeviceState state = commandDAO.getState(deviceId);
        if (state != null) { v.setAppliedRevision(state.getAppliedConfigRevision()); v.setAppliedAt(state.getAppliedConfigAt()); }
        Set<String> tokens = AgentCapabilityTokens.flatten(commandDAO.getDeviceCapabilities(deviceId));
        boolean supported = AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, tokens);
        v.setSupported(supported);
        v.setInSync(v.getCurrentRevision() != null && v.getCurrentRevision().equals(v.getAppliedRevision()));
        v.setLastCommand(ConfigStatusView.LastCommand.from(commandDAO.findLatestOfType(deviceId, DesiredConfigBuilder.COMMAND_TYPE)));
        return Response.OK(v);
    }

    // =================================================================================================================
    @ApiOperation(value = "Configuration sync summary", notes = "Per configuration: how many devices applied its current revision.")
    @GET
    @Path("/configurations/syncSummary")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSyncSummary() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Map<String, String> revisionByKey = new HashMap<>();
        Map<Integer, ConfigSyncSummary> out = new LinkedHashMap<>();
        // MeinConnect fork: devices with their own branding have their own revision.
        Map<Integer, McDeviceBranding> brandings = brandingDAO.byCustomer(customerId.get());
        for (DeviceSyncRow row : commandDAO.listDevicesForSync(customerId.get())) {
            Integer cfgId = row.getConfigurationId();
            ConfigSyncSummary s = out.computeIfAbsent(cfgId, id -> { ConfigSyncSummary x = new ConfigSyncSummary(); x.setConfigurationId(id); return x; });
            s.setTotal(s.getTotal() + 1);
            McDeviceBranding branding = row.getDeviceId() == null ? null : brandings.get(row.getDeviceId());
            String key = cfgId + "|" + (branding == null ? "" : branding.cacheKey());
            String current = revisionByKey.computeIfAbsent(key, k -> {
                Device probe = new Device(); probe.setConfigurationId(cfgId); probe.setCustomerId(customerId.get());
                com.hmdm.rest.json.agent.DesiredConfig doc = configReconciler.documentFor(probe, branding);
                return doc == null ? null : doc.getRevision();
            });
            Set<String> tokens = AgentCapabilityTokens.flatten(row.getCapabilitiesJson());
            boolean supported = AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, tokens);
            if (!supported) {
                s.setUnsupported(s.getUnsupported() + 1);
            } else if (row.getAppliedConfigRevision() == null) {
                s.setNeverSeen(s.getNeverSeen() + 1);
            } else if (row.getAppliedConfigRevision().equals(current)) {
                s.setInSync(s.getInSync() + 1);
            } else {
                s.setOutOfSync(s.getOutOfSync() + 1);
            }
        }
        return Response.OK(new ArrayList<>(out.values()));
    }

    // =================================================================================================================
    @ApiOperation(value = "Device telemetry", notes = "Latest full census snapshot (JSON) reported by the agent.")
    @GET
    @Path("/devices/{deviceId}/telemetry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTelemetry(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        com.hmdm.persistence.domain.DeviceState s = commandDAO.getState(deviceId);
        String json = s == null ? null : s.getTelemetry();
        try {
            return Response.OK(json == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            return Response.OK(null);
        }
    }

    // =================================================================================================================
    @ApiOperation(value = "Device events", notes = "Agent lifecycle event timeline, newest first.")
    @GET
    @Path("/devices/{deviceId}/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listEvents(@PathParam("deviceId") String deviceId,
                               @QueryParam("since") Long since,
                               @QueryParam("limit") Integer limit) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        int cap = limit == null ? 200 : Math.min(limit, 500);
        return Response.OK(commandDAO.listEvents(deviceId, sinceMillis, cap));
    }

    // =================================================================================================================
    @ApiOperation(value = "Command history", notes = "Command lifecycle history for a device, newest first. "
            + "Payloads are never returned (they can embed secrets); app commands carry the package as 'subject'.")
    @GET
    @Path("/devices/{deviceId}/commands")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCommands(@PathParam("deviceId") String deviceId,
                                 @QueryParam("since") Long since) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        return Response.OK(CommandHistoryView.fromAll(commandDAO.listHistory(deviceId, sinceMillis, 200)));
    }

    // =================================================================================================================
    @ApiOperation(value = "Location history", notes = "Recent location breadcrumb trail for a device, newest first.")
    @GET
    @Path("/devices/{deviceId}/locations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listLocations(@PathParam("deviceId") String deviceId,
                                  @QueryParam("since") Long since) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        return Response.OK(commandDAO.listLocations(deviceId, sinceMillis, 500));
    }

    // =================================================================================================================
    @ApiOperation(value = "Force sync", notes = "Wake the device now so it pulls pending commands + reports state.")
    @POST
    @Path("/devices/{deviceId}/sync")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forceSync(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("force sync")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        wakeHub.wake(deviceId, "commands");
        return Response.OK();
    }

    // =================================================================================================================
    // MeinConnect fork: per-device kiosk branding + enrollment-token status
    // =================================================================================================================

    @ApiOperation(value = "Device kiosk branding", notes = "MeinConnect fork: the device's title/logo override (null = configuration branding).")
    @GET
    @Path("/devices/{deviceId}/branding")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBranding(@PathParam("deviceId") String deviceId) {
        Device device = ownDevice(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        return Response.OK(brandingDAO.find(device.getId()));
    }

    @ApiOperation(value = "Set device kiosk branding", notes = "MeinConnect fork: overlays title and/or logo URL "
            + "(https only) on the configuration's kiosk theme for this device; both blank removes the override. "
            + "The device is woken and picks up the new desired state on its next check-in.")
    @PUT
    @Path("/devices/{deviceId}/branding")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setBranding(@PathParam("deviceId") String deviceId, McDeviceBranding body) {
        if (!canEditDevices("set device branding")) {
            return Response.PERMISSION_DENIED();
        }
        Device device = ownDevice(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");

        String title = body == null || body.getTitle() == null ? null : body.getTitle().trim();
        String logoUrl = body == null || body.getLogoUrl() == null ? null : body.getLogoUrl().trim();
        if (title != null && title.length() > MAX_BRAND_TITLE) {
            return Response.ERROR("error.agent.branding.invalid");
        }
        if (logoUrl != null && !logoUrl.isEmpty()
                && (logoUrl.length() > MAX_BRAND_LOGO_URL || !logoUrl.toLowerCase(java.util.Locale.ROOT).startsWith("https://"))) {
            // The agent only loads https images; reject early instead of silently showing nothing.
            return Response.ERROR("error.agent.branding.invalid");
        }

        brandingDAO.save(device.getId(), title, logoUrl);
        wakeHub.wake(deviceId, "commands");
        logger.info("Kiosk branding of device {} set (title {}, logo {})", deviceId,
                title == null || title.isEmpty() ? "-" : "set", logoUrl == null || logoUrl.isEmpty() ? "-" : "set");
        return Response.OK(brandingDAO.find(device.getId()));
    }

    @ApiOperation(value = "Enrollment token status", notes = "MeinConnect fork: whether the token was used and by which device.")
    @GET
    @Path("/token/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getToken(@PathParam("id") Integer id) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        AgentEnrollmentToken token = tokenDAO.findById(id);
        if (token == null || token.getCustomerId() != customerId.get()) {
            return Response.ERROR("error.agent.token.invalid");
        }
        return Response.OK(token);
    }

    /** The device when it exists and belongs to the current customer, else null. */
    private Device ownDevice(String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return null;
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null || device.getCustomerId() != customerId.get()) return null;
        return device;
    }
}
