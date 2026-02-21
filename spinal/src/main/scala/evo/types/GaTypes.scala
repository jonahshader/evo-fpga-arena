package evo.types

import spinal.core._
import spinal.lib._

object GaConfig {
  val MAX_POPULATION_SIZE = 128
  val SEED_COUNT_BITS     = 8
  val MAX_SEED_COUNT      = 1 << SEED_COUNT_BITS
}

/** GA configuration — sent from host at runtime. */
case class GaConfigBundle() extends Bundle {
  import GaConfig._
  val mutationRates        = Vec(UInt(8 bits), MAX_POPULATION_SIZE)
  val maxGen               = UInt(16 bits)
  val runUntilStopCmd      = Bool()
  val tournamentSize       = UInt(8 bits)
  val populationSizeExp    = UInt(8 bits)
  val modelHistorySize     = UInt(8 bits)
  val modelHistoryInterval = UInt(8 bits)
  val seed                 = Bits(32 bits)
  val referenceCount       = UInt(8 bits)
  val evalInterval         = UInt(8 bits)
  val seedCount            = UInt(SEED_COUNT_BITS bits)
  val frameLimit           = UInt(16 bits)
  val recycleSeeds         = Bool()
}

/** GA runtime state — reported back to host. */
case class GaStateBundle() extends Bundle {
  val currentGen      = UInt(16 bits)
  val referenceFitness = SInt(16 bits)
}

/** Neuroevolution top-level mode. */
object NeState extends SpinalEnum {
  val IDLE, TRAINING, PLAYING = newElement()
}
