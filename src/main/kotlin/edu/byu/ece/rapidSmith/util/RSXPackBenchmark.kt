package edu.byu.ece.rapidSmith.util

import edu.byu.ece.rapidSmith.cad.families.SiteCadFlow
import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface
import java.nio.file.Files
import java.nio.file.Paths

fun main(argv: Array<String>) {
    var benchmarkPath = Paths.get(argv[1]);
    var benchname = benchmarkPath.fileName.toFile().nameWithoutExtension
    var results_file = Paths.get(argv[1])
    var rscpPath = benchmarkPath.resolve("$benchname.rscp")
    val rscp = VivadoInterface.loadRSCP(rscpPath.toString())
    var flow = SiteCadFlow()

    val design = rscp.design
    val device = rscp.device
    flow.prepDesign(design, device)
    flow.run(design, device)
    val tcp = benchmarkPath.parent.resolve("$benchname.tcp")
    val timing_report = Paths.get("$benchname.twr")
    VivadoInterface.writeTCP(tcp.toString(), design, device, rscp.libCells, true)
    val final = VivadoProject.from_tcp(benchname, tcp).use {
        println(it.place().joinToString(System.lineSeparator()) { it })
        println(it.route().joinToString(System.lineSeparator()) { it })
        println(it.timing_report(timing_report).joinToString(System.lineSeparator()) { it })
        println(it.export_rscp().joinToString(System.lineSeparator()) { it })
        it.exported_rscp
    }

    var delay: Float = Files.newBufferedReader(timing_report).useLines {
        val pattern = Regex("""\s*arrival time\s+(-?\d+(\.\d+)?)\s*""")
        var delay: Float? = null
        for (line in it) {
            delay = pattern.matchEntire(line)?.groupValues?.get(1)?.toFloat()
            if (delay != null) {
                break
            }
        }
        delay!!
    }

    var routed_rscp = VivadoInterface.loadRSCP(final.toString())
    Files.newBufferedWriter(results_file).use {
        it.write("delay : ${-delay}"); it.newLine()
        gatherStats(routed_rscp.design, it)
    }


    // TODO compare placed file with routed file for modifications
}
