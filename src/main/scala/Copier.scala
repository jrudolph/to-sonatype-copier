import java.security.MessageDigest
import sbt._
import java.io.{ FileOutputStream, File }
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.resolve.DownloadOptions
import org.apache.ivy.core.module.descriptor.DefaultArtifact
import scala.util.Try

object Copier extends App {
  val gpgPath = "/usr/local/bin/gpg"
  val credentialsPath = "/tmp/.credentials"
  val org = "io.spray"
  val versions = Seq("1.0.0", "1.1.0", "1.2.0")
  val modules = Seq("can", "util", "routing", "testkit", "http", "httpx", "caching", "client", "io", "servlet").map("spray-"+)
  val kinds = Seq(
    Kind("jar", "jar"),
    Kind("pom", "pom"),
    Kind("src", "jar", "e:classifier" -> "sources"),
    Kind("doc", "jar", "e:classifier" -> "javadoc"))

  case class Kind(tpe: String, ext: String, extra: (String, String)*)
  val artifacts = Seq("")

  val logger = ConsoleLogger()
  val sprayResolver = MavenRepository("spray", "http://repo.spray.io/")
  Credentials.add(new File(credentialsPath), logger)

  val release = MavenRepository("oss", "https://oss.sonatype.org/service/local/staging/deploy/maven2")

  val config = new InlineIvyConfiguration(
    new IvyPaths(new File("."), None),
    Seq(DefaultMavenRepository, sprayResolver, release), Nil, Nil, false, None, Nil, None, logger)
  val ivy = new IvySbt(config)

  val sha1 = () ⇒ MessageDigest.getInstance("sha1")
  val md5 = () ⇒ MessageDigest.getInstance("md5")

  ivy.withIvy(logger) { ivy ⇒
    def module(name: String, version: String) = ModuleRevisionId.newInstance(org, name, version)
    val target = ivy.getSettings.getResolver("oss")

    for {
      name ← modules
      version ← versions
      kind ← kinds
    } {
      import scala.collection.JavaConverters._
      val ivyModule = module(name, version)
      def artifact(extraExt: String = "") = new DefaultArtifact(ivyModule, null, ivyModule.getName, kind.tpe, kind.ext + extraExt, kind.extra.toMap.asJava)
      def publish(a: DefaultArtifact, from: File): Unit =
        Try(target.publish(a, from, true))

      def publishHashes(suffix: String, file: File): Unit = {
        publish(artifact(suffix + ".md5"), hash(md5, file))
        publish(artifact(suffix + ".sha1"), hash(sha1, file))
      }

      val rep = ivy.getResolveEngine.download(artifact(), new DownloadOptions)

      val file =
        if (kind.ext == "pom") {
          val res = IO.read(rep.getLocalFile)
          val devSection =
            """<scm>
              |       <url>git://github.com/spray/spray.git</url>
              |       <connection>scm:git:git@github.com:spray/spray.git</connection>
              |    </scm>
              |    <developers>
              |       <developer><id>sirthias</id><name>Mathias Doenitz</name></developer>
              |       <developer><id>jrudolph</id><name>Johannes Rudolph</name></developer>
              |    </developers>""".stripMargin
          val fixed = res.replaceAll("""(?s:<repositories>.*?</repositories>)""", devSection)
          val temp = File.createTempFile("fixed", ".pom")
          temp.delete()
          temp.deleteOnExit()
          IO.write(temp, fixed)
          temp
        } else rep.getLocalFile

      publish(artifact(), file)
      publishHashes("", file)

      if (Set("jar", "pom")(kind.ext)) {
        val tempAsc = tempFile()
        Process(gpgPath, Seq("-ab", "-o", tempAsc.getAbsolutePath, file.getAbsolutePath)).!

        publish(artifact(".asc"), tempAsc)
        publishHashes(".asc", tempAsc)
      }
    }
  }

  def hash(digest: () ⇒ MessageDigest, file: File): File = {
    val md = digest()
    val res = md.digest(IO.readBytes(file))
    tempFile(res.map(b ⇒ (b & 0xff) formatted "%02x").mkString)
  }

  def tempFile(value: String): File = {
    val t = tempFile()
    IO.write(t, value)
    t
  }
  def tempFile(): File = {
    val temp = File.createTempFile("temp", ".tmp")
    temp.delete()
    temp.deleteOnExit()
    temp
  }
}
