package edu.byu.ece.rapidSmith.cad.families.artix7

import com.caucho.hessian.io.AbstractSerializerFactory
import com.caucho.hessian.io.Deserializer
import com.caucho.hessian.io.Serializer
import com.caucho.hessian.io.UnsafeSerializer
import edu.byu.ece.rapidSmith.RSEnvironment
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitList
import edu.byu.ece.rapidSmith.cad.cluster.loadPackUnits
import edu.byu.ece.rapidSmith.cad.cluster.site.PinName
import edu.byu.ece.rapidSmith.cad.cluster.site.SitePackUnitGenerator
import edu.byu.ece.rapidSmith.cad.cluster.site.use
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.device.families.Artix7.SiteTypes
import edu.byu.ece.rapidSmith.device.families.Artix7.TileTypes
import edu.byu.ece.rapidSmith.util.FileTools
import java.io.IOException
import java.nio.file.Files

class Artix7SitePackUnitGenerator : SitePackUnitGenerator() {
	override val PACKABLE_SITE_TYPES: List<SiteType>
	override val NULL_TILE_TYPE: TileType
	override val TIEOFF_SITE_TYPE: SiteType
	override val SWITCH_MATRIX_TILES: Set<TileType>
	override val INTERFACE_TILES: Set<TileType>
	override val VCC_SOURCES: Map<BelId, PinName>
	override val GND_SOURCES: Map<BelId, PinName>
	private val IGNORED_TILE_TYPES: Set<TileType>
	private val INSTANCE_NAMES: Map<SiteType, List<String>>

	init {
		PACKABLE_SITE_TYPES = ArrayList()
		PACKABLE_SITE_TYPES.add(SiteTypes.SLICEM)
		PACKABLE_SITE_TYPES.add(SiteTypes.SLICEL)
		PACKABLE_SITE_TYPES.add(SiteTypes.RAMB18E1)
		PACKABLE_SITE_TYPES.add(SiteTypes.RAMB36E1)
		PACKABLE_SITE_TYPES.add(SiteTypes.FIFO18E1)
		PACKABLE_SITE_TYPES.add(SiteTypes.FIFO36E1)
		PACKABLE_SITE_TYPES.add(SiteTypes.RAMBFIFO36E1)
		PACKABLE_SITE_TYPES.add(SiteTypes.DSP48E1)
		PACKABLE_SITE_TYPES.add(SiteTypes.STARTUP)
		PACKABLE_SITE_TYPES.add(SiteTypes.IOB33)
		PACKABLE_SITE_TYPES.add(SiteTypes.IOB33M)
		PACKABLE_SITE_TYPES.add(SiteTypes.IOB33S)
		PACKABLE_SITE_TYPES.add(SiteTypes.BUFG)

		INTERFACE_TILES = LinkedHashSet()
		INTERFACE_TILES.add(TileTypes.INT_INTERFACE_L)
		INTERFACE_TILES.add(TileTypes.INT_INTERFACE_R)
		INTERFACE_TILES.add(TileTypes.IO_INT_INTERFACE_L)
		INTERFACE_TILES.add(TileTypes.IO_INT_INTERFACE_R)
		INTERFACE_TILES.add(TileTypes.GTP_INT_INTERFACE)
		INTERFACE_TILES.add(TileTypes.BRAM_INT_INTERFACE_L)
		INTERFACE_TILES.add(TileTypes.BRAM_INT_INTERFACE_R)
		INTERFACE_TILES.add(TileTypes.PCIE_INT_INTERFACE_L)
		INTERFACE_TILES.add(TileTypes.PCIE_INT_INTERFACE_R)
		INTERFACE_TILES.add(TileTypes.RIOI3)
		INTERFACE_TILES.add(TileTypes.LIOI3)

		SWITCH_MATRIX_TILES = LinkedHashSet()
		SWITCH_MATRIX_TILES.add(TileTypes.INT_L)
		SWITCH_MATRIX_TILES.add(TileTypes.INT_R)

		NULL_TILE_TYPE = TileTypes.NULL
		TIEOFF_SITE_TYPE = SiteTypes.TIEOFF

		IGNORED_TILE_TYPES = LinkedHashSet()
		IGNORED_TILE_TYPES += TileTypes.BRAM_R
		IGNORED_TILE_TYPES += TileTypes.CLBLL_R
		IGNORED_TILE_TYPES += TileTypes.CLBLM_R
		IGNORED_TILE_TYPES += TileTypes.DSP_R

		INSTANCE_NAMES = HashMap()
		INSTANCE_NAMES[SiteTypes.SLICEM] = listOf("SLICE_X74Y81")
		INSTANCE_NAMES[SiteTypes.SLICEL] = listOf("SLICE_X40Y52")
		INSTANCE_NAMES[SiteTypes.RAMB18E1] = listOf("RAMB18_X2Y34")
		INSTANCE_NAMES[SiteTypes.RAMB36E1] = listOf("RAMB36_X2Y17")
		INSTANCE_NAMES[SiteTypes.FIFO18E1] = listOf("RAMB18_X2Y34")
		INSTANCE_NAMES[SiteTypes.FIFO36E1] = listOf("RAMB36_X2Y17")
		INSTANCE_NAMES[SiteTypes.RAMBFIFO36E1] = listOf("RAMB36_X2Y17")
		INSTANCE_NAMES[SiteTypes.DSP48E1] = listOf("DSP48_X2Y34")
		INSTANCE_NAMES[SiteTypes.STARTUP] = listOf("STARTUP_X0Y0")
		INSTANCE_NAMES[SiteTypes.IOB33] = listOf("C11")
		INSTANCE_NAMES[SiteTypes.IOB33M] = listOf("C11")
		INSTANCE_NAMES[SiteTypes.IOB33S] = listOf("C10")
		INSTANCE_NAMES[SiteTypes.BUFG] = listOf("BUFGCTRL_X0Y16")

		VCC_SOURCES = HashMap()
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "CEUSEDVCC")] = "1"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "CYINITVCC")] = "1"
/*
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "A6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "B6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "C6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "D6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "A5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "B5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "C5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "D5LUT")] = "O5"
*/
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "CEUSEDVCC")] = "1"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "CYINITVCC")] = "1"
/*
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "A6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "B6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "C6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "D6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "A5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "B5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "C5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "D5LUT")] = "O5"
*/
		GND_SOURCES = HashMap()
		GND_SOURCES[BelId(SiteTypes.SLICEL, "CYINITGND")] = "0"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "SRUSEDGND")] = "0"
