import sbt._
import java.io.{ FileOutputStream, File }
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.resolve.DownloadOptions
import org.apache.ivy.core.module.descriptor.DefaultArtifact

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
    Kind("doc", "jar", "e:classifier" -> "javadoc")).flatMap {
      case k @ Kind(a, b, c @ _*) ⇒ Seq(k, Kind(a, b + ".md5", c: _*), Kind(a, b + ".sha1", c: _*))
    }

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
      def publish(a: DefaultArtifact, from: File): Unit = target.publish(a, from, true)

      val rep = ivy.getResolveEngine.download(artifact(), new DownloadOptions)

      val file =
        if (kind.ext == "pom") {
          val res = IO.read(rep.getLocalFile)
          val devSection =
            """    <scm>
              |       <url>git://github.com/spray/spray.git</url>
              |       <connection>scm:git:git@github.com:spray/spray.git</connection>
              |    </scm>
              |    <developers>
              |       <developer><id>sirthias</id><name>Mathias Doenitz</name></developer>
              |       <developer><id>jrudolph</id><name>Johannes Rudolph</name></developer>
              |    </developers>
              |</project>""".stripMargin
          val fixed = res.replaceAllLiterally("</project>", devSection)
          val temp = File.createTempFile("fixed", ".pom")
          temp.delete()
          temp.deleteOnExit()
          IO.write(temp, fixed)
          temp
        } else rep.getLocalFile

      publish(artifact(), file)

      if (Set("jar", "pom")(kind.ext)) {
        val tempAsc = File.createTempFile("signer", ".asc")
        tempAsc.delete()
        tempAsc.deleteOnExit()
        Process(gpgPath, Seq("-ab", "-o", tempAsc.getAbsolutePath, file.getAbsolutePath)).!
        val fos = new FileOutputStream(tempAsc, true)
        fos.write("abc".getBytes)
        fos.close()

        publish(artifact(".asc"), tempAsc)
      }
    }
  }
}
