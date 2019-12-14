package edu.byu.ece.rapidSmith.cad.route.examples;

import edu.byu.ece.rapidSmith.cad.cluster.Cluster;
import edu.byu.ece.rapidSmith.cad.cluster.site.SiteClusterSite;
import edu.byu.ece.rapidSmith.cad.cluster.site.SitePackUnit;
import edu.byu.ece.rapidSmith.cad.families.zynq.ZynqSiteCadFlow;
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException;
import edu.byu.ece.rapidSmith.cad.place.annealer.SimulatedAnnealingPlacer;
import edu.byu.ece.rapidSmith.cad.route.RSVRoute;
import edu.byu.ece.rapidSmith.design.subsite.*;
import edu.byu.ece.rapidSmith.device.*;
import edu.byu.ece.rapidSmith.device.families.FamilyInfo;
import edu.byu.ece.rapidSmith.device.families.FamilyInfos;
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoCheckpoint;
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface;
import edu.byu.ece.rapidSmith.interfaces.vivado.XdcConstraint;
import edu.byu.ece.rapidSmith.interfaces.xray.XrayInterface;
import edu.byu.ece.rapidSmith.interfaces.yosys.YosysInterface;
import edu.byu.ece.rapidSmith.util.StatsGathererKt;
import edu.byu.ece.rapidSmith.util.Time;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static edu.byu.ece.rapidSmith.cad.families.zynq.ZynqKt.getZynqGroupSAPlacer;

public class MaverickDemo {

	private static CellDesign design = null;
	private static Device device = null;
	private static CellLibrary libCells = null;
	private static Map<Bel, BelRoutethrough> belRoutethroughMap;
	private static Set<Bel> gndSourceBels = null;
	private static Set<Bel> vccSourceBels = null;
	private static Map<CellPin, CellNet> portPinConns;
	private static List<Cell> portCells;
	/** PIPs used by the static design */
	private static Collection<PIP> staticPips;

	private static void exportFASM(String fasmOut) throws IOException {
		XrayInterface xrayInterface = new XrayInterface(design, device, belRoutethroughMap, vccSourceBels, gndSourceBels, staticPips);
		xrayInterface.writeFASM(fasmOut);
	}


	private static void importDesign(String checkpointIn) throws IOException {
		VivadoCheckpoint vcp = YosysInterface.loadRSCP(checkpointIn, true);

		// Get the pieces out of the checkpoint for use in manipulating it
		design = vcp.getDesign();
		device = vcp.getDevice();
		libCells = vcp.getLibCells();
		staticPips = vcp.getStaticPips();

		belRoutethroughMap = vcp.getBelRoutethroughMap();
		vccSourceBels = new HashSet<>();
		gndSourceBels = new HashSet<>();

		if (design.getImplementationMode().equals(ImplementationMode.RECONFIG_MODULE)) {
			removeUnusedPorts();
		}

	}

	private static List<Cluster<SitePackUnit, SiteClusterSite>> packDesign(CellDesign design) {
		// Make sure the design is unrouted and unplaced
		//design.unrouteDesignFull();
		//design.unplaceDesign();

		// Remove any pseudo pins in the design
		Iterator<Cell> cellIt = design.getLeafCells().iterator();
		while (cellIt.hasNext()) {
			Cell leafCell = cellIt.next();
			leafCell.removePseudoPins();
		}

		// Disconnect all nets from pseudo pins
		for (CellNet net : design.getNets()) {
			net.disconnectFromPins(net.getPseudoPins());
		}

		// Disconnect carry in pins from the ground net
		Collection<CellPin> gndCiPins = design.getGndNet().getSinkPins().stream()
				.filter(p -> p.getCell().getLibCell().getName().equals("CARRY4"))
				.filter(p -> p.getName().equals("CI"))
				.collect(Collectors.toList());
		design.getGndNet().disconnectFromPins(gndCiPins);

		// Disconnect carry in pins from the vcc net
		Collection<CellPin> vccCiPins = design.getVccNet().getSinkPins().stream()
				.filter(p -> p.getCell().getLibCell().getName().equals("CARRY4"))
				.filter(p -> p.getName().equals("CI"))
				.collect(Collectors.toList());
		design.getVccNet().disconnectFromPins(vccCiPins);

		// Pack the design
		ZynqSiteCadFlow zynqSiteCadFlow = new ZynqSiteCadFlow();

		return zynqSiteCadFlow.pack(design, device);
	}

	private static void routeDesign(CellDesign design) {
		RSVRoute router = new RSVRoute(device, design, libCells, vccSourceBels, gndSourceBels);
		try {
			router.routeDesign();
		} catch (CadException e) {
			e.printStackTrace();
		}
	}

