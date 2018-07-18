package edu.byu.ece.rapidSmith.util

import java.nio.file.Paths

fun main(argv: Array<String>) {
    var benchmarkPath = Paths.get(argv[0])
    var benchname = benchmarkPath.fileName.toFile().nameWithoutExtension
    var benchdir = benchmarkPath.parent
    var dcp = benchdir.resolve("$benchname.dcp")
    VivadoProject.from_dcp(benchname, dcp).use {
        println(it.place())
        println(it.route())
        println(it.timing_report())
        println(it.export_rscp())
    }

    // TODO parse the timing report
    // TODO read in the exported rscp file
    // TODO what numbers do I need?
}


// Questions: Do we need to perform a timing sweep
// Questions: Can we export relative placement constraints for packed results and let vivado place
// Questions: How much replacement does Vivado do in route


