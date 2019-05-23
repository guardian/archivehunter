package models

object BulkRestoreStats extends ((Int,Int,Int,Int)=>BulkRestoreStats) {
  def empty:BulkRestoreStats = new BulkRestoreStats(0,0,0,0)
}

case class BulkRestoreStats(unneeded:Int, inProgress:Int, available:Int, notRequested:Int)
