package edu.byu.ece.rapidSmith.cad.families.zynq

import com.caucho.hessian.io.*
import edu.byu.ece.rapidSmith.RSEnvironment
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitList
import edu.byu.ece.rapidSmith.cad.cluster.site.PinName
import edu.byu.ece.rapidSmith.cad.cluster.site.SitePackUnit
import edu.byu.ece.rapidSmith.cad.cluster.site.SitePackUnitGenerator
import edu.byu.ece.rapidSmith.cad.cluster.site.use
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.device.families.Zynq.*
import edu.byu.ece.rapidSmith.util.FileTools
import java.io.IOException
import java.nio.file.Files

class ZynqSitePackUnitGenerator(val device: Device) : SitePackUnitGenerator() {
	override val PACKABLE_SITE_TYPES: List<SiteType>
	override val NULL_TILE_TYPE: TileType
	override val TIEOFF_SITE_TYPE: SiteType
	override val SWITCH_MATRIX_TILES: Set<TileType>
	override val INTERFACE_TILES: Set<TileType>
	override val VERSION = CURRENT_VERSION
	override val VCC_SOURCES: Map<BelId, PinName>
	override val GND_SOURCES: Map<BelId, PinName>
	private val IGNORED_TILE_TYPES: Set<TileType>
	private val INSTANCE_NAMES: Map<SiteType, List<String>>

