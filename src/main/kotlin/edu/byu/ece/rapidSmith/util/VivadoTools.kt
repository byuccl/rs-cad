package edu.byu.ece.rapidSmith.util

import java.nio.file.Path
import java.nio.file.Paths

enum class HdlType {
	VHDL, VERILOG, ANY
}

class VivadoProject internal constructor(
		internal val name: String,
		internal val console: VivadoConsole
) : AutoCloseable {
	override fun close() {
		console.close(true)
	}

	var exported_rscp: Path? = null
		private set

	fun addConstraintsFiles(vararg files: Path, options: List<String> = emptyList()): List<String> {
		val opstring = if (options.isNotEmpty()) options.joinToString(" ", " ") else ""
		val filesString = files.joinToString(" ", " ") { it.toAbsolutePath().toString() }
		return console.runCommand("read_xdc$opstring$filesString")
	}

	fun place(options: List<String> = emptyList()): MutableList<String> {
		println("Placing design")
		val cmd = "place_design" + options.joinToString(" ", " ")
		return console.runCommand(cmd)
	}

	fun route(options: List<String> = emptyList()): MutableList<String> {
		println("Routing design")
		val cmd = "route_design" + options.joinToString(" ", " ")
		return console.runCommand(cmd)
	}

	fun export_rscp(file: Path?=null, options: List<String> = emptyList()): MutableList<String> {
		println("Exporting tcp")
		val fpath = file ?: Paths.get("$name.rscp")
		val opstring = options.joinToString(" ", " ")
		val output = console.runCommand("tincr::write_rscp$opstring $fpath")
		exported_rscp = fpath
		return output
	}

	fun timing_report(file: Path?=null): MutableList<String> {
		println("Generating timing report")
		var fpath = file ?: Paths.get("$name.twr")
		return console.runCommand("report_timing -setup -path_type short -file $fpath")
	}

	companion object {
		fun cleanProject(name: String, console: VivadoConsole = VivadoConsole()): VivadoProject {
			return VivadoProject(name, console)
		}

		fun from_dcp(name: String, dcp: Path, console: VivadoConsole = VivadoConsole()): VivadoProject {
			val prj = VivadoProject(name, console)
			println(console.runCommand("read_checkpoint $dcp"))
			println(console.runCommand("link_design"))
			return prj
		}

		fun from_tcp(name: String, tcp: Path, console: VivadoConsole = VivadoConsole()): VivadoProject {
			println("Reading $tcp into Vivado")
			val prj = VivadoProject(name, console)
			console.runCommand("tincr::read_tcp $tcp")
			return prj
		}
	}
}
