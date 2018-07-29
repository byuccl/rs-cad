/*
 * Copyright (c) 2016 Brigham Young University
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

package edu.byu.ece.rapidSmith.interfaces.vivado;

import edu.byu.ece.rapidSmith.cad.cluster.site.SiteCluster;
import edu.byu.ece.rapidSmith.design.subsite.Cell;
import edu.byu.ece.rapidSmith.design.subsite.CellDesign;
import edu.byu.ece.rapidSmith.design.subsite.CellPin;
import edu.byu.ece.rapidSmith.device.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static edu.byu.ece.rapidSmith.util.Exceptions.ImplementationException;

/**
 * This class is used for parsing and writing placement XDC files in a TINCR checkpoint.
 * Placement.xdc files are used to specify the physical location of cells in a Vivado netlist.
 *
 * @author Thomas Townsend, Dallon Glick
 */

// TODO: Merge with RS2's XdcPlacementInterface

public class XdcPackingInterface {

    private final CellDesign design;
    private final Device device;
    private final List<SiteCluster> siteClusters;
    private final Map<BelPin, CellPin> belPinToCellPinMap;

    public XdcPackingInterface(CellDesign design, Device device, List<SiteCluster> siteClusters) {
        this.design = design;
        this.device = device;
        this.siteClusters = siteClusters;
        belPinToCellPinMap = new HashMap<>();
    }

    /**
     * Returns the map of BelPin->CellPin mapping after the placement xdc
     * has been applied to the design. Should be called after parsePlacementXDC
     * is called.
     *
     * @return Map from BelPin to the CellPin that is placed on it
     */
    public Map<BelPin, CellPin> getPinMap() {
        return belPinToCellPinMap;
    }

    /**
     * Writes the XDC commands to create RLOC placer macros for clusters in Vivado.
     * Also writes necessary XDC commands for cells that are packed, but not placed.
     *
     * @param fileout
     * @throws IOException
     */
    private void writeClusters(BufferedWriter fileout) throws IOException {
        for (SiteCluster cluster : siteClusters) {
            String clusterName = cluster.getName();
            String siteTypeName = cluster.getType().getSiteType().name();
            StringBuilder cellRlocList = new StringBuilder();

            Collection<Cell> cells = cluster.getCells().stream()
                    .filter(cell -> !cell.isPort()).collect(Collectors.toList());
            for (Cell cell : cells) {
                String cellname = cell.getName();
                Bel bel = cluster.getCellPlacement(cell);
                assert (bel != null);
                String belName = bel.getName();
                cellRlocList.append(cellname).append(" X0Y0 ");

                // Set the cell's BEL type and cell to bel pin mappings for packed, but not placed cells
                if (!cell.isPlaced()) {
                    fileout.write(String.format("set_property BEL %s.%s [get_cells {%s}]\n", siteTypeName, belName, cellname));

                    // Cell to Bel Pin Mappings
                    //TODO: Update this function when more cells with LOCK_PINS are discovered
                    if (cell.isLut()) {
                        fileout.write("set_property LOCK_PINS { ");
                        for (CellPin cp : cell.getInputPins()) {
                            if (!cp.isPseudoPin() && cluster.getPinMapping(cp) != null) {
                                assert (cluster.getPinMapping(cp).size() == 1);
                                BelPin belPin = cluster.getPinMapping(cp).get(0);
                                fileout.write(String.format("%s:%s ", cp.getName(), belPin.getName()));
                            }
                        }
                        fileout.write("} [get_cells {" + cellname + "}]\n");
                    }
                }

            }
            if (cells.size() > 1) {
                // Create the XDC placer macro
                fileout.write(String.format("create_macro %s\n", clusterName));
                fileout.write(String.format("update_macro %s {%s}\n", clusterName, cellRlocList));
            }
        }
    }