	private static void findStaticSourceBels(CellDesign design, Set<Bel> vccSourceBels, Set<Bel> gndSourceBels) {
		// Get a list of static source BELs

		for (RouteTree routeTree : design.getVccNet().getSinkRouteTrees()) {
			RouteTree rootTree = routeTree.getRoot();
			BelPin rootBelPin = rootTree.getConnectedSourceBelPin();

			if (rootBelPin == null)
				continue;

			if (rootBelPin.getBel().getName().contains("LUT")) {
				// O5 or O6 pin of a LUT BEL
				vccSourceBels.add(rootBelPin.getBel());
			}
		}

		for (RouteTree routeTree : design.getGndNet().getSinkRouteTrees()) {
			RouteTree rootTree = routeTree.getRoot();
			BelPin rootBelPin = rootTree.getConnectedSourceBelPin();

			if (rootBelPin == null)
				continue;

			if (rootBelPin.getBel().getName().contains("LUT")) {
				// O5 or O6 pin of a LUT BEL
				gndSourceBels.add(rootBelPin.getBel());
			}
		}
	}


	// RS2 handles polarity muxes in a special way, so polarity mux configuration cannot be identified the same way
	// as other routing muxes (site pips). However, the configuration for these muxes are stored as a property on the
	// cells that are driven by these muxes (in netlists produced by Vivado).
	// See Travis Haroldsen's dissertation for more info on polarity muxes.
	// Note that these properties are only present after routing though...

	// As far as I can tell, there is no way for a user to change the polarity of a polarity selector in RS2.
	// So, for any sites that use the CLKINV polarity selector, we must just assume it is NOT inverting for now
	// (unless it came into RS2 from Vivado inverting)

	private static Map<String, String> getAllSitePips(RouteTree routeTree) {
		return getAllSitePips(routeTree.getRoot(), new HashMap<>());
	}

	private static Map<String, String> getAllSitePips(RouteTree routeTree, Map<String, String> pipInputs) {
		for (RouteTree rt : routeTree.getChildren()) {
			if (rt.getConnection().isPip()) {
				PIP pip = rt.getConnection().getPip();

				String pipName = pip.getStartWire().getName();
				String[] vals = pipName.split("/");
				assert vals.length == 2;
				vals = vals[1].split("\\.");
				assert vals.length == 2;

				// TODO: This likely isn't doing what was originally intended. Verify and remove.
				if (vals[0].equals("PRECYINIT")) {
					Bel carry4Bel = pip.getStartWire().getSite().getBel("CARRY4");

					if (design.isBelUsed(carry4Bel)) {
						Cell carry4Cell = design.getCellAtBel(carry4Bel);

						// If the CI pin is connected to a net, then the PRECYINIT BEL is really tied to the CIN input
						if (carry4Cell.getPin("CI").isConnectedToNet()) {
							vals[1] = "CIN";
						}
					}
				}

				pipInputs.put(vals[0], vals[1]);
			} else if (rt.getConnection().getSourceWire().getName().contains("CLK.CLK")) {
				String pipName = rt.getConnection().getSourceWire().getName();
				String[] vals = pipName.split("/");
				assert vals.length == 2;
				vals = vals[1].split("\\.");
				assert vals.length == 2;

				pipInputs.put("CLKINV", "CLK");
			}
			else if (rt.getConnectedBelPin() != null && rt.getConnectedBelPin().getName().equals("CIN")) {
				Bel carry4Bel = rt.getConnectedBelPin().getBel();
				assert (design.isBelUsed(carry4Bel));
				Cell carry4Cell = design.getCellAtBel(carry4Bel);
				assert (carry4Cell.getPin("CI").isConnectedToNet());
				pipInputs.put("PRECYINIT", "CIN");
			}
			getAllSitePips(rt, pipInputs);
		}
		return pipInputs;
	}

	private static void addUnusedPorts() {
		// Add "unused" port cells back
		for (Cell port : portCells) {
			design.addCell(port);
		}

		// Reconnect the port's pins to the correct nets
		for (Cell port : portCells) {
			for (CellPin pin : port.getPins()) {
				portPinConns.get(pin).connectToPin(pin);
			}
		}
	}



	/**
	 * Sets the used PIP (pipInVals) design data structure by using the
	 * sink route trees. Travis' packer/placer currently does not set this data structure, so I do it here.
	 */
	private static void setUsedPips(CellDesign design) {
		// Do I care about the usedSitePipsMap data structure? Or just pipInValues?
		// design.setUsedSitePipsAtSite(site, usedSitePips); - usedSitePipsMap
		// design.addPIPInputValsAtSite(site, pipToInputVal); - pipInValues

		for (CellNet net : design.getNets()) {
			// We must even consider intrasite-only nets! They can affect the intrasite routing of a site.

			List<RouteTree> sinkRouteTrees;
			if (net.isStaticNet())
				sinkRouteTrees = net.getSinkRouteTrees();
			else
				sinkRouteTrees = net.getSinkSitePinRouteTrees();

			for (RouteTree routeTree : sinkRouteTrees) {
				Site site = routeTree.getWire().getSite();

				Map<String, String> pipInputVals = getAllSitePips(routeTree);
				design.addPipInputValsAtSite(site, pipInputVals);
			}

			// Find site PIPs from intrasite source (driver) route trees
			RouteTree routeTree = net.getSourceRouteTree();

			if (routeTree == null) // VCC, GND, (probably only these) may not have an intrasite route tree
				continue;

			Site site = routeTree.getWire().getSite();
			Map<String, String> pipInputVals = getAllSitePips(routeTree);
			design.addPipInputValsAtSite(site, pipInputVals);
		}
	}


