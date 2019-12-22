package edu.byu.ece.rapidSmith.cad.families.zynq

import com.caucho.hessian.io.*
import edu.byu.ece.rapidSmith.RSEnvironment
import edu.byu.ece.rapidSmith.cad.cluster.PackUnit
import edu.byu.ece.rapidSmith.cad.cluster.PackUnitList
import edu.byu.ece.rapidSmith.cad.cluster.loadPackUnits
import edu.byu.ece.rapidSmith.cad.cluster.site.PinName
import edu.byu.ece.rapidSmith.cad.cluster.site.SitePackUnitGenerator
import edu.byu.ece.rapidSmith.cad.cluster.site.use
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.device.families.Zynq
import edu.byu.ece.rapidSmith.device.families.Zynq.*
import edu.byu.ece.rapidSmith.util.FileTools
import java.io.IOException
import java.nio.file.Files

class ZynqSitePackUnitGenerator(val device: Device) : SitePackUnitGenerator() {
	override val PACKABLE_SITE_TYPES: List<SiteType>
	override val NULL_TILE_TYPE: TileType
	override val TIEOFF_SITE_TYPE: SiteType
	override val SWITCH_MATRIX_TILES: LinkedHashSet<TileType>
	override val INTERFACE_TILES: LinkedHashSet<TileType>
	override val VCC_SOURCES: Map<BelId, PinName>
	override val GND_SOURCES: Map<BelId, PinName>
	private val IGNORED_TILE_TYPES: LinkedHashSet<TileType>
	private val INSTANCE_NAMES: Map<SiteType, List<String>>

	init {
		PACKABLE_SITE_TYPES = ArrayList()
        addPackableSiteType(SiteTypes.SLICEM)
        addPackableSiteType(SiteTypes.SLICEL)
        addPackableSiteType(SiteTypes.RAMB18E1)
        addPackableSiteType(SiteTypes.RAMB36E1)
        addPackableSiteType(SiteTypes.FIFO18E1)
        addPackableSiteType(SiteTypes.FIFO36E1)
        addPackableSiteType(SiteTypes.RAMBFIFO36E1)
        addPackableSiteType(SiteTypes.DSP48E1)
        addPackableSiteType(SiteTypes.STARTUP)
        addPackableSiteType(SiteTypes.IOB33)
        addPackableSiteType(SiteTypes.IOB33M)
        addPackableSiteType(SiteTypes.IOB33S)
        addPackableSiteType(SiteTypes.BUFG)

		INTERFACE_TILES = LinkedHashSet()
        IGNORED_TILE_TYPES = LinkedHashSet()

        addPackableTileType(INTERFACE_TILES, TileTypes.INT_INTERFACE_L)
        addPackableTileType(INTERFACE_TILES, TileTypes.INT_INTERFACE_R)
        addPackableTileType(INTERFACE_TILES, TileTypes.IO_INT_INTERFACE_L)
        addPackableTileType(INTERFACE_TILES, TileTypes.IO_INT_INTERFACE_R)
        addPackableTileType(INTERFACE_TILES, TileTypes.BRAM_INT_INTERFACE_L)
        addPackableTileType(INTERFACE_TILES, TileTypes.BRAM_INT_INTERFACE_R)
        addPackableTileType(INTERFACE_TILES, TileTypes.RIOI3)
        addPackableTileType(INTERFACE_TILES, TileTypes.LIOI3)

		SWITCH_MATRIX_TILES = LinkedHashSet()
        addPackableTileType(SWITCH_MATRIX_TILES, TileTypes.INT_L)
        addPackableTileType(SWITCH_MATRIX_TILES, TileTypes.INT_R)

		// I think it's fine to keep both of these even with partial devices.
		NULL_TILE_TYPE = TileTypes.NULL
		TIEOFF_SITE_TYPE = SiteTypes.TIEOFF

		IGNORED_TILE_TYPES += TileTypes.BRAM_R
		IGNORED_TILE_TYPES += TileTypes.CLBLL_R
		IGNORED_TILE_TYPES += TileTypes.CLBLM_R
		IGNORED_TILE_TYPES += TileTypes.DSP_R

		INSTANCE_NAMES = HashMap()
        assignPackableSiteInstances()

		VCC_SOURCES = HashMap()
        GND_SOURCES = HashMap()

        if (PACKABLE_SITE_TYPES.contains(SiteTypes.SLICEL)) {
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
        }

        if (PACKABLE_SITE_TYPES.contains(SiteTypes.SLICEM)) {
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
        }

        if (PACKABLE_SITE_TYPES.contains(SiteTypes.IOB33S)) {
            GND_SOURCES[BelId(SiteTypes.IOB33S, "IBUFDISABLE_GND")] = "0"
            GND_SOURCES[BelId(SiteTypes.IOB33S, "INTERMDISABLE_GND")] = "0"
        }


        if (PACKABLE_SITE_TYPES.contains(SiteTypes.IOB33M)) {
            GND_SOURCES[BelId(SiteTypes.IOB33M, "IBUFDISABLE_GND")] = "0"
            GND_SOURCES[BelId(SiteTypes.IOB33M, "INTERMDISABLE_GND")] = "0"
        }


        if (PACKABLE_SITE_TYPES.contains(SiteTypes.IOB33)) {
            GND_SOURCES[BelId(SiteTypes.IOB33, "IBUFDISABLE_GND")] = "0"
            GND_SOURCES[BelId(SiteTypes.IOB33, "INTERMDISABLE_GND")] = "0"
        }

        if (PACKABLE_SITE_TYPES.contains(SiteTypes.ILOGICE2)) {
            GND_SOURCES[BelId(SiteTypes.ILOGICE2, "D2OBYP_TSMUX_GND")] = "0"
            GND_SOURCES[BelId(SiteTypes.ILOGICE2, "D2OFFBYP_TSMUX_GND")] = "0"
        }

        if (PACKABLE_SITE_TYPES.contains(SiteTypes.ILOGICE3)) {
            GND_SOURCES[BelId(SiteTypes.ILOGICE3, "D2OBYP_TSMUX_GND")] = "0"
            GND_SOURCES[BelId(SiteTypes.ILOGICE3, "D2OFFBYP_TSMUX_GND")] = "0"
        }
	}

