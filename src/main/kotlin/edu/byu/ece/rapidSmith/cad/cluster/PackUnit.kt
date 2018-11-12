package edu.byu.ece.rapidSmith.cad.cluster

import com.caucho.hessian.io.*
import edu.byu.ece.rapidSmith.cad.cluster.site.use
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.FileTools
import edu.byu.ece.rapidSmith.util.Version
import java.io.Serializable
import java.nio.file.Path

/**
 * Class representing a pack unit.
 */
abstract class PackUnit(
    @Transient
	open val type: PackUnitType,
	@Transient
	open val template: PackUnitTemplate
) {
	abstract val version: Version
	abstract val latestVersion: Version
}

/** The type of a pack unit. */
open class PackUnitType(val name: String)

/** Template describing the make-up of a pack unit. */
abstract class PackUnitTemplate {
	/** BelPins that can act as vcc sources. */
	abstract val vccSources: Set<BelPin>
	/** BelPins that can act as gnd sources. */
	abstract val gndSources: Set<BelPin>
	/** Connections sourcing pins in this cluster without going through
	 * general routing. */
	abstract val directSourcesOfCluster: List<DirectConnection>
	/** Connections from this cluster sourcing pins in other clusters without
	 * going through general routing. */
	abstract val directSinksOfCluster: List<DirectConnection>
	/** Bels in this cluster */
	abstract val bels: Collection<Bel>
	/** Pins sourcing the general routing fabric out of this cluster. */
	abstract val outputs: List<Wire>
	/** Returns all of the pins inputs that source the specified BelPin */
	abstract fun getInputsOfSink(sinkPin: BelPin): List<Wire>?
	/** Inputs to this cluster coming from the general routing fabric. */
	abstract val inputs: Set<Wire>
	/** The device that describes the resources and routing in this pack unit. */
	abstract val device: Device
}

/** Class containing all of the pack units supported by a device */
class PackUnitList<out T: PackUnit>(
	/** The part this list was made from */
	val part: String,
	val packUnits: List<T>,
	/** All pins in the device that drive general fabric */
	val pinsDrivingGeneralFabric: Map<BelId, Set<String>>,
	/** All pins in the device that are driven by general fabric */
	val pinsDrivenByGeneralFabric: Map<BelId, Set<String>>
) : List<T> by packUnits, Serializable

/** Loads pack units from a compressed file */
fun <T: PackUnit> loadPackUnits(path: Path): PackUnitList<T> {
	return FileTools.getCompactReader(path).use { his ->
		val serializeFactory = object : AbstractSerializerFactory() {
			override fun getDeserializer(cl: Class<*>?): Deserializer? {
				return when (cl) {
					PackUnitList::class.java -> UnsafeDeserializer(cl)
					else -> null
				}
			}

			override fun getSerializer(cl: Class<*>?): Serializer? {
				return null
			}
		}
		his.serializerFactory.addFactory(serializeFactory)

		@Suppress("UNCHECKED_CAST")
		val pus = his.readObject() as PackUnitList<T>
		for (pu in pus)
			if (pu.version < pu.latestVersion) {
				System.err.println("Using outdated pack unit version")
				break
			}
		pus
	}
}
