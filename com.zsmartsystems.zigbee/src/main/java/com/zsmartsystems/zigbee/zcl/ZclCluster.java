/**
 * Copyright (c) 2016-2017 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.zsmartsystems.zigbee.zcl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zsmartsystems.zigbee.CommandResult;
import com.zsmartsystems.zigbee.IeeeAddress;
import com.zsmartsystems.zigbee.ZigBeeEndpoint;
import com.zsmartsystems.zigbee.ZigBeeEndpointAddress;
import com.zsmartsystems.zigbee.ZigBeeException;
import com.zsmartsystems.zigbee.ZigBeeNetworkManager;
import com.zsmartsystems.zigbee.internal.NotificationService;
import com.zsmartsystems.zigbee.zcl.clusters.general.ConfigureReportingCommand;
import com.zsmartsystems.zigbee.zcl.clusters.general.DefaultResponse;
import com.zsmartsystems.zigbee.zcl.clusters.general.DiscoverAttributesCommand;
import com.zsmartsystems.zigbee.zcl.clusters.general.DiscoverCommandsGenerated;
import com.zsmartsystems.zigbee.zcl.clusters.general.DiscoverCommandsReceived;
import com.zsmartsystems.zigbee.zcl.clusters.general.ReadAttributesCommand;
import com.zsmartsystems.zigbee.zcl.clusters.general.ReadAttributesResponse;
import com.zsmartsystems.zigbee.zcl.clusters.general.ReadReportingConfigurationCommand;
import com.zsmartsystems.zigbee.zcl.clusters.general.WriteAttributesCommand;
import com.zsmartsystems.zigbee.zcl.field.AttributeRecord;
import com.zsmartsystems.zigbee.zcl.field.AttributeReport;
import com.zsmartsystems.zigbee.zcl.field.AttributeReportingConfigurationRecord;
import com.zsmartsystems.zigbee.zcl.field.ReadAttributeStatusRecord;
import com.zsmartsystems.zigbee.zcl.field.WriteAttributeRecord;
import com.zsmartsystems.zigbee.zcl.protocol.ZclCommandDirection;
import com.zsmartsystems.zigbee.zdo.command.BindRequest;
import com.zsmartsystems.zigbee.zdo.command.UnbindRequest;

/**
 * Base class for the ZCL Cluster
 *
 * @author Chris Jackson
 *
 */
public abstract class ZclCluster {
    /**
     * The logger
     */
    private Logger logger = LoggerFactory.getLogger(ZclCluster.class);

    /**
     * The {@link ZigBeeNetworkManager} to which this device belongs
     */
    private final ZigBeeNetworkManager zigbeeManager;
    private final ZigBeeEndpoint zigbeeEndpoint;
    protected final int clusterId;
    protected final String clusterName;

    /**
     * Defines if the remote is a client (true) or server (false)
     * The definition of the direction is based on the remote being the server. If it is really
     * a server, then we need to reverse direction
     */
    private boolean isClient = false;

    private final List<ZclAttributeListener> attributeListeners = new ArrayList<ZclAttributeListener>();

    protected Map<Integer, ZclAttribute> attributes = initializeAttributes();

    protected abstract Map<Integer, ZclAttribute> initializeAttributes();

    public ZclCluster(ZigBeeNetworkManager zigbeeManager, ZigBeeEndpoint zigbeeEndpoint, int clusterId,
            String clusterName) {
        this.zigbeeManager = zigbeeManager;
        this.zigbeeEndpoint = zigbeeEndpoint;
        this.clusterId = clusterId;
        this.clusterName = clusterName;
    }

    protected Future<CommandResult> send(ZclCommand command) {
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());
        if (isClient()) {
            command.setCommandDirection(ZclCommandDirection.SERVER_TO_CLIENT);
        }

        return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    /**
     * Read an attribute
     *
     * @param attribute the {@link ZclAttribute} to read
     * @return command future
     */
    public Future<CommandResult> read(final ZclAttribute attribute) {
        final ReadAttributesCommand command = new ReadAttributesCommand();

        command.setClusterId(clusterId);
        command.setIdentifiers(Collections.singletonList(attribute.getId()));
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());

