package evo.nn

import spinal.core._
import spinal.lib._
import evo.types._

/**
 * Mutation functions for neural network parameters.
 *
 * Pure combinational functions that mirror the VHDL package mutate_funs.
 * These produce hardware when called inside a Component context.
 */
object Mutate {

  /**
   * Mutate a single parameter value.
   *
   * This function implements uniform random mutation with delta values in
   * the range [-4, -3, -2, -1, +1, +2, +3, +4] (note: 0 is excluded).
   * The result is clamped differently for weights vs biases.
   *
   * @param param       The 4-bit parameter value to mutate (signed interpretation)
   * @param paramIndex  The parameter's index in the chromosome (determines clamp range)
   * @param rng         32-bit random value for mutation decision and delta selection
   * @param mutationRate 8-bit mutation rate; mutation occurs if rng[7:0] < mutationRate
   * @return The mutated 4-bit parameter value
   */
  def mutateParam(
    param: Bits,
    paramIndex: UInt,
    rng: Bits,
    mutationRate: UInt
  ): Bits = {
    // Extra bit for overflow handling during mutation addition
    val extendedWidth = 5  // param'length + 1 = 4 + 1 = 5 bits

    // Check if mutation should occur
    val shouldMutate = rng(7 downto 0).asUInt < mutationRate

    // Select mutation delta based on rng[10:8]
    // Values 0-7 map to: -1, +1, -2, +2, -3, +3, -4, +4
    val delta = SInt(extendedWidth bits)
    switch(rng(10 downto 8).asUInt) {
      is(0) { delta := -1 }
      is(1) { delta := 1 }
      is(2) { delta := -2 }
      is(3) { delta := 2 }
      is(4) { delta := -3 }
      is(5) { delta := 3 }
      is(6) { delta := -4 }
      is(7) { delta := 4 }
    }

    // Convert param to signed and sign-extend to extendedWidth
    val paramSigned = SInt(extendedWidth bits)
    paramSigned := (param.asSInt).resize(extendedWidth)

    // Compute the result
    val result = SInt(extendedWidth bits)
    result := paramSigned  // Default: no mutation

    when(shouldMutate) {
      val withDelta = paramSigned + delta

      // Clamp based on parameter type
      val isWeight = paramIndex < NnConfig.TOTAL_WEIGHTS

      when(isWeight) {
        // Weights are clamped to [-2, 2]
        when(withDelta < -2) {
          result := -2
        } .elsewhen(withDelta > 2) {
          result := 2
        } .otherwise {
          result := withDelta
        }
      } otherwise {
        // Biases are clamped to [-7, 7]
        when(withDelta < -7) {
          result := -7
        } .elsewhen(withDelta > 7) {
          result := 7
        } .otherwise {
          result := withDelta
        }
      }
    }

    // Resize back to 4 bits for the result
    result.resize(4).asBits
  }
}
