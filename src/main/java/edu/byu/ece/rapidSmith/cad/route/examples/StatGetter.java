package edu.byu.ece.rapidSmith.cad.route.examples;

import edu.byu.ece.rapidSmith.cad.pack.rsvpack.CadException;
import edu.byu.ece.rapidSmith.cad.route.RSVRoute;
import edu.byu.ece.rapidSmith.design.subsite.CellDesign;
import edu.byu.ece.rapidSmith.design.subsite.CellLibrary;
import edu.byu.ece.rapidSmith.device.Bel;
import edu.byu.ece.rapidSmith.device.Device;
import edu.byu.ece.rapidSmith.device.FamilyType;
import edu.byu.ece.rapidSmith.device.families.FamilyInfo;
import edu.byu.ece.rapidSmith.device.families.FamilyInfos;
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoCheckpoint;
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface;
import edu.byu.ece.rapidSmith.util.StatsGathererKt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class StatGetter {
	private static CellDesign design;
	private static Device device;
	private static Set<Bel> gndSourceBels;
	private static Set<Bel> vccSourceBels;

	private static void printStats() {
		System.out.println("Cells: " + design.getCells().size());
		System.out.println("Nets: " + design.getNets().size());
		int totalSites = device.getSites().size();
		FamilyType familyType = device.getFamily();
		FamilyInfo familyInfo = FamilyInfos.get(familyType);

		long totalSlices = device.getSites().values().stream().filter(site -> familyInfo.sliceSites().contains(site.getType()))
				.count();
		long usedSlices = design.getUsedSites().stream().filter(site -> familyInfo.sliceSites().contains(site.getType())).count();
		long ioSites = device.getSites().values().stream().filter(site -> familyInfo.ioSites().contains(site.getType())).count();
		long usedIoSites = design.getUsedSites().stream().filter(site -> familyInfo.ioSites().contains(site.getType())).count();

		long totalLuts = totalSlices * 8;
		long totalFlops = totalSlices * 8;

		Collection<String> lutBels = new ArrayList<>();
		lutBels.add("LUT6");
		lutBels.add("LUT5");
		lutBels.add("LUT_OR_MEM6");
		lutBels.add("LUT_OR_MEM5");

		Collection<String> flopBels = new ArrayList<>();
		flopBels.add("REG_INIT");
		flopBels.add("FF_INIT");

		long usedLuts = design.getUsedBels().stream().filter(bel -> lutBels.contains(bel.getType())).count();
		long usedFlops = design.getUsedBels().stream().filter(bel -> flopBels.contains(bel.getType())).count();

		System.out.println("Sites: " + design.getUsedSites().size() + "/" + totalSites);
		System.out.println("Slices: " + usedSlices + "/" + totalSlices);
		System.out.println("LUTs: " + usedLuts + "/" + totalLuts);
		System.out.println("FFs: " + usedFlops + "/" + totalFlops);
		System.out.println("IOBs: " + usedIoSites + "/" + ioSites);
		System.out.println("Wirelength: " + StatsGathererKt.computeWireLength(design));
	}

	private static void importDesign(String checkpointIn) throws IOException {
		VivadoCheckpoint vcp = VivadoInterface.loadRSCP(checkpointIn, true, true);

		// Get the pieces out of the checkpoint for use in manipulating it
		design = vcp.getDesign();
		device = vcp.getDevice();
		vccSourceBels = new HashSet<>();
		gndSourceBels = new HashSet<>();
		vccSourceBels.addAll(vcp.getVccSourceBels());
		gndSourceBels.addAll(vcp.getGndSourceBels());
	}


	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("Usage: StatGetter");
			System.exit(1);
		}
		String checkpointIn = args[0];

		// Import a routed design
		importDesign(checkpointIn);

		// Print some stats
		printStats();

	}

}