/*
		GND_SOURCES[BelId(SiteTypes.SLICEL, "A6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "B6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "C6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "D6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "A5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "B5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "C5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "D5LUT")] = "O5"
*/
		GND_SOURCES[BelId(SiteTypes.SLICEM, "CYINITGND")] = "0"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "SRUSEDGND")] = "0"
/*
		GND_SOURCES[BelId(SiteTypes.SLICEM, "A6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "B6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "C6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "D6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "A5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "B5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "C5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "D5LUT")] = "O5"
*/
		GND_SOURCES[BelId(SiteTypes.IOB33S, "IBUFDISABLE_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.IOB33S, "INTERMDISABLE_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.IOB33M, "IBUFDISABLE_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.IOB33M, "INTERMDISABLE_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.IOB33, "IBUFDISABLE_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.IOB33, "INTERMDISABLE_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.ILOGICE2, "D2OBYP_TSMUX_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.ILOGICE2, "D2OFFBYP_TSMUX_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.ILOGICE3, "D2OBYP_TSMUX_GND")] = "0"
		GND_SOURCES[BelId(SiteTypes.ILOGICE3, "D2OFFBYP_TSMUX_GND")] = "0"
	}

	override fun findClusterInstances(siteType: SiteType, device: Device): List<Site> {
		return INSTANCE_NAMES[siteType]!!.map { device.getSite(it)!! }
	}

	companion object {
		@JvmStatic fun main(args: Array<String>) {
			val part = args[0]

			val device = Device.getInstance(part, true)

			val env = RSEnvironment.defaultEnv()
			val family = device.family
			val deviceDir = env.getPartFolderPath(family)
			val templatesPath = deviceDir.resolve("packunits-site.rpu")

			if (Files.exists(templatesPath) && !forceRebuild(args)) {
				val o = try {
					loadPackUnits<PackUnit>(templatesPath)
				} catch (e: Exception) {
					println("error reading device: $e")
					null
				}

				if (o != null) {
					println("Part $part already exists, skipping")
					return
				}
			}
			println("Generating template for $part")

			val packUnits = Artix7SitePackUnitGenerator().buildFromDevice(device)

			// write the templates
			try {
				FileTools.getCompactWriter(templatesPath).use { hos ->
					val serializeFactory = object : AbstractSerializerFactory() {
						override fun getDeserializer(cl: Class<*>?): Deserializer? {
							return null
						}

						override fun getSerializer(cl: Class<*>?): Serializer? {
							return when (cl) {
								PackUnitList::class.java -> UnsafeSerializer.create(cl)
								else -> null
							}
						}
					}
					hos.serializerFactory.addFactory(serializeFactory)
					hos.writeObject(packUnits)
				}
				println("Packunits written to file $templatesPath")
			} catch (e: IOException) {
				println("Error writing for device ...")
			}
		}

		private fun forceRebuild(args: Array<String>) =
			args.size >= 2 && args[1] == "rebuild"

	}
}
