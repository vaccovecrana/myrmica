package io.vacco.myrmica.maven.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vacco.myrmica.maven.schema.*;
import io.vacco.myrmica.maven.xform.MmPatchLeft;
import io.vacco.myrmica.maven.xform.MmXform;
import org.slf4j.*;

import java.io.File;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static java.util.Objects.*;
import static java.lang.String.*;
import static io.vacco.myrmica.maven.impl.MmProperties.*;
import static java.util.stream.Collectors.*;

public class MmRepository {

  private static final Logger log = LoggerFactory.getLogger(MmRepository.class);
  private static final ObjectMapper om = new ObjectMapper();
  private static final URL compXml = MmRepository.class.getResource("/io/vacco/myrmica/maven/artifact-handlers.xml");

  public static final Map<MmComponent.Type, MmComponent> defaultComps = MmXform.forComponents(compXml);

  public final Path localRoot;
  public final URI remoteRoot;

  public MmRepository(String localRootPath, String remotePath) {
    try {
      this.localRoot = Paths.get(requireNonNull(localRootPath));
      if (!localRoot.toFile().exists()) {
        throw new IllegalArgumentException(format("Missing root folder: [%s]", localRoot.toAbsolutePath().toString()));
      }
      if (!localRoot.toFile().isDirectory()) {
        throw new IllegalArgumentException(format("Not a directory: [%s]", localRoot.toAbsolutePath().toString()));
      }
      if (!requireNonNull(remotePath).endsWith("/")) {
        throw new IllegalArgumentException(String.format("Remote path does not end with a trailing slash: [%s]", remotePath));
      }
      this.remoteRoot = new URI(remotePath);
    } catch (Exception e) {
      throw new MmException.MmRepositoryInitializationException(localRootPath, remotePath, e);
    }
  }

  public static Path getResourcePath(MmCoordinates coordinates) {
    return Paths.get(
        coordinates.groupId.replace(".", "/"),
        coordinates.artifactId, coordinates.version
    );
  }

  public Path pomPathOf(MmCoordinates coordinates) {
    MmArtifact art = new MmArtifact();
    art.at = coordinates;
    art.comp = defaultComps.get(MmComponent.Type.pom);
    return getResourcePath(coordinates).resolve(art.baseArtifactName());
  }