	init { // Only add the site types that are actually present in the partial device!!


		PACKABLE_SITE_TYPES = ArrayList()

		if (device.getAllSitesOfType(SiteTypes.SLICEM) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.SLICEM)

		if (device.getAllSitesOfType(SiteTypes.SLICEL) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.SLICEL)

		if (device.getAllSitesOfType(SiteTypes.RAMB18E1) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.RAMB18E1)

		if (device.getAllSitesOfType(SiteTypes.RAMB36E1) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.RAMB36E1)

		if (device.getAllSitesOfType(SiteTypes.FIFO18E1) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.FIFO18E1)

		if (device.getAllSitesOfType(SiteTypes.FIFO36E1) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.FIFO36E1)

		if (device.getAllSitesOfType(SiteTypes.RAMBFIFO36E1) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.RAMBFIFO36E1)

		if (device.getAllSitesOfType(SiteTypes.DSP48E1) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.DSP48E1)

		if (device.getAllSitesOfType(SiteTypes.STARTUP) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.STARTUP)

		if (device.getAllSitesOfType(SiteTypes.IOB33) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.IOB33)

		if (device.getAllSitesOfType(SiteTypes.IOB33M) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.IOB33M)

		if (device.getAllSitesOfType(SiteTypes.IOB33S) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.IOB33S)

		if (device.getAllSitesOfType(SiteTypes.BUFG) != null)
			PACKABLE_SITE_TYPES.add(SiteTypes.BUFG)

		INTERFACE_TILES = HashSet() // Only add the interface tiles that are present in the partial device!

		if (device.hasTileType(TileTypes.INT_INTERFACE_L))
			INTERFACE_TILES.add(TileTypes.INT_INTERFACE_L)

		if (device.hasTileType(TileTypes.INT_INTERFACE_R))
			INTERFACE_TILES.add(TileTypes.INT_INTERFACE_R)

		if (device.hasTileType(TileTypes.IO_INT_INTERFACE_L))
			INTERFACE_TILES.add(TileTypes.IO_INT_INTERFACE_L)

		if (device.hasTileType(TileTypes.IO_INT_INTERFACE_R))
			INTERFACE_TILES.add(TileTypes.IO_INT_INTERFACE_R)

		if (device.hasTileType(TileTypes.BRAM_INT_INTERFACE_L))
			INTERFACE_TILES.add(TileTypes.BRAM_INT_INTERFACE_L)

		if (device.hasTileType(TileTypes.BRAM_INT_INTERFACE_R))
			INTERFACE_TILES.add(TileTypes.BRAM_INT_INTERFACE_R)

		if (device.hasTileType(TileTypes.RIOI3))
			INTERFACE_TILES.add(TileTypes.RIOI3)

		if (device.hasTileType(TileTypes.LIOI3))
			INTERFACE_TILES.add(TileTypes.LIOI3)




		SWITCH_MATRIX_TILES = HashSet()


		if (device.hasTileType(TileTypes.INT_L))
			SWITCH_MATRIX_TILES.add(TileTypes.INT_L)

		if (device.hasTileType(TileTypes.INT_R))
			SWITCH_MATRIX_TILES.add(TileTypes.INT_R)

		// TODO: Any checks for these with partial devices?
		NULL_TILE_TYPE = TileTypes.NULL
		TIEOFF_SITE_TYPE = SiteTypes.TIEOFF

		// TODO: What about this?
		IGNORED_TILE_TYPES = HashSet()
		IGNORED_TILE_TYPES += TileTypes.BRAM_R
		IGNORED_TILE_TYPES += TileTypes.CLBLL_R
		IGNORED_TILE_TYPES += TileTypes.CLBLM_R
		IGNORED_TILE_TYPES += TileTypes.DSP_R

		// TODO: Automatically find valid instances to use?
		INSTANCE_NAMES = HashMap()
		// WARNING: Do NOT use a SLICEM from the top row of the FPGA. These SLICEM's do not have a connection to continue
		// the carry chain like other SLICEM's.
		// Probably don't want to use a top SLICEL either.

		/*
		INSTANCE_NAMES[SiteTypes.SLICEM] = listOf("SLICE_X32Y125")
		INSTANCE_NAMES[SiteTypes.SLICEL] = listOf("SLICE_X33Y125")
		INSTANCE_NAMES[SiteTypes.RAMB18E1] = listOf("RAMB18_X2Y58")
		INSTANCE_NAMES[SiteTypes.RAMB36E1] = listOf("RAMB36_X2Y29")
		INSTANCE_NAMES[SiteTypes.FIFO18E1] = listOf("RAMB18_X2Y58")
		INSTANCE_NAMES[SiteTypes.FIFO36E1] = listOf("RAMB36_X2Y29")
		INSTANCE_NAMES[SiteTypes.RAMBFIFO36E1] = listOf("RAMB36_X2Y29")
		INSTANCE_NAMES[SiteTypes.DSP48E1] = listOf("DSP48_X2Y58")
		INSTANCE_NAMES[SiteTypes.STARTUP] = listOf("STARTUP_X0Y0")
		INSTANCE_NAMES[SiteTypes.IOB33] = listOf("C20")
		INSTANCE_NAMES[SiteTypes.IOB33M] = listOf("C20")
		INSTANCE_NAMES[SiteTypes.IOB33S] = listOf("B20")
		INSTANCE_NAMES[SiteTypes.BUFG] = listOf("BUFGCTRL_X0Y16")
		*/
		INSTANCE_NAMES[SiteTypes.SLICEM] = listOf("SLICE_X38Y96")
		INSTANCE_NAMES[SiteTypes.SLICEL] = listOf("SLICE_X37Y96")
		//INSTANCE_NAMES[SiteTypes.RAMB18E1] = listOf("RAMB18_X2Y58")
		//INSTANCE_NAMES[SiteTypes.RAMB36E1] = listOf("RAMB36_X2Y29")
		//INSTANCE_NAMES[SiteTypes.FIFO18E1] = listOf("RAMB18_X2Y58")
		//INSTANCE_NAMES[SiteTypes.FIFO36E1] = listOf("RAMB36_X2Y29")
		//INSTANCE_NAMES[SiteTypes.RAMBFIFO36E1] = listOf("RAMB36_X2Y29")
		//INSTANCE_NAMES[SiteTypes.DSP48E1] = listOf("DSP48_X2Y58")


		// Can a startup site even be in a partial device (for partial reconfiguration)?
		//INSTANCE_NAMES[SiteTypes.STARTUP] = listOf("STARTUP_X0Y0")
		//INSTANCE_NAMES[SiteTypes.IOB33] = listOf("C20")
		//INSTANCE_NAMES[SiteTypes.IOB33M] = listOf("C20")
		//INSTANCE_NAMES[SiteTypes.IOB33S] = listOf("B20")
		// INSTANCE_NAMES[SiteTypes.BUFG] = listOf("BUFGCTRL_X0Y16")


		VCC_SOURCES = HashMap()

		VCC_SOURCES[BelId(SiteTypes.SLICEL, "CEUSEDVCC")] = "1"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "CYINITVCC")] = "1"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "A6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "B6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "C6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "D6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "A5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "B5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "C5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEL, "D5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "CEUSEDVCC")] = "1"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "CYINITVCC")] = "1"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "A6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "B6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "C6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "D6LUT")] = "O6"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "A5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "B5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "C5LUT")] = "O5"
		VCC_SOURCES[BelId(SiteTypes.SLICEM, "D5LUT")] = "O5"


		GND_SOURCES = HashMap()

		GND_SOURCES[BelId(SiteTypes.SLICEL, "CYINITGND")] = "0"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "SRUSEDGND")] = "0"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "A6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "B6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "C6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "D6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "A5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "B5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "C5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEL, "D5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "CYINITGND")] = "0"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "SRUSEDGND")] = "0"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "A6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "B6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "C6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "D6LUT")] = "O6"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "A5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "B5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "C5LUT")] = "O5"
		GND_SOURCES[BelId(SiteTypes.SLICEM, "D5LUT")] = "O5"
		/*
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
		*/

	}

	override fun findClusterInstances(siteType: SiteType, device: Device): List<Site> {
		return INSTANCE_NAMES[siteType]!!.map { device.getSite(it)!! }
	}

	companion object {
		val CURRENT_VERSION = "1.0.0"

		@JvmStatic fun main(args: Array<String>) {
			val part = args[0]
			val device = Device.getInstance(part, true)
			val env = RSEnvironment.defaultEnv()
			val family = device.family
			val deviceDir = env.getPartFolderPath(family)
			val templatesPath = deviceDir.resolve(part + "_packunits_site.rpu")

			if (Files.exists(templatesPath) && !forceRebuild(args)) {
				val o = try {
					FileTools.getCompactReader(templatesPath).use { his ->
						@Suppress("UNCHECKED_CAST")
						his.readObject() as PackUnitList<SitePackUnit>
					}
				} catch (e: Exception) {
					println("error reading device: $e")
					null
				}

				if (o?.version == CURRENT_VERSION) {
					println("Part $part already exists, skipping")
					return
				}
			}
			println("Generating template for " + part)

			val packUnits = ZynqSitePackUnitGenerator(device).buildFromDevice(device)

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
			} catch (e: IOException) {
				println("Error writing for device ...")
			}
		}

		private fun forceRebuild(args: Array<String>) =
			args.size >= 3 && args[2] == "rebuild"

	}
}
