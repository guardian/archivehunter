import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration

class ArchiveHunterConfigurationMock extends ArchiveHunterConfiguration {
  override def getOptional[T](key: String)(implicit converter: String => T): Option[T] = None

  override def get[T](key: String)(implicit converter: String => T): T = converter(key)
}
