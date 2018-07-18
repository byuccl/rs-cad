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

    fun addHdlFiles(type: HdlType, vararg files: Path, library: String? = null,
            options: List<String> = emptyList()
    ) {
        val opstring = options.joinToString(" ", " ")
        val filesString = files.joinToString(" ", " ") { it.toAbsolutePath().toString() }
        val libString = when {
            library != null -> "-library $library"
            type == HdlType.VHDL -> "-library work"
            else -> ""
        }

        when (type) {
            HdlType.VHDL -> console.runCommand("read_vhdl$opstring$libString$filesString")
            HdlType.VERILOG -> console.runCommand("read_verilog$opstring$libString$filesString")
            HdlType.ANY -> console.runCommand("add_file$opstring$filesString")
        }
    }

    fun addConstraintsFiles(vararg files: Path, options: List<String> = emptyList()) {
        val opstring = options.joinToString(" ", " ")
        val filesString = files.joinToString(" ", " ") { it.toAbsolutePath().toString() }
        console.runCommand("run_xdc$opstring$filesString")
    }

    fun synthesize(top_module: String, part: String, options: List<String> = emptyList()) {
        println("Synthesizing design")
        var cmd = "synth_design -name $name -part $part -top $top_module"
        cmd += options.joinToString(" ", " ")
        console.runCommand(cmd)
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
        val fpath = file ?: Paths.get(name)
        val opstring = options.joinToString(" ", " ")
        return console.runCommand("tincr::write_rscp$opstring $fpath")
    }

    fun timing_report(): MutableList<String> {
        println("Generating timing report")
        return console.runCommand("report_timing -setup -path_type short")
        // TODO parse the generated report
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
