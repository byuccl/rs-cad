package edu.byu.ece.rapidSmith.util

import edu.byu.ece.rapidSmith.cad.families.artix7.SiteCadFlow
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface
import java.nio.file.Paths

fun main(argv: Array<String>) {
    var benchmarkPath = Paths.get(argv[1]);
    var benchname = benchmarkPath.fileName.toFile().nameWithoutExtension
    var rscpPath = benchmarkPath.resolve("$benchname.rscp")
    val rscp = VivadoInterface.loadRSCP(rscpPath.toString())
    var flow = SiteCadFlow()

    val design = rscp.design
    val device = rscp.device
    flow.prepDesign(design, device)
    flow.run(design, device)
    val tcp = benchmarkPath.parent.resolve("$benchname.tcp")
    VivadoInterface.writeTCP(tcp.toString(), design, device, rscp.libCells)
    VivadoProject.from_tcp(benchname, tcp).use {
        it.place()
        it.route()
        it.timing_report()
        it.export_rscp()
    }

    // TODO parse the timing report
    // TODO read in the exported rscp file
    // TODO what numbers do I need?
    // TODO compare placed file with routed file for modifications
}
