package edu.byu.ece.rapidSmith.util

import edu.byu.ece.rapidSmith.cad.families.artix7.SiteCadFlow
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

	// Perform the flow
	val rscp = VivadoInterface.loadRSCP(benchmarkPath.toString())
	var flow = SiteCadFlow()

	val design = rscp.design
	val device = rscp.device
	flow.prepDesign(design, device)
	flow.run(design, device)
	val tcp = benchmarkPath.parent.resolve("$benchname.tcp")

	val orig_xdc = benchdir.resolve("$benchname.xdc")
	val new_xdc = Paths.get("$benchname-timing.xdc")
	Files.newBufferedWriter(new_xdc).use { o ->
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
	VivadoInterface.writeTCP(tcp.toString(), design, device, rscp.libCells)
	VivadoProject.from_tcp(benchname, tcp).use {
		println(it.addConstraintsFiles(new_xdc).joinToString(System.lineSeparator()) { it })
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
	// TODO read in the exported rscp file
	// TODO what numbers do I need?
}