        return send(command);
        // return zigbeeManager.unicast(command, new ZclCustomResponseMatcher());
    }

    /**
     * Write an attribute
     *
     * @param attribute the {@link ZclAttribute} to write
     * @param value the value to set (as {@link Object})
     * @return command future {@link CommandResult}
     */
    public Future<CommandResult> write(final ZclAttribute attribute, final Object value) {
        final WriteAttributesCommand command = new WriteAttributesCommand();

        command.setClusterId(clusterId);
        final WriteAttributeRecord attributeIdentifier = new WriteAttributeRecord();
        attributeIdentifier.setAttributeIdentifier(attribute.getId());
        attributeIdentifier.setAttributeDataType(attribute.getDataType());
        attributeIdentifier.setAttributeValue(value);
        command.setRecords(Collections.singletonList(attributeIdentifier));
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());

        return send(command);
        // return zigbeeManager.unicast(command, new ZclCustomResponseMatcher());
    }

    /**
     * Read an attribute
     *
     * @param attribute the {@link ZclAttribute} to read
     * @return
     */
    protected Object readSync(final ZclAttribute attribute) {
        logger.debug("readSync request: {}", attribute);
        CommandResult result;
        try {
            result = read(attribute).get();
        } catch (InterruptedException e) {
            e.printStackTrace();
            logger.debug("readSync interrupted", e);
            return null;
        } catch (ExecutionException e) {
            e.printStackTrace();
            logger.debug("readSync exception", e);
            return null;
        }

        if (!result.isSuccess()) {
            return null;
        }

        ReadAttributesResponse response = result.getResponse();
        if (response.getRecords().get(0).getStatus() == 0) {
            return response.getRecords().get(0).getAttributeValue();
        }

        return null;
    }

    /**
     * Configures the reporting for the specified attribute ID for analog attributes.
     * <p>
     * <b>minInterval</b>:
     * The minimum reporting interval field is 16 bits in length and shall contain the
     * minimum interval, in seconds, between issuing reports of the specified attribute.
     * If minInterval is set to 0x0000, then there is no minimum limit, unless one is
     * imposed by the specification of the cluster using this reporting mechanism or by
     * the applicable profile.
     * <p>
     * <b>maxInterval</b>:
     * The maximum reporting interval field is 16 bits in length and shall contain the
     * maximum interval, in seconds, between issuing reports of the specified attribute.
     * If maxInterval is set to 0xffff, then the device shall not issue reports for the specified
     * attribute, and the configuration information for that attribute need not be
     * maintained.
     * <p>
     * <b>reportableChange</b>:
     * The reportable change field shall contain the minimum change to the attribute that
     * will result in a report being issued. This field is of variable length. For attributes
     * with 'analog' data type the field has the same data type as the attribute. The sign (if any) of the reportable
     * change field is ignored.
     *
     * @param attribute the {@link ZclAttribute} to configure reporting
     * @param minInterval the minimum reporting interval
     * @param maxInterval the maximum reporting interval
     * @param reportableChange the minimum change required to report an update
     * @return command future {@link CommandResult}
     */
    public Future<CommandResult> setReporting(final ZclAttribute attribute, final int minInterval,
            final int maxInterval, final Object reportableChange) {

        final ConfigureReportingCommand command = new ConfigureReportingCommand();
        command.setClusterId(clusterId);

        final AttributeReportingConfigurationRecord record = new AttributeReportingConfigurationRecord();
        record.setDirection(0);
        record.setAttributeIdentifier(attribute.getId());
        record.setAttributeDataType(attribute.getDataType());
        record.setMinimumReportingInterval(minInterval);
        record.setMaximumReportingInterval(maxInterval);
        record.setReportableChange(reportableChange);
        record.setTimeoutPeriod(0);
        command.setRecords(Collections.singletonList(record));
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());

        return send(command);
        // return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    /**
     * Configures the reporting for the specified attribute ID for discrete attributes.
     * <p>
     * <b>minInterval</b>:
     * The minimum reporting interval field is 16 bits in length and shall contain the
     * minimum interval, in seconds, between issuing reports of the specified attribute.
     * If minInterval is set to 0x0000, then there is no minimum limit, unless one is
     * imposed by the specification of the cluster using this reporting mechanism or by
     * the applicable profile.
     * <p>
     * <b>maxInterval</b>:
     * The maximum reporting interval field is 16 bits in length and shall contain the
     * maximum interval, in seconds, between issuing reports of the specified attribute.
     * If maxInterval is set to 0xffff, then the device shall not issue reports for the specified
     * attribute, and the configuration information for that attribute need not be
     * maintained.
     *
     * @param attribute the {@link ZclAttribute} to configure reporting
     * @param minInterval the minimum reporting interval
     * @param maxInterval the maximum reporting interval
     * @return command future {@link CommandResult}
     */
    public Future<CommandResult> setReporting(final ZclAttribute attribute, final int minInterval,
            final int maxInterval) {
        return setReporting(attribute, minInterval, maxInterval, null);
    }

    /**
     * Gets the reporting configuration for an attribute
     *
     * @param attribute the {@link ZclAttribute} on which to enable reporting
     * @return command future {@link CommandResult}
     */
    public Future<CommandResult> getReporting(final ZclAttribute attribute) {
        final ReadReportingConfigurationCommand command = new ReadReportingConfigurationCommand();
        command.setClusterId(clusterId);
        AttributeRecord record = new AttributeRecord();
        record.setAttributeIdentifier(attribute.getId());
        record.setDirection(0);
        command.setRecords(Collections.singletonList(record));
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());

        return send(command);
        // return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    /**
     * Gets all the attributes supported by this cluster This will return all
     * attributes, even if they are not actually supported by the device. The
     * user should check to see if this is implemented.
     *
     * @return {@link Set} containing all {@link ZclAttributes} available in this cluster
     */
    public Set<ZclAttribute> getAttributes() {
        Set<ZclAttribute> attr = new HashSet<ZclAttribute>();
        attr.addAll(attributes.values());
        return attr;
    }

    /**
     * Gets an attribute from the attribute ID
     *
     * @param id
     *            the attribute ID
     * @return the {@link ZclAttribute}
     */
    public ZclAttribute getAttribute(int id) {
        return attributes.get(id);
    }

    /**
     * Gets the cluster ID for this cluster
     *
     * @return the cluster ID as {@link Integer}
     */
    public Integer getClusterId() {
        return clusterId;
    }

    /**
     * Gets the cluster name for this cluster
     *
     * @return the cluster name as {@link String}
     */
    public String getClusterName() {
        return clusterName;
    }

    /**
     * Returns the ZigBee address of this cluster
     *
     * @return the {@link ZigBeeEndpointAddress} of the cluster
     */
    public ZigBeeEndpointAddress getZigBeeAddress() {
        return zigbeeEndpoint.getEndpointAddress();
    }

    /**
     * Sets the server flag for this cluster. This means the cluster is listed
     * in the devices input cluster list
     *
     */
    public void setServer() {
        isClient = false;
    }

    /**
     * Gets the state of the server flag. If the cluster is a server this will
     * return true
     *
     * @return true if the cluster can act as a server
     */
    public boolean isServer() {
        return !isClient;
    }

    /**
     * Sets the client flag for this cluster. This means the cluster is listed
     * in the devices output cluster list
     *
     */
    public void setClient() {
        isClient = true;
    }

    /**
     * Gets the state of the client flag. If the cluster is a client this will
     * return true
     *
     * @return true if the cluster can act as a client
     */
    public boolean isClient() {
        return isClient;
    }

    /**
     * Adds a binding from the cluster to the destination {@link ZigBeeEndpoint}.
     *
     * @param address the destination {@link IeeeAddress}
     * @param endpointId the destination endpoint ID
     * @return Command future
     */
    public Future<CommandResult> bind(IeeeAddress address, int endpointId) {
        final BindRequest command = new BindRequest();
        command.setSrcAddress(zigbeeEndpoint.getIeeeAddress());
        command.setSrcEndpoint(zigbeeEndpoint.getEndpointId());
        command.setClusterId(clusterId);
        command.setDstAddrMode(3); // 64 bit addressing
        command.setDstAddress(address);
        command.setDstEndpoint(endpointId);
        return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    /**
     * Adds a binding from the cluster to the local destination..
     *
     * @return Command future
     */
    public Future<CommandResult> bind() {
        return bind(zigbeeManager.getNode(0).getIeeeAddress(), 1);
    }

    /**
     * Removes a binding from the cluster to the destination {@link ZigBeeEndpoint}.
     *
     * @param address the destination {@link IeeeAddress}
     * @param endpointId the destination endpoint ID
     * @return Command future
     */
    public Future<CommandResult> unbind(IeeeAddress address, int endpointId) {
        final UnbindRequest command = new UnbindRequest();
        command.setSrcAddress(zigbeeEndpoint.getIeeeAddress());
        command.setSrcEndpoint(zigbeeEndpoint.getEndpointId());
        command.setClusterId(clusterId);
        command.setDstAddrMode(3); // 64 bit addressing
        command.setDstAddress(address);
        command.setDstEndpoint(endpointId);
        return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    /**
     * Removes a binding from the cluster to the local destination..
     *
     * @return Command future
     */
    public Future<CommandResult> unbind() {
        return unbind(zigbeeManager.getNode(0).getIeeeAddress(), 1);
    }

    /**
     * Sends a default response to the client
     *
     * @param commandIdentifier the command identifier to which this is a response
     * @param status the {@link ZclStatus} to send in the response
     */
    public void sendDefaultResponse(Integer commandIdentifier, ZclStatus status) {
        DefaultResponse defaultResponse = new DefaultResponse();
        defaultResponse.setCommandIdentifier(commandIdentifier);
        defaultResponse.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());
        defaultResponse.setClusterId(clusterId);
        defaultResponse.setStatusCode(status);

        try {
            zigbeeManager.sendCommand(defaultResponse);
        } catch (ZigBeeException e) {
            logger.debug("Exception sending default response message: ", e);
        }
    }

    /**
     * Discovers the list of attributes support by the cluster
     *
     * @return Command future
     */
    public Future<CommandResult> discoverAttributes() {
        final DiscoverAttributesCommand command = new DiscoverAttributesCommand();
        command.setClusterId(clusterId);
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());
        // TODO: Handle multiple requests
        command.setStartAttributeIdentifier(0);
        command.setMaximumAttributeIdentifiers(40);
        return send(command);
        // return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    /**
     * Discovers the list of commands received by the cluster
     *
     * @return Command future
     */
    public Future<CommandResult> discoverCommandsReceived() {
        final DiscoverCommandsReceived command = new DiscoverCommandsReceived();
        command.setClusterId(clusterId);
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());
        // TODO: Handle multiple requests
        command.setStartCommandIdentifier(0);
        command.setMaximumCommandIdentifiers(40);
        return send(command);
        // return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    /**
     * Discovers the list of commands generated by the cluster
     *
     * @return Command future
     */
    public Future<CommandResult> discoverCommandsGenerated() {
        final DiscoverCommandsGenerated command = new DiscoverCommandsGenerated();
        command.setClusterId(clusterId);
        command.setDestinationAddress(zigbeeEndpoint.getEndpointAddress());
        // TODO: Handle multiple requests
        command.setStartCommandIdentifier(0);
        command.setMaximumCommandIdentifiers(40);
        return send(command);
        // return zigbeeManager.unicast(command, new ZclResponseMatcher());
    }

    public void addAttributeListener(ZclAttributeListener listener) {
        // Don't add more than once.
        if (attributeListeners.contains(listener)) {
            return;
        }
        attributeListeners.add(listener);
    }

    /**
     * Remove an attribute listener from the cluster.
     *
     * @param listener callback listener implementing {@link ZclAttributeListener} to remove
     */
    public void removeAttributeListener(final ZclAttributeListener listener) {
        attributeListeners.remove(listener);
    }

    /**
     * Notify attribute listeners of an updated {@link ZclAttribute}
     *
     * @param attribute the {@link ZclAttribute} to notify
     */
    private void notifyAttributeListener(final ZclAttribute attribute) {
        for (final ZclAttributeListener listener : attributeListeners) {
            NotificationService.execute(new Runnable() {
                @Override
                public void run() {
                    listener.attributeUpdated(attribute);
                }
            });
        }
    }

    /**
     * Processes a list of attribute reports for this cluster
     *
     * @param reports
     *            {@List} of {@link AttributeReport}
     */
    public void handleAttributeReport(List<AttributeReport> reports) {
        for (AttributeReport report : reports) {
            ZclAttribute attribute = attributes.get(report.getAttributeIdentifier());
            if (attribute == null) {
                return;
            }
            attribute.updateValue(report.getAttributeValue());
            notifyAttributeListener(attribute);
        }
    }

    /**
     * Processes a list of attribute status reports for this cluster
     *
     * @param reports
     *            {@List} of {@link ReadAttributeStatusRecord}
     */
    public void handleAttributeStatus(List<ReadAttributeStatusRecord> records) {
        for (ReadAttributeStatusRecord record : records) {
            ZclAttribute attribute = attributes.get(record.getAttributeIdentifier());
            attribute.updateValue(record.getAttributeValue());
            notifyAttributeListener(attribute);
        }
    }

    /**
     * Gets a command from the command ID (ie a command from client to server). If no command with the requested id is
     * found, null is returned.
     *
     * @param commandId the command ID
     * @return the {@link ZclCommand} or null if no command found.
     */
    public ZclCommand getCommandFromId(int commandId) {
        return null;
    }

    /**
     * Gets a response from the command ID (ie a command from server to client). If no command with the requested id is
     * found, null is returned.
     *
     * @param commandId the command ID
     * @return the {@link ZclCommand} or null if no command found.
     */
    public ZclCommand getResponseFromId(int commandId) {
        return null;
    }
}