	private static void removeUnusedPorts() {
		// There may be some ports in the RM netlist which are unused.
		// This is Case 5. Reconfigurable Module has an active driver, but the Static side has no active loads, described in
		// vivado Partial Reconfig Documentation.
		// "This does not result in an error, but is far from optimal because the RM logic remains. No
		// partition pin is inserted. These partition outputs should be removed."
		// These ports must remain in the netlist for RM import, although they are unused.
		// TODO: Is this the best way to address this? (No)
		portPinConns = new HashMap<>();
		portCells = new ArrayList<>();

		for (Iterator<Cell> iterator = design.getPorts().iterator(); iterator.hasNext(); ) {
			Cell port = iterator.next();

			if (!port.hasPartitionPin()) {
				portCells.add(port);

				for (CellPin pin : port.getPins()) {
					portPinConns.put(pin, pin.getNet());
				}
			}
		}

		for (Cell port : portCells) {
			design.removeCell(port);
		}
	}

	/**
	 * Sets Vivado's DRC checks that make sure ports are constrained and have specified I/O standards to be
	 * warnings instead of errors.
	 */
	private static void disablePortDRC() {
		design.addVivadoConstraint(new XdcConstraint("set_property", "SEVERITY {Warning} [get_drc_checks NSTD-1]"));
		design.addVivadoConstraint(new XdcConstraint("set_property", "SEVERITY {Warning} [get_drc_checks UCIO-1]"));
	}

	/**
	 * Sets all the cells and nets in the design to DONT_TOUCH. This must be done instead of simply setting the entire
	 * design to DONT_TOUCH to ensure the "update_design -cells blackbox_cell -from_file rm_netlist.edf" TCL command
	 * does not do any optimizations on the netlist.
	 */
	private static void dontTouchEdif() {
		// Set all cells to DONT_TOUCH
		for (Cell cell : design.getCells()) {
			if (!cell.getProperties().has("DONT_TOUCH"))
				cell.getProperties().add(new Property("DONT_TOUCH", PropertyType.EDIF, "TRUE"));
		}

		// Set all nets to DONT_TOUCH
		for (CellNet net : design.getNets()) {
			if (!net.getProperties().has("DONT_TOUCH"))
				net.getProperties().add(new Property("DONT_TOUCH", PropertyType.EDIF, "TRUE"));
		}
	}

	private static void exportDesign(String checkpointOut) throws IOException {
		// Prepare to export the TCP
		if (design.getImplementationMode().equals(ImplementationMode.RECONFIG_MODULE)) {
			addUnusedPorts();
		}

		disablePortDRC();
		dontTouchEdif();

		// TODO: Don't modify the staticrouteMap in this part.
		VivadoInterface.writeTCP(checkpointOut, design, device, libCells, true);
	}


	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("Usage: MaverickDemo rscpCheckpointDirectoryName");
			System.exit(1);
		}

		Time runTime = new Time();
		String checkpointIn = args[0];
		String fasmOut = checkpointIn.substring(0, checkpointIn.length() - 4) + "fasm";
		String tcpOut = checkpointIn.substring(0, checkpointIn.length() - 4) + "tcp";

		// Set RSCP filename from command line arg
		runTime.setStartTime();
		importDesign(checkpointIn);
		runTime.setEndTime();
		System.out.print(runTime.getTotalTime() + " ");

		// Pack the design
		runTime.setStartTime();
		List<Cluster<SitePackUnit, SiteClusterSite>> clusters = packDesign(design);
		runTime.setEndTime();
		System.out.print(runTime.getTotalTime() + " ");
		System.out.println("Clusterz: " + clusters.size());

		// Place the design
		runTime.setStartTime();
		SimulatedAnnealingPlacer<SiteClusterSite> placer = getZynqGroupSAPlacer();
		placer.place(device, design, clusters);

		// These steps should be included in the packer/placer in the future.
		findStaticSourceBels(design, vccSourceBels, gndSourceBels);
		setUsedPips(design);
		runTime.setEndTime();
		System.out.print(runTime.getTotalTime() + " ");


		// Route design
		runTime.setStartTime();
		routeDesign(design);
		runTime.setEndTime();
		System.out.print(runTime.getTotalTime()  + " ");

		// Write FASM
		//runTime.setStartTime();
		//exportFASM(fasmOut);
		//runTime.setEndTime();
		//System.out.println(runTime.getTotalTime());

		//runTime.setStartTime();
		exportDesign(tcpOut);
		//runTime.setEndTime();
		//System.out.println("Took " + runTime.getTotalTime() + " seconds to write TCP");
	}

}

