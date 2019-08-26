package edu.byu.ece.rapidSmith.cad.route;

import edu.byu.ece.rapidSmith.device.*;

/**
 * A forward connection from a global wire to another wire.
 */
public class GlobalWireConnection extends Connection {
    /** The global source wire **/
    private Wire sourceWire;
    /** The sink tile wire  **/
    private Wire sinkWire;

    public GlobalWireConnection(Wire sourceWire, Wire sinkWire) {
        this.sourceWire = sourceWire;
        this.sinkWire = sinkWire;
    }

    @Override
    public Wire getSourceWire() {
        return sourceWire;
    }

    @Override
    public Wire getSinkWire() {
        return sinkWire;
    }

    @Override
    public boolean isWireConnection() {
        return true;
    }

    /** Programmable in the sense that we don't necessarily use all of the local VCC/GND sources **/
    @Override
    public boolean isPip() {
        return true;
    }

    @Override
    public boolean isRouteThrough() {
        return false;
    }

    @Override
    public boolean isDirectConnection() {
        return false;
    }

    /**
     * Get the single site associated with the connection, if there is one.
     *
     * @return the site
     */
    @Override
    public Site getSite() {
        return null;
    }

    @Override
    public boolean isPinConnection() {
        return false;
    }

    @Override
    public SitePin getSitePin() {
        return null;
    }

    @Override
    public PIP getPip() {
        return null;
    }

    @Override
    public boolean isTerminal() {
        return false;
    }

    @Override
    public BelPin getBelPin() {
        return null;
    }
}