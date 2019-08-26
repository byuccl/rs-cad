/*
 * Copyright (c) 2019 Brigham Young University
 *
 * This file is part of the BYU RapidSmith Tools.
 *
 * BYU RapidSmith Tools is free software: you may redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * BYU RapidSmith Tools is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * A copy of the GNU General Public License is included with the BYU
 * RapidSmith Tools. It can be found at doc/LICENSE.GPL3.TXT. You may
 * also get a copy of the license at <http://www.gnu.org/licenses/>.
 */

package edu.byu.ece.rapidSmith.cad.route;

import edu.byu.ece.rapidSmith.device.*;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A global wire. This wire is not a real physical wire in a device, but can instead be used to represent the
 * common global VCC and GND sources in a device. Global wires are not included in device files and must be created
 * by the user to be used.
 */
public class GlobalWire implements Wire, Serializable {

    private Device device;
    private boolean isVcc;
    private Set<Connection> connections;

    public GlobalWire(Device device, boolean isVcc) {
        this.device = device;
        this.isVcc = isVcc;
    }

    /**
     * Set the forward connections for the global wire.
     * @param connections the forward connections.
     */
    public void setConnections(Set<Connection> connections) {
        this.connections = connections;
    }

    /**
     * Adds the specified forward connection to the global wire.
     * @param connection the forward connection
     */
    public void addConnection(Connection connection) {
        connections.add(connection);
    }

    @Override
    public int getWireEnum() {
        return -1;
    }

    @Override
    public String getName() {
        return (isVcc) ? "GlobalVccWire" : "GlobalGndWire";
    }

    @Override
    public String getFullName() {
        return device.getPartName() + "/" + getName();
    }

    @Override
    public Tile getTile() {
        return null;
    }

    @Override
    public Site getSite() {
        return null;
    }

    /** @deprecated Use {@link #getName} instead.
     */
    @Override
    public String getWireName() {
        return getName();
    }

    /**
     * @deprecated Use {@link #getFullName} instead.
     */
    @Override
    public String getFullWireName() {
        return getFullName();
    }

    /**
     * Return connection linking this wire to other wires in the same hierarchy.
     */
    @Override
    public Collection<Connection> getWireConnections() {
        return connections;
    }

    // TODO
    @Override
    public WireConnection[] getWireConnectionsArray() {
        return new WireConnection[0];
    }

    // TODO

    /**
     * Returns the connection to the given sink wire, if it exists.
     * @param sinkWire the sink wire to get a connection to
     * @return the wire connection to sinkWire
     */
    @Override
    public Connection getWireConnection(Wire sinkWire) {
        if (connections == null)
            return null;

        return connections.stream()
                .filter(conn -> conn.getSinkWire() == sinkWire)
                .iterator().next();
    }

    /**
     * Returns the connected site pins for each possible type of the connected site.
     * @return all connected sites pins of this wire
     */
    @Override
    public Collection<SitePin> getAllConnectedPins() {
        return null;
    }

    /**
     * Returns connection linking this wire to another wire in a different
     * hierarchical level through a pin.
     */
    @Override
    public SitePin getConnectedPin() {
        return null;
    }

    /**
     * Returns connection linking this wire to another wire in a different
     * hierarchical level through a pin.
     */
    @Override
    public BelPin getTerminal() {
        return null;
    }

    /**
     * Returns connection linking this wire to its drivers in the same hierarchy.
     */
    @Override
    public Collection<Connection> getReverseWireConnections() {
        return null;
    }

    @Override
    public WireConnection[] getReverseWireConnectionsArray() {
        return new WireConnection[0];
    }

    /**
     * Returns the connected site pins for each possible type of the connected site.
     *
     * @return all connected sites pins of this wire
     */
    @Override
    public Collection<SitePin> getAllReverseSitePins() {
        return null;
    }

    /**
     * Return connection linking this wire to its drivers in the different
     * levels of hierarchy.
     */
    @Override
    public SitePin getReverseConnectedPin() {
        return null;
    }

    /**
     * Returns the sources (BelPins) which drive this wire.
     */
    @Override
    public BelPin getSource() {
        return null;
    }

    /**
     * Returns all beginning and end wires that make up a node. Does not include intermediate wires.
     * @return beginning and end wires of a node.
     */
    @Override
    public Set<Wire> getWiresInNode() {
        Set<Wire> wires = new HashSet<>();
        wires.add(this);
        return wires;
    }
}
