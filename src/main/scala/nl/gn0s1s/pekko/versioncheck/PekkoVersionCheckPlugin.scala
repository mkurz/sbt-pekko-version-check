package nl.gn0s1s.pekko.versioncheck

import sbt._
import sbt.Keys._

object PekkoVersionCheckPlugin extends AutoPlugin {
  case class PekkoVersionReport(
      pekkoVersion: Option[VersionNumber],
      pekkoHttpVersion: Option[VersionNumber],
      pekkoManagementVersion: Option[VersionNumber]
  )

  override def trigger = allRequirements

  object autoImport {
    lazy val pekkoVersionCheckFailBuildOnNonMatchingVersions =
      settingKey[Boolean]("Sets whether non-matching module versions fail the build")
    val pekkoVersionCheck                                    = taskKey[PekkoVersionReport]("Check that all Pekko modules have the same version")
  }

  import autoImport._

  override lazy val globalSettings = Seq(
    pekkoVersionCheckFailBuildOnNonMatchingVersions := false
  )

  override lazy val projectSettings = Seq(
    pekkoVersionCheck := checkModuleVersions(
      updateFull.value,
      streams.value.log,
      pekkoVersionCheckFailBuildOnNonMatchingVersions.value
    )
  )

  private val pekkoModules           = Set(
    "pekko",
    "pekko-actor",
    "pekko-actor-testkit-typed",
    "pekko-actor-tests",
    "pekko-actor-typed",
    "pekko-actor-typed-tests",
    "pekko-cluster",
    "pekko-cluster-metrics",
    "pekko-cluster-sharding",
    "pekko-cluster-sharding-typed",
    "pekko-cluster-tools",
    "pekko-cluster-typed",
    "pekko-coordination",
    "pekko-discovery",
    "pekko-distributed-data",
    "pekko-multi-node-testkit",
    "pekko-osgi",
    "pekko-persistence",
    "pekko-persistence-query",
    "pekko-persistence-shared",
    "pekko-persistence-tck",
    "pekko-persistence-typed",
    "pekko-protobuf",
    "pekko-protobuf-v3",
    "pekko-remote",
    "pekko-serialization-jackson",
    "pekko-slf4j",
    "pekko-stream",
    "pekko-stream-testkit",
    "pekko-stream-typed",
    "pekko-testkit"
  )
  private val pekkoHttpModules       = Set(
    "pekko-http",
    "pekko-http-caching",
    "pekko-http-core",
    "pekko-http-jackson",
    "pekko-http-marshallers-java",
    "pekko-http-marshallers-scala",
    "pekko-http-root",
    "pekko-http-spray-json",
    "pekko-http-testkit",
    "pekko-http-xml",
    "pekko-http2-support",
    "pekko-parsing"
  )
  private val pekkoManagementModules = Set(
    "pekko-discovery-consul",
    "pekko-discovery-aws-api",
    "pekko-discovery-marathon-api",
    "pekko-discovery-aws-api-async",
    "pekko-discovery-kubernetes-api",
    "pekko-lease-kubernetes",
    "pekko-management",
    "pekko-management-cluster-bootstrap",
    "pekko-management-cluster-http"
  )

  private sealed trait Group

  private case object Pekko extends Group

  private case object PekkoHttp extends Group

  private case object PekkoManagement extends Group

  private case object Others extends Group

  private def checkModuleVersions(
      updateReport: UpdateReport,
      log: Logger,
      failBuildOnNonMatchingVersions: Boolean
  ): PekkoVersionReport = {
    log.info("Checking Pekko module versions")
    val allModules             = updateReport.allModules
    val grouped                = allModules.groupBy(m =>
      if (m.organization == "org.apache.pekko") {
        val nameWithoutScalaV = m.name.dropRight(5)
        if (pekkoModules(nameWithoutScalaV)) Pekko
        else if (pekkoHttpModules(nameWithoutScalaV)) PekkoHttp
        else if (pekkoManagementModules(nameWithoutScalaV)) PekkoManagement
        else Others
      }
    )
    val pekkoVersion           = grouped.get(Pekko)
      .flatMap(verifyVersions("Pekko", _, updateReport, log, failBuildOnNonMatchingVersions))
      .map(VersionNumber.apply)
    val pekkoHttpVersion       = grouped.get(PekkoHttp)
      .flatMap(verifyVersions("Pekko HTTP", _, updateReport, log, failBuildOnNonMatchingVersions)
        .map(VersionNumber.apply))
    val pekkoManagementVersion = grouped.get(PekkoManagement)
      .flatMap(verifyVersions("Pekko Management", _, updateReport, log, failBuildOnNonMatchingVersions)
        .map(VersionNumber.apply))

    PekkoVersionReport(pekkoVersion, pekkoHttpVersion, pekkoManagementVersion)
  }

  private def verifyVersions(
      project: String,
      modules: Seq[ModuleID],
      updateReport: UpdateReport,
      log: Logger,
      failBuildOnNonMatchingVersions: Boolean
  ): Option[String] = {
    var throwOnNonMatchingVersions = false

    val result = modules.foldLeft(None: Option[String]) { (prev, module) =>
      prev match {
        case Some(version) =>
          if (module.revision != version) {
            val allModules   = updateReport.configurations.flatMap(_.modules)
            val moduleReport = allModules.find(r =>
              r.module.organization == module.organization && r.module.name == module.name
            )
            val tsText       = moduleReport match {
              case Some(report) =>
                s"Transitive dependencies from ${report.callers.mkString("[", ", ", "]")}"
              case None         =>
                ""
            }
            if (failBuildOnNonMatchingVersions) {
              throwOnNonMatchingVersions = true
              log.error(
                s"""| Non-matching $project module versions, previously seen version $version, but module ${module.name} has version ${module.revision}.
                    | $tsText""".stripMargin.trim
              )
            } else {
              log.warn(
                s"""| Non-matching $project module versions, previously seen version $version, but module ${module.name} has version ${module.revision}.
                    | $tsText""".stripMargin.trim
              )

            }
            Some(version)
          } else Some(version)
        case None          => Some(module.revision)
      }
    }
    if (throwOnNonMatchingVersions) {
      throw NonMatchingVersionsException
    }
    result
  }
}
