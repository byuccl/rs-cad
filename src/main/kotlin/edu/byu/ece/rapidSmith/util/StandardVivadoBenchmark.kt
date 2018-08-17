package edu.byu.ece.rapidSmith.util

import edu.byu.ece.rapidSmith.interfaces.vivado.VivadoInterface
import java.nio.file.Files
import java.nio.file.Paths

fun main(argv: Array<String>) {
	var benchmarkPath = Paths.get(argv[0])
	var results_file = Paths.get(argv[1])
	var seed = argv[2].toInt()
	var constraint = argv[3].toFloat()
	var benchname = benchmarkPath.fileName.toFile().nameWithoutExtension
	var benchdir = benchmarkPath.parent
	println("building $benchname $benchmarkPath")
	System.out.flush()

	val orig_xdc = benchdir.resolve("$benchname.xdc")
	val new_xdc = Paths.get("$benchname-timing.xdc")
	Files.newBufferedWriter(new_xdc).use {o ->
		Files.newBufferedReader(orig_xdc).useLines {
			for (line in it) {
				var l = line
				l = l.replace(Regex("%TIMING%"), constraint.toString())
				l = l.replace(Regex("%TIMINGD2%"), (constraint/2).toString())
				o.write(l)
			}
		}
	}

	val timing_report = Paths.get("$benchname.twr")
	VivadoProject.from_dcp(benchname, benchmarkPath).use {
		// TODO set the strategy?
		println(it.addConstraintsFiles(new_xdc).joinToString(System.lineSeparator()) { it })
		println(it.place().joinToString(System.lineSeparator()) { it })
		println(it.route().joinToString(System.lineSeparator()) { it })
		println(it.timing_report(timing_report).joinToString(System.lineSeparator()) { it })
		println(it.export_rscp().joinToString(System.lineSeparator()) { it })
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

	Files.newBufferedWriter(results_file).use {
		it.write("delay : ${-delay}"); it.newLine()
	}

	var routed_rscp = VivadoInterface.loadRSCP("")
	gatherStats(routed_rscp.design)

	// TODO read in the exported rscp file
	// TODO what numbers do I need?
}


// Questions: Can we export relative placement constraints for packed results and let vivado place --Maybe
// Questions: How much replacement does Vivado do in route -- We're using constraints so it should be none


