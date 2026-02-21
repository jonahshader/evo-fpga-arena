package evo.nn

import spinal.core._
import spinal.lib._
import evo.types._

/**
 * Neural Network Parameter Decoder
 *
 * Pure combinational functions for decoding a parameter index into
 * layer, neuron, and weight indices via bit-slicing.
 *
 * This mirrors the VHDL package functions in decoder_funs.vhd.
 *
 * The param_index is a flat address that encodes three fields:
 * - bits [0+0*WEIGHTS_PER_NEURON_EXP : WEIGHTS_PER_NEURON_EXP]  -> weight_index
 * - bits [0+1*WEIGHTS_PER_NEURON_EXP : 2*WEIGHTS_PER_NEURON_EXP] -> neuron_index
 * - bits [0+2*WEIGHTS_PER_NEURON_EXP : LAYER_COUNT_EXP]           -> layer_index
 *
 * For bias addressing, the weight_index field is not used (we start
 * from TOTAL_WEIGHTS).
 *
 * Ported from fpga/src/neural_network/decoder_funs.vhd
 */
object Decoder {

  import NnConfig._

  /**
   * Decode a parameter index and value, returning the updated layers.
   *
   * This is a pure combinational function that takes:
   * - layers: current neural network parameters
   * - param: the parameter value (4 bits)
   * - param_index: flat parameter address
   *
   * Returns: updated layers with the parameter applied
   *
   * The function has two modes based on param_index:
   * - If index < TOTAL_WEIGHTS: update a weight
   * - If index < TOTAL_PARAMS: update a bias
   */
  def decodeAddress(
    layers: Vec[Vec[NeuronParams]],
    param: Bits,
    paramIndex: UInt
  ): Vec[Vec[NeuronParams]] = {
    // Create a new layers structure - we'll conditionally assign to it
    // Since we can't modify Vec in place, we use a different approach:
    // create the updated structure based on the decoded indices

    val indexValue = paramIndex

    // Determine if we're updating a weight or bias
    val isWeight = indexValue < TOTAL_WEIGHTS
    val isBias = indexValue >= TOTAL_WEIGHTS && indexValue < TOTAL_PARAMS

    // Extract the bit fields for weight addressing
    // weight_index: bits [WEIGHTS_PER_NEURON_EXP*0 : WEIGHTS_PER_NEURON_EXP]
    val weightIndex = indexValue(WEIGHTS_PER_NEURON_EXP - 1 downto 0)

    // neuron_index: bits [WEIGHTS_PER_NEURON_EXP*1 : WEIGHTS_PER_NEURON_EXP*2]
    val neuronIndex = indexValue(2 * WEIGHTS_PER_NEURON_EXP - 1 downto WEIGHTS_PER_NEURON_EXP)

    // layer_index: bits [WEIGHTS_PER_NEURON_EXP*2 : WEIGHTS_PER_NEURON_EXP*2 + LAYER_COUNT_EXP]
    val layerIndex = indexValue(2 * WEIGHTS_PER_NEURON_EXP + LAYER_COUNT_EXP - 1 downto 2 * WEIGHTS_PER_NEURON_EXP)

    // For bias addressing, we need to subtract TOTAL_WEIGHTS first
    val biasAdjustedIndex = indexValue - TOTAL_WEIGHTS
    val biasNeuronIndex = biasAdjustedIndex(WEIGHTS_PER_NEURON_EXP - 1 downto 0)
    val biasLayerIndex = biasAdjustedIndex(WEIGHTS_PER_NEURON_EXP + LAYER_COUNT_EXP - 1 downto WEIGHTS_PER_NEURON_EXP)

    // Build the updated layers structure
    // We iterate through all layers/neurons/weights and conditionally update
    val result = Vec(Vec(NeuronParams(), WEIGHTS_PER_NEURON), LAYER_COUNT)

    for (layerIdx <- 0 until LAYER_COUNT) {
      for (neuronIdx <- 0 until WEIGHTS_PER_NEURON) {
        val layerMatch = isWeight && (layerIndex === layerIdx) ||
                         isBias && (biasLayerIndex === layerIdx)
        val neuronMatch = isWeight && (neuronIndex === neuronIdx) ||
                          isBias && (biasNeuronIndex === neuronIdx)

        for (weightIdx <- 0 until WEIGHTS_PER_NEURON) {
          val weightMatch = isWeight && (weightIndex === weightIdx)

          // Default: copy from input
          result(layerIdx)(neuronIdx).weights(weightIdx) := layers(layerIdx)(neuronIdx).weights(weightIdx)

          // Conditionally update weight
          when(isWeight && layerIndex === layerIdx && neuronIndex === neuronIdx && weightIndex === weightIdx) {
            result(layerIdx)(neuronIdx).weights(weightIdx) := param.asSInt.resized
          }
        }

        // Default: copy bias from input
        result(layerIdx)(neuronIdx).bias := layers(layerIdx)(neuronIdx).bias

        // Conditionally update bias
        when(isBias && biasLayerIndex === layerIdx && biasNeuronIndex === neuronIdx) {
          result(layerIdx)(neuronIdx).bias := param.asSInt.resized
        }
      }
    }

    result
  }

  /**
   * Extract just the layer index from a parameter index.
   * Useful for diagnostics or debug logic.
   */
  def getLayerIndex(paramIndex: UInt): UInt = {
    val indexValue = paramIndex
    val isWeight = indexValue < TOTAL_WEIGHTS

    val weightLayerIndex = indexValue(2 * WEIGHTS_PER_NEURON_EXP + LAYER_COUNT_EXP - 1 downto 2 * WEIGHTS_PER_NEURON_EXP)
    val biasAdjustedIndex = indexValue - TOTAL_WEIGHTS
    val biasLayerIndex = biasAdjustedIndex(WEIGHTS_PER_NEURON_EXP + LAYER_COUNT_EXP - 1 downto WEIGHTS_PER_NEURON_EXP)

    Mux(isWeight, weightLayerIndex, biasLayerIndex).resized
  }

  /**
   * Extract just the neuron index from a parameter index.
   */
  def getNeuronIndex(paramIndex: UInt): UInt = {
    val indexValue = paramIndex
    val isWeight = indexValue < TOTAL_WEIGHTS

    val weightNeuronIndex = indexValue(2 * WEIGHTS_PER_NEURON_EXP - 1 downto WEIGHTS_PER_NEURON_EXP)
    val biasAdjustedIndex = indexValue - TOTAL_WEIGHTS
    val biasNeuronIndex = biasAdjustedIndex(WEIGHTS_PER_NEURON_EXP - 1 downto 0)

    Mux(isWeight, weightNeuronIndex, biasNeuronIndex).resized
  }

  /**
   * Extract just the weight index from a parameter index.
   * Only valid for weight addresses (< TOTAL_WEIGHTS).
   */
  def getWeightIndex(paramIndex: UInt): UInt = {
    paramIndex(WEIGHTS_PER_NEURON_EXP - 1 downto 0)
  }

  /**
   * Check if a parameter index refers to a weight (not a bias).
   */
  def isWeightIndex(paramIndex: UInt): Bool = {
    paramIndex < TOTAL_WEIGHTS
  }

  /**
   * Check if a parameter index refers to a bias (not a weight).
   */
  def isBiasIndex(paramIndex: UInt): Bool = {
    val idx = paramIndex
    idx >= TOTAL_WEIGHTS && idx < TOTAL_PARAMS
  }
}