    /**
     * Writes the XDC commands for placed cells
     *
     * @param fileout
     * @throws IOException
     */
    private void writePlacedCells(BufferedWriter fileout) throws IOException {
        Iterator<Cell> cellIt = sortCellsForXdcExport(design).iterator();

        // All cells are assumed placed in this while loop
        while (cellIt.hasNext()) {
            Cell cell = cellIt.next();
            Site site = cell.getSite();
            Bel bel = cell.getBel();

            String cellname = cell.getName();

            // ports need a package pin reference, and aren't placed in Vivado
            if (cell.isPort()) {
                PackagePin packagePin = device.getPackagePin(bel);
                // if the port is not mapped to a valid package pin, thrown an exception
                if (packagePin == null) {
                    if (device.getPackagePins().isEmpty()) {
                        throw new ImplementationException("Device " + device.getPartName() + " is missing package pin information: cannot generate TCP without it.\n"
                                + "To generate the package pin information and add it to your device follow these three steps: \n"
                                + "1.) Run the Tincr command \"tincr::create_xml_device_info\" for your part.\n"
                                + "2.) Store the generated XML file to the devices/family directory which corresponds to your part.\n"
                                + "3.) Run the DeviceInfoInstaller in the util package to add the package pins to the device");
                    }

                    throw new ImplementationException("Cannot export placement information for port cell " + cellname + ".\n"
                            + "Package Pin for BEL " + bel.getFullName() + " cannot be found.");
                }
                fileout.write(String.format("set_property PACKAGE_PIN %s [get_ports {%s}]\n", packagePin.getName(), cellname));
            } else {
                fileout.write(String.format("set_property BEL %s.%s [get_cells {%s}]\n", site.getType().name(), bel.getName(), cellname));
                fileout.write(String.format("set_property LOC %s [get_cells {%s}]\n", site.getName(), cellname));

                //TODO: Update this function when more cells with LOCK_PINS are discovered
                if (cell.isLut()) {
                    fileout.write("set_property LOCK_PINS { ");
                    for (CellPin cp : cell.getInputPins()) {
                        if (!cp.isPseudoPin() && cp.getMappedBelPin() != null) {
                            fileout.write(String.format("%s:%s ", cp.getName(), cp.getMappedBelPin().getName()));
                        }
                    }
                    fileout.write("} [get_cells {" + cellname + "}]\n");
                }
            }
        }
    }

    /**
     * Creates a placement.xdc file from the cells of the given design
     * This file can be imported into Vivado to constrain the cells to a physical location
     *
     * @param xdcOut Output placement.xdc file location
     * @throws IOException
     */
    public void writePlacementXdc(String xdcOut) throws IOException {
        try (BufferedWriter fileout = new BufferedWriter(new FileWriter(xdcOut))) {

            // Write clusters, including packed-only and placed cells
            writeClusters(fileout);

            // Write placed cells
            writePlacedCells(fileout);
        }
    }

    /*
     * Sorts the cells of the design in the order required for TINCR export.
     * Cells that are unplaced are not included in the sorted list.
     * Uses a bin sorting algorithm to have a complexity of O(n).
     *
     * TODO: Add <is_lut>, <is_carry>, and <is_ff> tags to cell library
     */
    private Stream<Cell> sortCellsForXdcExport(CellDesign design) {
        // cell bins
        ArrayList<Cell> sorted = new ArrayList<>(design.getCells().size());
        ArrayList<Cell> lutCellsH5 = new ArrayList<>();
        ArrayList<Cell> lutCellsD5 = new ArrayList<>();
        ArrayList<Cell> lutCellsABC5 = new ArrayList<>();
        ArrayList<Cell> lutCellsH6 = new ArrayList<>();
        ArrayList<Cell> lutCellsD6 = new ArrayList<>();
        ArrayList<Cell> lutCellsABC6 = new ArrayList<>();
        ArrayList<Cell> carryCells = new ArrayList<>();
        ArrayList<Cell> ffCells = new ArrayList<>();
        ArrayList<Cell> ff5Cells = new ArrayList<>();
        ArrayList<Cell> muxCells = new ArrayList<>();

        // traverse the cells and drop them in the correct bin
        Iterator<Cell> cellIt = design.getLeafCells().iterator();

        while (cellIt.hasNext()) {
            Cell cell = cellIt.next();

            // only add cells that are placed to the list
            if (!cell.isPlaced()) {
                continue;
            }

            String libCellName = cell.getLibCell().getName();
            String belName = cell.getBel().getName();

            if (belName.endsWith("6LUT")) {
                if (belName.contains("H")) {
                    lutCellsH6.add(cell);
                } else if (belName.contains("D")) {
                    lutCellsD6.add(cell);
                } else {
                    lutCellsABC6.add(cell);
                }
            } else if (belName.endsWith("5LUT")) {
                if (belName.contains("H")) {
                    lutCellsH5.add(cell);
                } else if (belName.contains("D")) {
                    lutCellsD5.add(cell);
                } else {
                    lutCellsABC5.add(cell);
                }
            } else if (libCellName.startsWith("CARRY")) {
                carryCells.add(cell);
            } else if (belName.endsWith("5FF")) {
                ff5Cells.add(cell);
            } else if (belName.endsWith("FF")) {
                ffCells.add(cell);
            } else if (belName.endsWith("MUX")) {
                muxCells.add(cell);
            } else {
                sorted.add(cell);
            }
        }

        // append all other cells in the correct order
        return Stream.of(sorted.stream(),
                lutCellsH5.stream(),
                lutCellsD5.stream(),
                lutCellsABC5.stream(),
                lutCellsH6.stream(),
                lutCellsD6.stream(),
                lutCellsABC6.stream(),
                ffCells.stream(),
                carryCells.stream(),
                muxCells.stream(),
                ff5Cells.stream())
                .flatMap(Function.identity());
    }
}
