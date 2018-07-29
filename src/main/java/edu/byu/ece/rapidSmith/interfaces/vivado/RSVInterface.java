package edu.byu.ece.rapidSmith.interfaces.vivado;

import edu.byu.ece.rapidSmith.cad.cluster.site.SiteCluster;
import edu.byu.ece.rapidSmith.design.subsite.CellDesign;
import edu.byu.ece.rapidSmith.design.subsite.CellLibrary;
import edu.byu.ece.rapidSmith.design.subsite.ImplementationMode;
import edu.byu.ece.rapidSmith.device.Device;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

// TODO: Merge with VivadoInterface

public final class RSVInterface {

	public static void writePackedTCP(String tcpDirectory, CellDesign design, Device device, CellLibrary libCells, List<SiteCluster> siteClusters) throws IOException {
		new File(tcpDirectory).mkdir();

		// insert routethrough buffers
		LutRoutethroughInserter inserter=new LutRoutethroughInserter(design,libCells);
		inserter.execute();

		// Write placement.xdc
		String placementOut=Paths.get(tcpDirectory,"placement.xdc").toString();
		XdcPackingInterface placementInterface=new XdcPackingInterface(design, device, siteClusters);
		placementInterface.writePlacementXdc(placementOut);

		// Write routing.xdc
		String routingOut=Paths.get(tcpDirectory,"routing.xdc").toString();
		XdcRoutingInterface routingInterface=new XdcRoutingInterface(design,device,null, ImplementationMode.REGULAR);
		routingInterface.writeRoutingXDC(routingOut,design);

		// Write EDIF netlist
		String edifOut=Paths.get(tcpDirectory,"netlist.edf").toString();
		EdifInterface.writeEdif(edifOut,design);

		// write constraints.xdc
		String constraintsOut=Paths.get(tcpDirectory,"constraints.xdc").toString();
		XdcConstraintsInterface constraintsInterface=new XdcConstraintsInterface(design,device);
		constraintsInterface.writeConstraintsXdc(constraintsOut);

		// write design.info
		String partInfoOut= Paths.get(tcpDirectory,"design.info").toString();
		DesignInfoInterface.writeInfoFile(partInfoOut,design.getPartName());
    }
}