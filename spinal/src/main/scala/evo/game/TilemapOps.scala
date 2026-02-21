package evo.game

import spinal.core._
import spinal.lib._
import evo.types._
import evo.types.MapConfig._

/**
 * Combinational tilemap operations: tile lookup, coordinate conversion, spawn sampling.
 *
 * These mirror the VHDL package functions in game_types.vhd. In SpinalHDL they
 * produce hardware when called inside a Component context.
 */
object TilemapOps {

  /** Convert a pixel coordinate to a tile index (right-shift by TILE_PX_BITS). */
  def pixelToTile(pixel: SInt): SInt = pixel >> TILE_PX_BITS

  /** Convert a tile index to pixel coordinate (left-shift by TILE_PX_BITS). */
  def tileToPixel(tile: SInt): SInt = tile |<< TILE_PX_BITS

  /**
   * Look up a tile from the tilemap given signed tile coordinates.
   * Implements bounds checking matching the VHDL:
   *   - x < 0 or y < 0 or x >= width => GROUND
   *   - y >= height => AIR
   *   - otherwise => tiles(height - 1 - y)(x)  (y-flip for y-up ordering)
   */
  def getTile(tm: Tilemap, tileX: SInt, tileY: SInt): SpinalEnumCraft[TileType.type] = {
    val result = TileType()

    val outOfBoundsX = tileX < 0 || tileX >= tm.width.asSInt.resized
    val belowZeroY   = tileY < 0
    val aboveMapY    = tileY >= tm.height.asSInt.resized

    when(outOfBoundsX || belowZeroY) {
      result := TileType.GROUND
    } elsewhen(aboveMapY) {
      result := TileType.AIR
    } otherwise {
      val flippedY = (tm.height - 1).asSInt.resized - tileY
      result := tm.tiles(flippedY.asUInt.resized)(tileX.asUInt.resized)
    }
    result
  }

  /**
   * Convert a TilePos to an F4Vec (pixel coordinates as fixed-point).
   * Single shift: tile << (TILE_PX_BITS + fracBits) places the value
   * directly into the correct SFix raw position.
   */
  def tilePosToF4Vec(pos: TilePos): F4Vec = {
    val vec = F4Vec()
    val totalShift = TILE_PX_BITS + F4.fracBits
    vec.x.raw := (pos.x << totalShift).asSInt.resized
    vec.y.raw := (pos.y << totalShift).asSInt.resized
    vec
  }

  /**
   * Sample a spawn location from the tilemap using RNG bits.
   * Takes low bits from rng, wraps if >= numSpawn.
   */
  def sampleSpawn(tm: Tilemap, rng: Bits): TilePos = {
    val index = UInt(8 bits)
    val rawIndex = rng(7 downto 0).asUInt.resized
    when(rawIndex >= tm.numSpawn) {
      index := rawIndex - tm.numSpawn
    } otherwise {
      index := rawIndex
    }
    tm.spawn(index.resized)
  }
}
