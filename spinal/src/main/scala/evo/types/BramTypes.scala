package evo.types

import spinal.core._
import spinal.lib._

object BramConfig {
  val NUM_BRAMS      = 144
  val BRAM_WIDTH     = 4
  val BRAM_DEPTH     = 4608
  val BRAM_ADDR_BITS = log2Up(BRAM_DEPTH)
}

object BramCommand extends SpinalEnum {
  val COPY_AND_MUTATE, READ_TO_NN_1, READ_TO_NN_2, DUMP = newElement()
}