    fun getTopYCoordinate(type: SiteType): Int {
        return device.sites.values.stream().filter { site -> site.possibleTypes.contains(type)}
                .max(Comparator.comparing(Site::getInstanceY)).get().instanceY
    }

    fun getBottomYCoordinate(type: SiteType): Int {
        return device.sites.values.stream().filter { site -> site.possibleTypes.contains(type)}
                .min(Comparator.comparing(Site::getInstanceY)).get().instanceY
    }

	override fun findClusterInstances(siteType: SiteType, device: Device): List<Site> {
		return INSTANCE_NAMES[siteType]!!.map { device.getSite(it)!! }
	}

    private fun addPackableSiteType(type: SiteType) {
        if (device.hasSiteType(type))
            (PACKABLE_SITE_TYPES as ArrayList<SiteType>).add(type)
    }

    private fun addPackableTileType(tileTypes: HashSet<TileType>, type: TileType) {
        if (device.hasTileType(type))
            tileTypes.add(type)
        else
            (IGNORED_TILE_TYPES as HashSet<TileType>).add(type)
    }

    /**
     * Assign valid site instances for every packable site type. The templates are based off of these instances.
     */
    private fun assignPackableSiteInstances() {
        PACKABLE_SITE_TYPES.forEach {
            val siteInstances = device.getAllSitesOfType(it)
            assert (siteInstances.size > 0)

            // Avoid using sites near device boundaries as instances. For instance, using a SLICEM from the top
            // row of the FPGA will lead to issues since SLICEMs in the top row do not have a connection to continue
            // the carry chain (unlike other SLICEMs).
            // QUESTION: Are there any other similar boundary cases?
            var instance = siteInstances[0]

            for (i in 1 until siteInstances.size) {
                // TODO: Need a way to be able to know the bottom row (instanceY) given only the partial device.
                // TODO: Change device to have build-in coordinate info (to replace the top and bottom y coordinate methods)
                // Add true device size information to partial device??
                // QUESTION: Does this matter? Or just the full device coordinates?
                if (instance.instanceY != getTopYCoordinate(it) && instance.instanceY != getBottomYCoordinate(it))
                    break
                instance = siteInstances[i]
            }
            (INSTANCE_NAMES as HashMap<SiteType, List<String>>)[it] = listOf(instance.name)
        }
    }

	companion object {
		@JvmStatic fun main(args: Array<String>) {
			val part = args[0]
            val partsFolder = RSEnvironment.defaultEnv().getPartFolderPath(Zynq.FAMILY_TYPE)
			val device = Device.getInstance(part, true)
			val env = RSEnvironment.defaultEnv()
			val family = device.family
			val deviceDir = env.getPartFolderPath(family)
			val templatesPath = deviceDir.resolve(part + "_packunits_site.rpu")

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

			//val packUnits = ZynqSitePackUnitGenerator(device).buildFromDevice(device, belCosts)
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
				println("Packunits written to file $templatesPath")
			} catch (e: IOException) {
				println("Error writing for device ...")
			}
		}

		private fun forceRebuild(args: Array<String>) =
			args.size >= 2 && args[1] == "rebuild"

	}
}
