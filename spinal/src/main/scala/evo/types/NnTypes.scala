package evo.types

import spinal.core._
import spinal.lib._

object NnConfig {
  val WEIGHT_BITS       = 3
  val BIAS_BITS         = 4
  val NEURON_DATA_WIDTH = 12

  val WEIGHTS_PER_NEURON_EXP = 5
  val WEIGHTS_PER_NEURON     = 1 << WEIGHTS_PER_NEURON_EXP  // 32

  val LAYER_COUNT_EXP = 2
  val LAYER_COUNT     = 1 << LAYER_COUNT_EXP  // 4

  val TOTAL_WEIGHTS = (WEIGHTS_PER_NEURON * WEIGHTS_PER_NEURON) * LAYER_COUNT
  val TOTAL_BIAS    = LAYER_COUNT * WEIGHTS_PER_NEURON
  val TOTAL_PARAMS  = TOTAL_WEIGHTS + TOTAL_BIAS
}

/** A single neuron's parameters. */
case class NeuronParams() extends Bundle {
  import NnConfig._
  val weights = Vec(SInt(WEIGHT_BITS bits), WEIGHTS_PER_NEURON)
  val bias    = SInt(BIAS_BITS bits)
}