  public MmPom loadPom(MmCoordinates c) {
    try {
      Path pomPath = pomPathOf(c);
      Path target = localRoot.resolve(pomPath);
      if (!target.toFile().getParentFile().exists()) { target.toFile().getParentFile().mkdirs(); }
      if (!target.toFile().exists()) {
        URI remotePom = remoteRoot.resolve(pomPath.toString());
        log.info("Fetching [{}]", remotePom);
        Files.copy(remotePom.toURL().openStream(), target);
      } else { log.info("Resolving [{}]", target); }
      MmPom pom = MmXform.forPom(target.toUri().toURL());
      if (pom.at.groupId == null || pom.at.version == null) {
        pom.at.groupId = pom.parent.groupId;
        pom.at.version = pom.parent.version;
      }
      return pom;
    }
    catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public MmPom computePom(MmCoordinates coordinates) {
    try {
      List<MmPom> poms = new ArrayList<>();
      List<MmPom> pomsRev;
      Optional<MmCoordinates> oc = Optional.of(coordinates);

      while (oc.isPresent()) {
        MmPom p = loadPom(oc.get());
        poms.add(p);
        oc = p.parent != null ? Optional.of(p.parent) : Optional.empty();
      }

      pomsRev = new ArrayList<>(poms);
      Collections.reverse(pomsRev);
      MmVarContext varCtx = new MmVarContext();

      System.getProperties().forEach((k, v) -> varCtx.set(k.toString(), v));
      varCtx.push();
      varCtx.set("project.build.directory", new File(".").getAbsolutePath());
      varCtx.set("project.groupId", coordinates.groupId);
      varCtx.set("project.artifactId", coordinates.artifactId);
      varCtx.set("project.version", coordinates.version);

      if (poms.size() > 1) {
        varCtx.set("project.parent.groupId", poms.get(1).at.groupId);
        varCtx.set("project.parent.artifactId", poms.get(1).at.artifactId);
        varCtx.set("project.parent.version", poms.get(1).at.version);
      }

      pomsRev.forEach(pom -> pom.properties.forEach(varCtx::set));
      poms.forEach(pom -> resolveVarReferences(pom, varCtx));

      MmVarContext depCtx = new MmVarContext();

      pomsRev.forEach(pom -> {
        Set<MmArtifact> imports = pom.dependencyManagement.stream()
            .filter(art -> art.meta.scopeType == MmArtifactMeta.Scope.Import)
            .flatMap(art -> computePom(art.at).dependencyManagement.stream()).collect(toSet());
        pom.dependencyManagement.addAll(imports);
        pom.dependencyManagement.forEach(art -> depCtx.set(art.at.artifactFormat(), art));
        depCtx.push();
      });

      Optional<MmPom> ePom = new MmPatchLeft().onMultiple(pomsRev);
      if (!ePom.isPresent()) { throw new IllegalStateException("Unable to merge POM hierarchy " + poms); }
      if (log.isDebugEnabled()) {
        log.debug(om.writerWithDefaultPrettyPrinter().writeValueAsString(ePom.get()));
      }

      MmPom pom = ePom.get();
      for (MmArtifact dep : pom.dependencies) {
        if (dep.at.version == null) {
          MmArtifact mDep = (MmArtifact) depCtx.get(dep.at.artifactFormat());
          dep.at = mDep.at;
        }
      }

      return pom;

    } catch (Exception e) {
      throw new MmException.MmPomResolutionException(coordinates, e);
    }
  }

/*

  private OxVtx<String, MmPom> asVtx(MmCoordinates c) {
    return new OxVtx<>(c.getArtifactFormat(), loadPom(c));
  }

  private void buildPomTail(MmCoordinates c, OxGrph<String, MmPom> g) {
    OxVtx<String, MmPom> vtx = asVtx(c);
    if (g.vtx.contains(vtx)) { return; }
    g.vtx.add(vtx);
    for (MmArtifact rtd : vtx.data.getRuntimeDependencies()) {
      buildPomTail(rtd.at, g);
      String artId = rtd.at.getArtifactFormat();
      Optional<OxVtx<String, MmPom>> oRtdVtx = g.vtx.stream().filter(v0 -> v0.id.equals(artId)).findFirst();
      if (oRtdVtx.isPresent()) {
        g.addEdge(vtx, oRtdVtx.get());
        if (!rtd.at.getVersionFormat().equals(oRtdVtx.get().data.coordinates.getVersionFormat())) {
          oRtdVtx.get().data.extraVersions.add(rtd.at);
        }
      }
    }
  }

  public OxGrph<String, MmPom> buildPomGraph(MmCoordinates c) {
    OxGrph<String, MmPom> graph = new OxGrph<>();
    buildPomTail(c, graph);
    return graph;
  }
*/
/*

*/
  /*
  private Path install(Artifact a) {
    requireNonNull(a);
    try {
      Path target = a.getLocalPackagePath(localRoot);
      if (!target.toFile().getParentFile().exists()) { target.toFile().getParentFile().mkdirs(); }
      if (!target.toFile().exists()) {
        URI remoteArtifact = a.getPackageUri(remoteRoot);
        log.info("Downloading [{}]", remoteArtifact);
        Files.copy(remoteArtifact.toURL().openStream(), target);
      }
      return target;
    } catch (Exception e) {
      String msg = String.format("Unable to install artifact [%s]", a);
      throw new IllegalStateException(msg, e);
    }
  }



  /**
   * @see
   *   <a href="https://maven.apache.org/plugins/maven-dependency-plugin/examples/resolving-conflicts-using-the-dependency-tree.html">
   *     resolving-conflicts-using-the-dependency-tree.html
   *   </a>
   *
  private void loadRtTail(DependencyNode context) {
    if (context.getArtifact().isRuntimeClassPath()) {
      Set<Artifact> deps = context.getPom().getDependencies();
      for (Artifact rd : deps) {
        if (!rd.isRuntimeClassPath()) continue;
        if (context.excludes(rd)) continue;
        if (context.isTopLevelOverride(rd)) continue;
        DependencyNode child = new DependencyNode(buildPom(rd.at), rd, context);
        context.getChildren().add(child);
        loadRtTail(child);
      }
    }
  }

  public ResolutionResult loadRuntimeArtifactsAt(Coordinates root) {
    DependencyNode n0 = new DependencyNode(buildPom(root));
    loadRtTail(n0);
    return new ResolutionResult(n0);
  }

  public Map<Artifact, Path> installLoadedArtifacts(ResolutionResult r) {
    return r.artifacts.parallelStream()
        .collect(Collectors.toMap(Function.identity(), this::install));
  }

  public Map<Artifact, Path> installRuntimeArtifactsAt(Coordinates root) {
    return new TreeMap<>(installLoadedArtifacts(loadRuntimeArtifactsAt(root)));
  }
*/
}
